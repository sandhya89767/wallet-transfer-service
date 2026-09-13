#!/usr/bin/env python3
"""HTTP-only acceptance probes. No third-party Python packages or database access."""
from concurrent.futures import ThreadPoolExecutor
import json
import itertools
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from issue_token import DEMO_SECRET, ROLES, issue

BASE = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
BASES = [url.rstrip("/") for url in os.environ.get("BASE_URLS", BASE).split(",")]
URLS = itertools.cycle(BASES)
URL_LOCK = threading.Lock()
RUN = str(uuid.uuid4())
LATENCIES = []
LATENCY_LOCK = threading.Lock()
fixture_path = os.environ.get("BURST_TOKENS_FILE")
if fixture_path:
    with open(fixture_path, encoding="utf-8") as stream:
        TOKENS = json.load(stream)
    if not all(role in TOKENS for role in ROLES):
        sys.exit("Token bundle must contain race, sender, recipient, a, and b")
else:
    secret = os.environ.get("WALLET_AUTH_SECRET")
    if not secret:
        if any(urllib.parse.urlparse(url).hostname not in ("localhost", "127.0.0.1", "::1") for url in BASES):
            sys.exit("Remote runs require BURST_TOKENS_FILE or operator WALLET_AUTH_SECRET")
        secret = DEMO_SECRET
    TOKENS = {role: issue(f"{RUN}-{role}", secret) for role in ROLES}


def request(method, path, role=None, body=None, expected=200):
    headers = {"X-Correlation-Id": f"burst-{RUN}"}
    if role is not None:
        headers["Authorization"] = "Bearer " + TOKENS[role]
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    started = time.monotonic()
    with URL_LOCK:
        base = next(URLS)
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.code
        raw = response.read().decode()
        correlation = response.headers.get("X-Correlation-Id")
    with LATENCY_LOCK:
        LATENCIES.append(time.monotonic() - started)
    assert status == expected, f"{method} {path}: expected {expected}, got {status}: {raw[:400]}"
    assert correlation == f"burst-{RUN}", "Correlation ID was not propagated"
    return json.loads(raw)


def concurrent(calls, workers=40):
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(lambda call: call(), calls))


def wallet(role, initial=0):
    return request("POST", "/wallets", role, {"initial_balance_paise": initial}, expected=201)


def balance(role, wallet_id):
    value = request("GET", f"/wallets/{wallet_id}", role)["balance_paise"]
    assert type(value) is int and value >= 0
    return value


def transfer(role, source, destination, amount, key, expected=200):
    return request("POST", "/transfers", role, {
        "from": source, "to": destination, "amount_paise": amount, "idempotency_key": key,
    }, expected)


def wallet_race():
    responses = concurrent([lambda: wallet("race", 100000) for _ in range(50)], 50)
    assert len(responses) == 50
    assert len({result["id"] for result in responses}) == 1
    assert all(result == responses[0] for result in responses)
    print("PASS wallet race: 50 responses, exactly one wallet, identical bodies")


def idempotency():
    source, destination = wallet("sender", 100000)["id"], wallet("recipient", 20000)["id"]
    before = balance("sender", source), balance("recipient", destination)
    assert before[0] >= 7500, "Use a fresh token bundle; sender has insufficient seed funds"
    key = f"{RUN}-retry"
    responses = concurrent([lambda: transfer("sender", source, destination, 7500, key) for _ in range(30)], 30)
    assert len(responses) == 30 and all(result == responses[0] for result in responses)
    assert responses[0]["status"] == "SUCCEEDED"
    assert balance("sender", source) == before[0] - 7500
    assert balance("recipient", destination) == before[1] + 7500
    transfer("sender", source, destination, 7501, key, expected=409)
    for role in ("sender", "recipient"):
        assert request("GET", "/transfers/" + responses[0]["id"], role) == responses[0]
    print("PASS retries: 30 identical responses, one debit/credit, changed body returns 409")


def contention():
    a, b = wallet("a", 100000)["id"], wallet("b", 100000)["id"]
    before = balance("a", a) + balance("b", b)
    assert balance("a", a) >= 20000 and balance("b", b) >= 20000, "Use fresh funded identities"
    calls = []
    for i in range(440):
        role, source, destination = ("a", a, b) if i % 2 == 0 else ("b", b, a)
        amount = 100 if i < 400 else before + 1
        calls.append(lambda r=role, s=source, d=destination, v=amount, k=f"{RUN}-{i}": transfer(r, s, d, v, k))
    results = concurrent(calls)
    assert len(results) == 440 and len({result["id"] for result in results}) == 440
    assert sum(result["status"] == "SUCCEEDED" for result in results) == 400
    declines = [result for result in results if result["status"] == "DECLINED"]
    assert len(declines) == 40 and all(result["failure_reason"] == "INSUFFICIENT_FUNDS" for result in declines)
    after = balance("a", a), balance("b", b)
    assert sum(after) == before
    # Terminal declines must also be replayable, with no new movement.
    declined = results[400]
    assert transfer("a", a, b, before + 1, f"{RUN}-400") == declined
    print(f"PASS contention: 400 successes, 40 declines, total conserved ({before}), balances={after}")


def validation():
    request("GET", "/wallets/" + str(uuid.uuid4()), expected=401)
    request("GET", "/wallets/not-a-uuid", "race", expected=400)
    request("POST", "/wallets", "race", {"initial_balance_paise": 1.5}, expected=400)
    print("PASS HTTP validation: missing authentication, invalid UUID, fractional paise rejected")


def main():
    # Keep Python optimizations from silently removing acceptance assertions.
    if not __debug__:
        sys.exit("Do not run acceptance probes with python -O")
    scenario = sys.argv[1] if len(sys.argv) > 1 else "all"
    scenarios = {"wallet": wallet_race, "idempotency": idempotency, "contention": contention, "validation": validation}
    if scenario == "all":
        for run in scenarios.values():
            run()
    elif scenario in scenarios:
        scenarios[scenario]()
    else:
        sys.exit("Usage: burst.sh [all|wallet|idempotency|contention|validation]")
    ordered = sorted(LATENCIES)
    print(json.dumps({"result": "PASS", "requests": len(ordered), "run_id": RUN,
                      "client_p99_ms": round(ordered[min(len(ordered)-1, int(len(ordered)*0.99))] * 1000, 2)}))


if __name__ == "__main__":
    main()