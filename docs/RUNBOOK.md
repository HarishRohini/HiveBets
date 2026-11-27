# HiveBets runbook

What each alert means, what to check, and how to recover. Dashboards: Grafana (http://localhost:3001) → HiveBets → "HiveBets overview" and "HiveBets SLOs".

## Service level objectives

| SLO | Target | Measured by |
|---|---|---|
| Order availability | 99.5% of order requests answered without a server error, measured at the gateway (so requests that never reached trading-service count) | `spring_cloud_gateway_requests_seconds_count{routeId="trading",httpMethod="POST"}` |
| Order latency | 95% of orders matched and committed within 250 ms | `hivebets_order_latency_seconds` histogram |
| Event freshness | outbox drained, no dead letters, consumer backlog under 1,000 | `hivebets_outbox_pending`, RabbitMQ queue metrics |
| Analytics freshness | gold tables under 15 minutes old | `hivebets_analytics_data_age_seconds` |

The k6 load test (`make load`) uses the same targets as thresholds.

## Alerts

### order-errors
**Order API error rate above 1%.** Orders are failing with server errors.
1. Grafana → Explore → Loki: `{service_name="trading-service"} | json | log_level="ERROR"`; follow a trace ID into Tempo.
2. `docker compose ps`: is trading-service or Postgres unhealthy or restarting?
3. Lock conflicts are retried three times; repeated `lock conflict` warnings mean contention on a hot wallet.
4. After recovery, run `scripts/drills/check_consistency.sh`.

### order-latency
**Order latency p95 above 300 ms.** Each market runs on one writer thread; a hot market queues orders behind each other.
1. "Order latency" and "Requests / s by service" panels: is it load (k6, many agents) or a slow dependency?
2. Postgres connections panel: saturation of the pool (30 per trading-service instance).
3. In Tempo, pick a slow `POST /api/orders` trace and look for the slow SQL span.

### dead-letters
**Events in a dead-letter queue.** A consumer failed an event four times (with backoff) or could not read it at all.
1. Look at them without moving them: `python3 scripts/replay_dlq.py --dry-run`.
2. Find the cause in the consumer's logs (realtime-service: `{service_name="realtime-service"}`).
3. Fix it, then `make replay-dlq`. Consumers deduplicate on event id, so replaying is safe.

### outbox-backlog
**Outbox not draining.** trading-service cannot publish to RabbitMQ. Orders still work (the events wait in `trading.outbox`), but live views and the lake lag.
1. `docker compose ps rabbitmq`; `docker compose logs rabbitmq`.
2. When the broker is back, the relay retries with backoff (up to 10 s) and drains on its own; watch "Outbox waiting to publish" fall to 0.
3. Drill: `scripts/drills/rabbitmq_outage.sh`.

### queue-backlog
**Consumer falling behind.** More than 5,000 events waiting in `realtime.events` or `lake-sink.events`.
1. Is the consumer running and healthy? Redis (for realtime) or RustFS (for lake-sink) down?
2. lake-sink keeps a failed batch and retries every 30 s; once RustFS is back it catches up.
3. realtime-service can be scaled out: it fans out over Redis pub/sub, so several instances share the queue.

### analytics-stale
**Analytics data is stale.** The Dagster schedule has not published the gold tables for 20 minutes.
1. Dagster UI (http://localhost:3002) → Runs: look at the failed step.
2. Run once by hand: `make pipeline-once`.
3. Missing bronze files usually mean lake-sink is stuck (see queue-backlog).

## Drills

Run with the stack up. Each ends with `scripts/drills/check_consistency.sh`, which checks the ledger, the outbox, the queues and that realtime-service's per-market volume equals trading-service's.

| Drill | What happens | Expected |
|---|---|---|
| `scripts/drills/rabbitmq_outage.sh` | RabbitMQ stopped for 60 s under load | orders keep succeeding; outbox grows then drains; consistent afterwards. Measured: 0 of 5,961 requests failed, outbox peaked near 10,000 events and drained within a minute |
| `scripts/drills/trading_restart.sh` | trading-service restarted under load | the gateway retries requests that could not connect, so callers wait instead of failing; books rebuilt from Postgres; consistent afterwards. Measured: 11 s restart, 1 of 4,353 requests failed (7% before the connect retry), order p95 rose to about 0.5 s during the restart |

## Load test

```bash
make load                      # 40 orders/s for 2 minutes against one market
RATE=120 DURATION=60s make load
```

On a 4-core cloud sandbox with the whole stack on one machine, one market handled about 120 orders/s with order p95 around 220 ms and no errors; at 30 orders/s p95 was about 20 ms.
