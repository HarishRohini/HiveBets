#!/usr/bin/env python3
"""Move dead-lettered events back to the queue they came from, after the cause is fixed.

Usage: python3 scripts/replay_dlq.py [--queue realtime.events] [--max 1000] [--dry-run]
       (without --queue: every *.dlq with messages)

Each message is republished through the default exchange straight to its original queue, so
other consumers do not see it twice. Consumers are idempotent on eventId anyway.
Uses the RabbitMQ management API (RABBITMQ_USER / RABBITMQ_PASSWORD from .env). Stdlib only.
"""
import argparse
import base64
import json
import os
import urllib.parse
import urllib.request


def env():
    e = {}
    path = os.path.join(os.path.dirname(__file__), "..", ".env")
    if os.path.exists(path):
        for line in open(path):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.strip().split("=", 1)
                e[k] = v
    e.update(os.environ)
    return e


class Rabbit:
    def __init__(self, url, user, password):
        self.url = url.rstrip("/")
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    def call(self, method, path, body=None):
        req = urllib.request.Request(self.url + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", self.auth)
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw else None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--api", default="http://localhost:15672")
    p.add_argument("--queue", help="original queue name, e.g. realtime.events")
    p.add_argument("--max", type=int, default=1000)
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args()
    e = env()
    r = Rabbit(a.api, e.get("RABBITMQ_USER", "hivebets"), e.get("RABBITMQ_PASSWORD", "hivebets"))
    vhost = urllib.parse.quote("/", safe="")

    queues = [a.queue] if a.queue else [q["name"][:-4] for q in r.call("GET", f"/api/queues/{vhost}")
                                        if q["name"].endswith(".dlq") and q.get("messages", 0) > 0]
    if not queues:
        print("no dead-lettered messages")
        return
    for q in queues:
        dlq = q + ".dlq"
        if a.dry_run:
            msgs = r.call("POST", f"/api/queues/{vhost}/{urllib.parse.quote(dlq, safe='')}/get",
                          {"count": a.max, "ackmode": "ack_requeue_true", "encoding": "auto"})
            for m in msgs:
                death = (m["properties"].get("headers") or {}).get("x-death", [{}])[0]
                print(f"{dlq}: {m['properties'].get('type')} reason={death.get('reason')} count={death.get('count')}")
            print(f"{len(msgs)} message(s) would be replayed from {dlq}")
            continue
        msgs = r.call("POST", f"/api/queues/{vhost}/{urllib.parse.quote(dlq, safe='')}/get",
                      {"count": a.max, "ackmode": "ack_requeue_false", "encoding": "base64"})
        moved = 0
        for m in msgs:
            props = {k: v for k, v in m["properties"].items() if k in ("content_type", "type", "delivery_mode", "message_id")}
            props["headers"] = {k: v for k, v in (m["properties"].get("headers") or {}).items() if not k.startswith("x-")}
            res = r.call("POST", f"/api/exchanges/{vhost}/amq.default/publish",
                         {"properties": props, "routing_key": q, "payload": m["payload"], "payload_encoding": "base64"})
            moved += 1 if res and res.get("routed") else 0
        print(f"replayed {moved}/{len(msgs)} message(s) from {dlq} to {q}")


if __name__ == "__main__":
    main()
