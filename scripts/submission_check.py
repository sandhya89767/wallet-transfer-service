#!/usr/bin/env python3
"""Operator-only persistence check and private reviewer bundle preparation.

Secrets are entered with getpass, never printed, and never written to disk.
Signed tokens/state live in a git-ignored, owner-only directory. Evidence JSON
contains only request results and identifiers, never authentication headers.
"""
import argparse
import datetime
import getpass
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

from issue_token import ROLES, issue

ROOT = Path(__file__).resolve().parent.parent
PRIVATE = ROOT / '.submission-private'


def private_write(path, data):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # Exclusive creation prevents overwriting earlier evidence or token bundles.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        json.dump(data, stream, indent=2)
        stream.write('\n')


def request(base, method, path, correlation, token=None, body=None, expected=200):
    headers = {'X-Correlation-Id': correlation}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=120 if 'health' in path else 30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        code = response.status
        content = response.read().decode()
        returned_correlation = response.headers.get('X-Correlation-Id')
    if code != expected:
        raise RuntimeError(f'{method} {path}: expected HTTP {expected}, got {code}; correlation={correlation}')
    if returned_correlation != correlation:
        raise RuntimeError('Correlation header missing or changed')
    print(f'{method} {path}: HTTP {code}', flush=True)
    return content if path == '/metrics' else json.loads(content)


def process_start(base, correlation):
    metrics = request(base, 'GET', '/metrics', correlation)
    match = re.search(r'^process_start_time_seconds(?:\{[^\n]*\})? ([0-9.eE+\-]+)$', metrics, re.MULTILINE)
    if not match:
        raise RuntimeError('Process start metric missing; cannot prove restart')
    return float(match.group(1))


def prepare(base):
    run = str(uuid.uuid4())
    folder = PRIVATE / run
    correlation = 'persistence-' + run
    secret = getpass.getpass('Paste deployed WALLET_AUTH_SECRET here (hidden), then Enter: ')
    if len(secret.encode()) < 32:
        raise ValueError('Signing secret must be at least 32 UTF-8 bytes')
    expires = int(time.time()) + 86400
    users = {role: issue(run + '-' + role, secret, expires) for role in ('sender', 'recipient')}
    # Recording and reviewer identities are separate: demonstration must not
    # consume the reviewer fixtures or reveal the tokens to be delivered.
    reviewer = {role: issue(str(uuid.uuid4()) + '-' + role, secret, expires) for role in ROLES}
    recording = {role: issue(str(uuid.uuid4()) + '-' + role, secret, expires) for role in ROLES}
    del secret
    ready = request(base, 'GET', '/actuator/health/readiness', correlation)
    if ready.get('status') != 'UP':
        raise RuntimeError('Service not ready')
    # Verify signing key using a read-only authenticated lookup before saving.
    request(base, 'GET', '/wallets/' + str(uuid.uuid4()), correlation, users['sender'], expected=404)
    private_write(folder / 'reviewer-tokens.json', reviewer)
    private_write(folder / 'recording-tokens.json', recording)
    metadata = {'base_url': base, 'expires_at_utc': datetime.datetime.fromtimestamp(
        expires, datetime.timezone.utc).isoformat(), 'run_id': run,
        'note': 'Private bearer tokens, not the signing secret. Use fresh reviewer fixtures once.'}
    private_write(folder / 'delivery-info.json', metadata)
    start = process_start(base, correlation)
    source = request(base, 'POST', '/wallets', correlation, users['sender'],
                     {'initial_balance_paise': 100000}, expected=201)
    destination = request(base, 'POST', '/wallets', correlation, users['recipient'],
                          {'initial_balance_paise': 20000}, expected=201)
    body = {'from': source['id'], 'to': destination['id'], 'amount_paise': 7500,
            'idempotency_key': run + '-persist'}
    transfer = request(base, 'POST', '/transfers', correlation, users['sender'], body)
    if transfer['status'] != 'SUCCEEDED':
        raise RuntimeError('Initial transfer did not succeed')
    balances = [request(base, 'GET', '/wallets/' + wallet['id'], correlation, users[role])['balance_paise']
                for role, wallet in [('sender', source), ('recipient', destination)]]
    if balances != [92500, 27500]:
        raise RuntimeError('Unexpected balances before redeployment')
    state = {'base': base, 'correlation_id': correlation, 'tokens': users, 'body': body,
             'transfer': transfer, 'balances': balances, 'process_start_before': start}
    private_write(folder / 'persistence-state.json', state)
    evidence = {key: value for key, value in state.items() if key != 'tokens'}
    evidence['phase'] = 'before_redeploy'
    private_write(folder / 'before-public-evidence.json', evidence)
    print('PREPARED: one transfer committed; balances 92500 / 27500 paise.', flush=True)
    print('Correlation:', correlation)
    print('Private working folder:', folder)
    print('Reviewer bundle expiry UTC:', metadata['expires_at_utc'])
    print('Redeploy the service, then run the after check against this private folder.')


def after(folder):
    folder = Path(folder).resolve()
    state = json.loads((folder / 'persistence-state.json').read_text())
    base, correlation = state['base'], state['correlation_id']
    ready = request(base, 'GET', '/actuator/health/readiness', correlation)
    if ready.get('status') != 'UP':
        raise RuntimeError('Service not ready')
    start = process_start(base, correlation)
    if start <= state['process_start_before']:
        raise RuntimeError('No newer process detected; redeployment must complete first')
    fetched = request(base, 'GET', '/transfers/' + state['transfer']['id'], correlation, state['tokens']['sender'])
    if fetched != state['transfer']:
        raise RuntimeError('Persisted transfer response differs after redeploy')
    for phase in ('before_replay', 'after_replay'):
        if phase == 'after_replay':
            replay = request(base, 'POST', '/transfers', correlation, state['tokens']['sender'], state['body'])
            if replay != state['transfer']:
                raise RuntimeError('Same-key replay did not return identical persisted result')
        balances = [request(base, 'GET', '/wallets/' + state['body'][field], correlation,
                            state['tokens'][role])['balance_paise']
                    for role, field in [('sender', 'from'), ('recipient', 'to')]]
        if balances != state['balances']:
            raise RuntimeError('Balances changed at ' + phase)
    evidence = {'result': 'PASS', 'base_url': base, 'correlation_id': correlation,
                'transfer_id': fetched['id'], 'balances_before_redeploy': state['balances'],
                'balances_after_redeploy_and_replay': balances,
                'same_key_identical_result': True, 'process_start_before': state['process_start_before'],
                'process_start_after': start, 'checked_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    private_write(folder / 'after-public-evidence.json', evidence)
    print(json.dumps(evidence, indent=2))
    print('PASS: real process replacement, persisted transfer, identical same-key replay, no second debit.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare').add_argument('--base', required=True)
    sub.add_parser('after').add_argument('--folder', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            if not args.base.startswith('https://'):
                raise ValueError('A public HTTPS API URL is required')
            prepare(args.base.rstrip('/'))
        else:
            after(args.folder)
    except Exception as error:
        # Avoid tracebacks or accidental secret-containing exception messages.
        print('FAILED:', str(error) if isinstance(error, (RuntimeError, ValueError)) else type(error).__name__, file=sys.stderr)
        sys.exit(1)