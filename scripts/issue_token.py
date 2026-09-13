#!/usr/bin/env python3
"""Offline operator utility. Never deploy an unauthenticated token issuance endpoint."""
import base64
import hashlib
import hmac
import json
import os
import re
import sys
import time
import uuid

DEMO_SECRET = "local-demo-only-change-before-deploy-0123456789"
ROLES = ("race", "sender", "recipient", "a", "b")


def issue(user, secret, expires=None):
    if not re.fullmatch(r"[A-Za-z0-9._@-]{1,128}", user):
        raise ValueError("Invalid user ID")
    if len(secret.encode()) < 32:
        raise ValueError("Signing secret must contain at least 32 bytes")
    expires = expires if expires is not None else int(time.time()) + 3600
    payload = base64.urlsafe_b64encode(f"{user}:{expires}".encode()).rstrip(b"=")
    signature = base64.urlsafe_b64encode(hmac.new(secret.encode(), payload, hashlib.sha256).digest()).rstrip(b"=")
    return (payload + b"." + signature).decode()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: WALLET_AUTH_SECRET=... python3 scripts/issue_token.py USER_ID|--bundle")
    secret = os.environ.get("WALLET_AUTH_SECRET")
    if not secret:
        sys.exit("Set WALLET_AUTH_SECRET locally. Never share the signing secret with reviewers.")
    if sys.argv[1] == "--bundle":
        prefix = str(uuid.uuid4())
        # Fresh identities for each review run; a bundle is valid for 24 hours.
        print(json.dumps({role: issue(f"{prefix}-{role}", secret, int(time.time()) + 86400) for role in ROLES}))
    else:
        print(issue(sys.argv[1], secret))