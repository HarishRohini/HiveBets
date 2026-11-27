#!/usr/bin/env python3
"""Repeatable activity through the gateway: two test accounts trade against each other.

Usage: python3 scripts/smoke.py [--rounds N] [--resolve] [--api http://localhost:8080]
Reads ADMIN_USERNAME / ADMIN_PASSWORD from the environment or .env. Standard library only.
"""
import argparse
import http.cookiejar
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid


def load_env():
    env = {}
    path = os.path.join(os.path.dirname(__file__), "..", ".env")
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k] = v
    env.update(os.environ)
    return env


class Client:
    def __init__(self, api):
        self.api = api.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, headers=None, ok=(200, 201, 202, 204)):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.api + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with self.opener.open(req, timeout=20) as res:
                raw = res.read()
                return res.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read()
            payload = json.loads(raw) if raw else None
            if e.code in ok:
                return e.code, payload
            return e.code, payload

    def must(self, method, path, body=None, headers=None):
        status, payload = self.call(method, path, body, headers)
        if status >= 300:
            sys.exit(f"FAILED {method} {path}: {status} {payload}")
        return payload

    def sign_in(self, username, password, invite=None):
        status, _ = self.call("POST", "/api/auth/login", {"username": username, "password": password})
        if status == 200:
            return
        status, payload = self.call("POST", "/api/auth/signup", {"username": username, "password": password, "inviteCode": invite})
        if status not in (200, 201):
            sys.exit(f"could not sign in or sign up {username}: {status} {payload}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--api", default=os.environ.get("API", "http://localhost:8080"))
    p.add_argument("--rounds", type=int, default=10)
    p.add_argument("--resolve", action="store_true", help="resolve the market at the end")
    p.add_argument("--market", help="trade in this market instead of creating one")
    args = p.parse_args()
    env = load_env()

    admin, a, b = Client(args.api), Client(args.api), Client(args.api)
    admin.sign_in(env.get("ADMIN_USERNAME", "admin"), env.get("ADMIN_PASSWORD", "change-me-admin"))
    a.sign_in("smoke-alpha", "smoke-alpha-pass")
    b.sign_in("smoke-bravo", "smoke-bravo-pass")

    if args.market:
        market = admin.must("GET", f"/api/markets/{args.market}")
    else:
        market = admin.must("POST", "/api/markets", {
            "question": f"Smoke test {time.strftime('%H:%M:%S')}: will the coin land heads?",
            "yesStatement": "The coin landed heads.", "noStatement": "The coin landed tails.",
            "kind": "SIMULATED", "durationMinutes": 10})
        print(f"created market {market['id']}: {market['question']}")
        st, _ = admin.call("POST", "/api/news", {"marketId": market["id"], "text": "Smoke test: the coin is in the air."})
        if st >= 300:
            print(f"warning: posting a headline failed ({st}); is news-service up?")
    mid = market["id"]

    price = 50
    trades = 0
    for i in range(args.rounds):
        price = max(5, min(95, price + random.randint(-4, 4)))
        n = random.randint(1, 20)
        # alpha bids YES; bravo buys NO at the complement, which crosses.
        a.must("POST", "/api/orders", {"marketId": mid, "side": "YES", "action": "BUY", "price": price, "shares": n},
               {"Idempotency-Key": str(uuid.uuid4())})
        o = b.must("POST", "/api/orders", {"marketId": mid, "side": "NO", "action": "BUY", "price": 100 - price, "shares": n},
                   {"Idempotency-Key": str(uuid.uuid4())})
        if o["filledShares"] > 0:
            trades += 1
        time.sleep(0.2)

    # An idempotent replay must return the same order.
    key = str(uuid.uuid4())
    first = a.must("POST", "/api/orders", {"marketId": mid, "side": "YES", "action": "BUY", "price": 3, "shares": 1}, {"Idempotency-Key": key})
    again = a.must("POST", "/api/orders", {"marketId": mid, "side": "YES", "action": "BUY", "price": 3, "shares": 1}, {"Idempotency-Key": key})
    assert first["id"] == again["id"], "idempotency key did not dedupe"
    a.must("DELETE", f"/api/orders/{first['id']}")

    status, _ = a.call("GET", "/api/analytics/summary")
    assert status == 403, f"trader should be refused analytics, got {status}"

    pa, pb = a.must("GET", "/api/portfolio"), b.must("GET", "/api/portfolio")
    print(f"{trades}/{args.rounds} rounds traded; last price {price}¢")
    print(f"alpha equity ${pa['wallet']['equityCents'] / 100:.2f}, bravo equity ${pb['wallet']['equityCents'] / 100:.2f}")

    if args.resolve:
        outcome = random.choice(["YES", "NO"])
        admin.must("POST", f"/api/markets/{mid}/resolve", {"outcome": outcome})
        pa, pb = a.must("GET", "/api/portfolio"), b.must("GET", "/api/portfolio")
        total = pa["wallet"]["equityCents"] + pb["wallet"]["equityCents"]
        print(f"resolved {outcome}; alpha ${pa['wallet']['availableCents'] / 100:.2f}, bravo ${pb['wallet']['availableCents'] / 100:.2f}")
        print("money conserved" if total % 100 == 0 else "WARNING: totals look off")
    print("smoke OK")


if __name__ == "__main__":
    main()
