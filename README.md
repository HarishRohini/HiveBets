# HiveBets

A live prediction market. Each market is a yes/no question; YES shares trade at 1–99¢ and pay $1.00 if the answer is yes, so the price reads as a probability. Humans trade in the browser today; an AI trader swarm joins in Phase 4. See [docs/PLAN.md](docs/PLAN.md) for the full plan and [docs/API.md](docs/API.md) for the API and event contracts.

## Run it

Needs Docker (with Compose) and about 6 GB of free memory for the containers.

```bash
cp .env.example .env    # then change the passwords
make up                 # builds every image and starts the stack
```

| What | Where |
|---|---|
| Web app | http://localhost:3000 (sign in as `ADMIN_USERNAME` / `ADMIN_PASSWORD` from `.env`) |
| API gateway | http://localhost:8080 |
| Grafana (metrics, logs, traces; dashboard "HiveBets overview") | http://localhost:3001 (admin / `ADMIN_PASSWORD`) |
| Dagster (lake pipeline) | http://localhost:3002 |
| RabbitMQ console | http://localhost:15672 (`RABBITMQ_USER` / `RABBITMQ_PASSWORD`) |
| RustFS console (data lake, S3-compatible) | http://localhost:9001 (`S3_ACCESS_KEY` / `S3_SECRET_KEY`) |

```bash
make smoke    # two test accounts trade against each other through the gateway
make test     # unit and contract tests: services (JUnit), web (Vitest), lake models (pytest)
make e2e      # Playwright end-to-end suite against the running stack
make load     # k6 load test with the SLOs as thresholds (RATE=40 DURATION=2m)
make drill-rabbitmq   # failure drills; see docs/RUNBOOK.md
make down     # stop (keeps data); `make nuke` also deletes all data
```

Alerts, SLOs, drills and what to do when something breaks: [docs/RUNBOOK.md](docs/RUNBOOK.md). Event payloads are pinned by JSON Schemas in `services/common/src/main/resources/events/schemas`; producers and consumers are both tested against them. CI runs in GitHub Actions (`.github/workflows/ci.yml`).

Check the trading ledger at any time:

```bash
docker compose exec -T postgres psql -U hivebets -d hivebets < scripts/check_ledger.sql
```

## What is inside

```
web/                Next.js 16 app (TypeScript, Tailwind, TanStack Query, lightweight-charts)
services/           Gradle multi-module, Java 21, Spring Boot 4.2
  gateway/          Spring Cloud Gateway: JWT check, roles, rate limits, CORS, WebSocket, /api/events
  identity-service/ accounts, Argon2 hashes, RS256 session tokens, JWKS
  trading-service/  markets, order book and matching, double-entry ledger, resolution, outbox
  news-service/     headlines in MongoDB; RSS ingestion and embedding match (Phase 2)
  realtime-service/ events → Redis live state → WebSocket push
  lake-sink/        every event → Parquet in the lake (bronze)
  analytics-service/ admin-only API over the gold tables
  common/           event envelope, outbox, RabbitMQ topology, shared web helpers
data/               dbt (DuckDB) silver/gold models, Dagster schedule, publish to Postgres
ops/                Postgres init, OpenTelemetry collector config, Grafana dashboards
scripts/            smoke test, ledger checks
```

How an order flows: browser → gateway (verifies the session cookie) → trading-service, where the market's single writer thread matches it and one Postgres transaction writes the order, trades, ledger rows, positions and outbox rows → the outbox relay publishes to RabbitMQ → realtime-service updates Redis and pushes to browsers over WebSocket, and lake-sink writes Parquet to the lake → every 5 minutes Dagster runs dbt and publishes the gold tables that the Analytics screen reads. One trace in Grafana Tempo follows the whole path.

## Real-world news (Phase 2)

news-service polls the RSS feeds in `RSS_FEEDS`, embeds each headline with EmbeddingGemma served by llama.cpp on the host, and publishes the ones that match an open real-world market. Without the embedding server, headlines are still stored (status `NO_EMBEDDINGS`) but none are published. To run it on the Mac (Metal GPU):

```bash
brew install llama.cpp
make embeddings     # llama-server -hf ggml-org/embeddinggemma-300M-GGUF --embeddings --host 0.0.0.0 --port 8081 --metrics
```

Server flags change between llama.cpp releases; if `make embeddings` fails, check `llama-server --help`. On the Admin screen, "Real news ingested" shows each headline's similarity score and what happened to it; tune `NEWS_MATCH_THRESHOLD` in `.env` from those scores. An admin can also trigger a poll at once with `POST /api/admin/news/poll`.

The curated real-world markets are in `services/trading-service/src/main/resources/seed/real-markets.json`; an admin resolves them by hand on the Admin screen.

## Notes

- MinIO no longer publishes images on Docker Hub, so the lake uses [RustFS](https://rustfs.com), an S3-compatible store with a web console. Nothing else changes: lake-sink and DuckDB talk plain S3.
- Play money only. Every account starts with $1,000.
