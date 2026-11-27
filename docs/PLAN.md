# HiveBets — a prediction market traded by an AI swarm

## Context

Goal: a showcase end-to-end product with every layer of a real system (UI, API gateway, backend services, SQL + NoSQL, cache, queue, data lake pipeline, monitoring, logging), on a domain more interesting than e-commerce. The project folder is empty, so this is greenfield.

Product: **HiveBets**, a live prediction market. Markets are yes/no questions about a synthetic world ("Will the Mars colony vote pass by Friday?"). A swarm of AI traders reads generated news and trades on it through the same web app a human uses. Humans can log in and trade against them.

Decisions already made by the user: Next.js/TypeScript UI, Java Spring Boot backend, SQL + NoSQL, Redis, RabbitMQ, Docker Compose first and Kubernetes later, software stack working end to end before any agent work. The data pipeline choice was left to me (see below).

Machine: M4, 10 cores, 32 GB, Docker 28, Java 21, Node 25, pnpm, Python 3.14, Homebrew. llama.cpp is not installed yet (`brew install llama.cpp`).

## What is traded, and where history comes from

- No companies or tickers. Each market is a yes/no question with a close date. YES shares trade at 1–99 cents and pay 100 if the answer is yes, so price ≈ probability.
- No external or imported price history. A market starts empty; all history is generated as the system runs.
- Source of truth for orders, trades, ledger and positions is Postgres. Redis holds recent candles and the live book. The lake holds every event for full history and replay. Trader returns (profit and loss, calibration) are computed by the pipeline into gold marts.

## Resolution and money

- Simulated markets live 5–15 minutes; the world engine keeps about five open, opening a new one whenever one resolves.
- Each has a hidden probability that random-walks; headlines are noisy hints that get more reliable near close. At close the engine draws the outcome at random with the final hidden probability and calls the resolve endpoint. The hidden probability is logged to the lake (never exposed to traders) as the "correct price" for analytics.
- Play money only, no bank-service (user chose the simple option). Each account starts with a $1,000 bankroll in its trading wallet. A trade puts 100 cents per share in escrow (buyer pays the price, seller locks the rest); at resolution the winning side receives it. Zero-sum between traders; the ledger always balances.
- Resolution and payout are therefore part of Phase 1, since minute-long markets are the core loop.
- An agent that reaches zero balance is retired and replaced by a new agent with a fresh persona; persona survival becomes an analytics mart.

## How the swarm works

- A **world engine** holds a hidden true probability per market that drifts over time. It emits headlines that are noisy hints about that truth, written by a Gemma 3 1B model served by llama.cpp (template fallback if the model is slow), and resolves each market at close.
- Each **agent** has a persona (text embedded once with EmbeddingGemma), a bankroll, a risk appetite and a belief per market.
- Each market stores three embeddings: the question, a "yes happened" statement and a "no happened" statement.
- Each headline is embedded **once**. Per agent, the decision is then only vector maths: similarity to the market question says which market it concerns; similarity to the yes statement minus the no statement gives the direction; similarity to the persona says how strongly that agent reacts. The agent updates its belief and places a limit order when its belief differs from the market price by more than its threshold.
- This reading of headlines does not depend on who wrote them, so the same agents handle synthetic and real news.
- The model is called per headline, not per agent, so the agents' thinking is cheap.
- Models are served by **llama.cpp** (`llama-server`), running natively on the Mac for Metal GPU access (Docker on macOS has none). See "Local models" below.
- Swarm and world engine are Python (asyncio + Playwright + numpy). They are clients of the system, not part of the backend. The world engine posts news through the API; traders do not use the API directly.

### Agents trade through the real UI (chosen by the user)

- Every trader agent drives the web app in a headless browser: one shared Chromium, one Playwright browser context per agent (own cookies and login). It logs in, opens a market, reads headlines and prices from the page, fills the order ticket and clicks Buy/Sell.
- **Session-based**: a population of about 500 agents, each visiting for a few minutes and then leaving; a scheduler keeps about 50 online (configurable, `SWARM_CONCURRENCY`). Expect roughly 5–20 orders/second.
- The swarm runs natively on the Mac next to llama.cpp, not in Docker, to avoid a second Chromium memory overhead inside the Docker VM.
- Pages expose stable `data-testid` attributes; the swarm uses only those selectors, wrapped in one page-object module so a UI change is fixed in one place.
- The web app sends **clickstream and web-vitals events** (page view, click, order submitted, session start/end, LCP/INP) to `POST /events` on the gateway. They go to RabbitMQ and the lake like every other event, giving sessions, funnels and retention marts.
- High-volume backend load testing is separate: k6 against the API in Phase 3.
- Agents are **scripted first**. From the start of Phase 4 the swarm logs every UI step as a trajectory record (goal, simplified page snapshot from the accessibility tree, action taken, result) to the lake; this is the training set for the fine-tuned UI model in Phase 6.

## Local models (llama.cpp, chosen by the user over Ollama)

- Two `llama-server` processes on the Mac, each exposing an OpenAI-compatible HTTP API:
  - **Embeddings**: EmbeddingGemma GGUF, started in embedding mode, port 8081 (`/v1/embeddings`). Used by the swarm and by news-service (RSS matching, via `host.docker.internal`).
  - **Generation**: Gemma 3 1B instruct GGUF, port 8082 (`/v1/chat/completions`). Used by the world engine; in Phase 6 the fine-tuned UI model is served the same way.
- What the finer control buys us:
  - **Constrained output**: a JSON schema / GBNF grammar per request, so a headline always comes back as valid `{headline, tone}` and (Phase 6) a UI action is always one of the allowed actions.
  - Per-request sampling settings and seeds, so a simulation run can be replayed.
  - Parallel slots and batching sized to the swarm; LoRA adapters loaded at start-up.
  - A Prometheus metrics endpoint, scraped into Grafana (tokens/s, queue depth, slot use).
- EmbeddingGemma expects a task prefix on its input (different for queries and documents); confirm the exact prefixes and server flags against the current llama.cpp docs when scaffolding.
- `swarm/llm.py` is the only module that talks to the model servers, so the backend can be swapped again without touching agents.

## World engine in detail

A single Python process with four jobs, run on a one-second loop:

1. **Open markets.** Picks a scenario from a seed file of templates (question, a "yes happened" statement, a "no happened" statement, a pool of story beats), chooses a starting hidden probability and a lifetime of 5-15 minutes, and creates the market through the gateway.
2. **Move the truth.** Each tick the hidden probability takes a small random step (in log-odds, so it stays between 0 and 1).
3. **Write news.** Every few seconds it picks a market and decides the slant: mostly in the direction the truth just moved, sometimes misleading, less often misleading as close approaches. It asks llama.cpp for a headline with that slant and posts it to `POST /news` with the market id.
4. **Resolve.** At close it draws yes/no at random using the final hidden probability and calls `POST /markets/{id}/resolve`.

- **Where the truth lives**: only in the world engine's own state, a local SQLite file `swarm/state/world.db` (so it survives a restart). It is never written to the trading or news databases and no trader-facing API returns it.
- The engine also sends `truth.tick` events to an engine-only endpoint; these go to RabbitMQ and are consumed only by lake-sink, for the admin "price vs true odds" chart.
- **News ingestion path**: engine → gateway (service account, `ENGINE` role) → news-service validates and stores in MongoDB → publishes `news.published` → realtime-service pushes it to open browsers, lake-sink archives it. Real headlines enter at news-service through its scheduled RSS poller and follow the same path from there.
- **Who wins**: there is no single winner. trading-service stops accepting orders at the close time on its own clock (even if the engine is late). On the resolve call, in one transaction, it cancels unfilled orders and releases their reserved cash, then pays $1.00 per share to everyone holding the side that happened and nothing to the other side, writes the ledger rows and emits `market.resolved`. A trader's profit is payout minus what they paid; traders who sold earlier at a better price have already banked theirs.

## What Redis does

Redis holds fast, disposable copies; nothing in it is the source of truth, and it can be rebuilt from Postgres and replayed events.

- **Live market state** written by realtime-service: current order book snapshot, last price, rolling price candles, volume and trader counts. A page load reads these instead of querying Postgres.
- **Leaderboard**: a sorted set of trader profit, updated on each trade and payout.
- **Fan-out**: pub/sub channel per market so that several realtime-service instances can each push to their own WebSocket clients.
- **Gateway rate limiting**: token-bucket counters per user and per login username.
- **Short-lived caches**: market list, latest headlines per market.

## How an agent decides and bets

Agent traits (fixed at creation from its persona group): reactivity, how much it trusts the crowd, minimum edge before betting, risk fraction, patience, contrarian or not. State: bankroll, positions, a belief per market.

Deciding, each time the agent looks at a market page:
1. **Read the page**: YES price, best bid/ask, time left, and the headlines listed (all via `data-testid` selectors).
2. **Take in new headlines.** For each headline it has not seen, fetch the headline's vector from the swarm's shared cache (embedded once for the whole swarm). Then:
   - direction = similarity to the market's "yes happened" statement minus similarity to "no happened";
   - weight = similarity between the headline and the agent's persona (how much this agent cares about or trusts this kind of news);
   - shift the belief, in log-odds, by reactivity x weight x direction plus a little personal noise. Contrarians apply part of the shift in the opposite direction.
3. **Lean toward the crowd**: pull the belief toward the current price by the agent's crowd-trust amount.
4. **Find the edge**: belief minus the YES ask price (or the mirror for NO). If the edge is below the agent's minimum, do nothing.
5. **Size it**: a fraction of the Kelly bet (edge divided by potential loss, times the risk fraction), capped at 10% of bankroll.
6. **Price it**: impatient agents accept the best available price; patient ones post a limit order inside the spread and wait.
7. **Manage positions**: sell when the price has moved past the agent's belief (take profit) or the belief has flipped (cut loss).

Betting, as browser steps in one session: sign in → markets list → open the market with the largest gap between belief and price (ties broken by persona interest) → read → pick side, shares and limit price in the order ticket → click Buy → wait for the confirmation → pause a human-like few seconds → repeat for a few minutes → leave. The order ticket therefore needs a limit-price field (the mockup only has a share stepper).

## Can agents talk to each other?

- **No private channel.** Agents share no memory and send each other no messages; a back channel would amount to collusion and would bypass the product.
- **They do influence each other through the market**: every agent sees the price, order book and recent trades that other agents created, and leans toward the crowd by its own trust setting. That alone produces herding, overreaction and corrections.
- **Public comments (Phase 4)**: an agent can post a one-line reason for its trade on the market page (stored by news-service in MongoDB, written by llama.cpp). Other agents read comments like weak headlines, weighted by persona similarity to the author. This is communication through the product, visible to humans too.

## Accounts, roles and how agents log in

- **Roles** in the JWT: `ADMIN` and `TRADER`. Account type: `HUMAN` or `AGENT` (drives the AI/Human label).
- **Analytics is admin-only** (user's decision). The gateway rejects `/analytics/**` and admin endpoints (create/resolve market) without the `ADMIN` role; the web app hides the Analytics link and redirects non-admins. Grafana, Dagster, RabbitMQ and MinIO consoles are operator tools outside the app. The world engine uses a service account with a separate `ENGINE` role for posting news and resolving simulated markets.
- **Admin account** is seeded at first boot from `ADMIN_USERNAME` / `ADMIN_PASSWORD` in `.env` (gitignored).
- **Login screen**: username + password, with a Create account tab that has an optional invite code. identity-service stores Argon2 password hashes and sets the session as an httpOnly cookie.
- **Agent credentials**:
  - When the swarm creates an agent it generates a username (`<persona>-<nnnn>`) and a random password and saves them, with the persona, in a local SQLite file `swarm/state/agents.db` (gitignored, never sent anywhere).
  - First session: the agent signs up through the real Create account form, entering `AGENT_INVITE_CODE` (shared secret in `.env`, known to identity-service) so the account is typed `AGENT`.
  - Every later session: the scheduler hands the agent's record to its Playwright worker, which types the username and password into the login form. Each agent has its own browser context, so its session cookie is isolated.
  - Passwords never reach logs, clickstream or the lake. Trajectory records store `<PASSWORD>` in place of the typed value; in Phase 6 the fine-tuned model emits that placeholder and the harness substitutes the real value, so the model never sees a password.
- Login rate limiting is per username, not per IP, since all agents share one address.

## News sources (hybrid, chosen by the user)

Every headline carries `source: synthetic | real` and both kinds flow through news-service, MongoDB and `news.published` identically.

- **Synthetic**: the world engine above. Fast markets that resolve in minutes, with a known ground truth for scoring.
- **Real**: a scheduled RSS ingester inside news-service (Rome library) pulls public feeds, deduplicates by URL/GUID and near-duplicate embedding, and stores headline, link and publisher. Only headlines similar enough to an open real-world market are published to traders.
- **Real-world markets** are a small curated list in a seed file (question, yes/no statements, close date), resolved manually by an admin in the UI. Auto-proposing markets from headlines with an LLM is left out: the 1B Gemma model is too weak to write good, resolvable questions.
- The UI labels markets and headlines as Simulated or Real, and analytics keep the two apart (ground-truth metrics apply to simulated markets only).

## Architecture

```
Next.js web ──HTTP/WS──> gateway (Spring Cloud Gateway)
                           ├─> identity-service ── Postgres
                           ├─> trading-service ─── Postgres  (markets, orders, matching, ledger, outbox)
                           ├─> news-service ────── MongoDB   (headlines, comments, agent rationales)
                           └─> realtime-service ── Redis     (book snapshots, last price, candles, leaderboard)
                                     ▲
trading/news ──outbox──> RabbitMQ topic exchange `hivebets.events` ──> realtime-service
                                     └──> lake-sink ──> MinIO (bronze Parquet) ──> dbt + DuckDB ──> gold marts
swarm agents (Python + Playwright, headless browsers) ──> Next.js web ──> gateway
world engine (Python) ──> gateway (posts news, resolves markets) ; llama.cpp on host
web clickstream ──> gateway /events ──> RabbitMQ ──> lake
All services ──OTLP──> Grafana LGTM (Prometheus metrics, Loki logs, Tempo traces, Grafana dashboards)
```

| Piece | Choice | Notes |
|---|---|---|
| UI | Next.js (App Router), TypeScript, Tailwind, TanStack Query, lightweight-charts | Market list, market page (order book, price chart, order ticket, news ticker), portfolio, leaderboard, analytics |
| Gateway | Spring Cloud Gateway | JWT validation (JWKS from identity), Redis token-bucket rate limiting, CORS, request ID, WebSocket proxying |
| Services | Java 21, Spring Boot (current stable, pinned at scaffold time), Gradle multi-module | Built in multi-stage Docker images, so no local Gradle install is needed |
| SQL | Postgres | One instance, a schema per service; Flyway migrations |
| NoSQL | MongoDB | News, comments and agent rationales are document-shaped and write-heavy |
| Cache | Redis | Order book snapshots, last prices, candles, leaderboard sorted sets, rate limits, WS fan-out via pub/sub |
| Queue | RabbitMQ | Topic exchange, one durable queue per consumer, dead-letter queues, publisher confirms |
| Data lake | MinIO + Parquet, dbt-duckdb, Dagster | See "Data pipeline" |
| Observability | OpenTelemetry Java agent + Micrometer, `grafana/otel-lgtm` | JSON logs carrying trace IDs; exporters for Postgres, Redis, RabbitMQ |

### Trading core (the interesting part)

- Binary contracts: a YES share pays 100 cents if the market resolves yes. Prices are 1–99 cents. Buying NO at `p` is modelled as selling YES at `100 - p` with collateral locked.
- Central limit order book, price-time priority, kept in memory per market. Each market has a single writer thread, so matching needs no locks.
- One order command = one Postgres transaction that writes the order, trades, double-entry ledger rows (funds reserved at placement) and outbox rows. Wallet lives inside trading-service on purpose, to avoid a distributed transaction on the hot path.
- An outbox relay publishes `order.accepted`, `trade.executed`, `book.changed`, `market.resolved` to RabbitMQ. Books are rebuilt from open orders on startup.
- Client-supplied idempotency keys on order placement.
- Invariant tested with property tests: money and shares are conserved across any sequence of orders.

### Data pipeline (my recommendation)

1. **lake-sink** (small Spring Boot consumer) binds its own queue to every event, batches, and writes Parquet to MinIO at `bronze/<event_type>/dt=YYYY-MM-DD/hr=HH/`.
2. **dbt with DuckDB** reads bronze straight from MinIO and builds silver (cleaned, deduplicated) and gold marts.
3. **Dagster** schedules and shows lineage for the runs.
4. Gold marts (small, chart-shaped tables, one per question) are published into a Postgres `analytics` schema. Charts never query the lake directly.
5. A read-only **analytics-service** (Spring Boot) exposes the marts as JSON through the gateway (e.g. `GET /analytics/markets/{id}/efficiency`); a custom Next.js analytics page renders them, with a "data as of" time. Grafana reads the same tables. No separate BI tool (user's choice).

Live charts (price, order book, leaderboard) do not come from the lake; they come from Redis over WebSocket. Dagster runs incrementally every ~5 minutes, so analytical charts lag live by one run. The world engine emits `truth.tick` events to the lake so marts can compare price with the hidden probability.

Marts worth having: sessions, funnels (visit → view market → order) and web vitals from clickstream; trader calibration (Brier score), market efficiency (price against the hidden truth), news-to-price reaction time, profit by persona cohort, volume and liquidity per market.

Why this and not Spark/Kafka/ClickHouse: it shows the same lakehouse shape (bronze/silver/gold, columnar files on object storage, orchestrated SQL transforms) while running comfortably on one laptop.

## Repo layout

```
virtual-product/
  docker-compose.yml  Makefile  .env.example  README.md
  services/           # Gradle multi-module
    gateway/ identity-service/ trading-service/ news-service/ realtime-service/ lake-sink/ analytics-service/
    common/           # event schemas, outbox, logging/tracing config
  web/                # Next.js
  swarm/              # Python: world engine, agents, personas
  data/               # dbt project, Dagster definitions
  ops/                # Grafana dashboards + alerts, otel config, RabbitMQ definitions, init SQL
  k8s/                # later phase
```

## Phases

Order chosen by the user: build the software stack and make it work end to end with human users first; agents come after it is hardened.

Until Phase 4 there is no world engine and no swarm. The admin creates markets, posts headlines and resolves markets by hand from admin screens, and a small `make smoke` script (two test accounts placing crossing orders through the API) provides repeatable activity.

### Phase 1 — software stack, working end to end

**1a. Foundation.**
- Repo scaffold: Gradle multi-module `services/`, `web/`, `data/`, `ops/`; Makefile; `.env.example`.
- Docker Compose with Postgres, MongoDB, Redis, RabbitMQ, MinIO and the Grafana LGTM stack, all with health checks.
- `services/common`: event envelope and schemas, transactional outbox, JSON logging with trace IDs, OpenTelemetry wiring (so every service is traced from its first commit).
- Done when: `make up` brings everything up healthy and a hello-world service shows a trace in Grafana.

**1b. Identity and gateway.**
- identity-service: sign-up (with optional invite code), sign-in, roles (`ADMIN`, `TRADER`, `ENGINE`), account type, seeded admin, Argon2 hashes.
- gateway: routing, token and role checks, per-user and per-username rate limiting in Redis, CORS, request IDs.
- web: app shell and navigation, Sign in / Create account screen, admin-only link handling.
- Done when: you can sign up, sign in, and a trader is refused on an admin route.

**1c. Trading core.**
- trading-service: markets, limit orders (place, cancel), in-memory order book per market with a single writer, double-entry ledger with cash reserved at placement, idempotency keys, outbox to RabbitMQ, close-time cutoff, resolve and payout.
- Unit tests for the matching engine plus the conservation property test (money and shares never appear or vanish).
- web: markets list, market page with order ticket (side, shares, limit price), admin screens to create and resolve a market. Pages refresh by polling for now.
- Done when: two browser accounts can trade against each other and a resolved market pays the right side.

**1d. Real-time.**
- realtime-service: consumes events, maintains Redis state (book snapshot, last price, candles, volume, leaderboard), pushes over WebSocket through the gateway.
- web: live order book, price chart and recent trades replace polling; Portfolio and Leaderboard screens.
- Done when: an order placed in one browser moves the book and chart in another without a refresh.

**1e. News.**
- news-service with MongoDB: store and list headlines per market, `source` field (`synthetic | real | manual`), publishes `news.published`.
- web: news list on the market page with a chart marker per headline; admin form to post a headline.
- Done when: a headline posted by the admin appears live on the market page.

**1f. Data pipeline and analytics.**
- Clickstream and page-speed events from the web app to `POST /events`.
- lake-sink writing Parquet to MinIO; dbt + DuckDB models (clean, then gold); Dagster schedule every 5 minutes; gold tables published to the Postgres `analytics` schema.
- analytics-service and the admin-only Analytics screen. First gold tables are the ones that need no agents: volume and liquidity per market, session funnel, page speed, profit per trader.
- Done when: activity from the smoke script shows up on the Analytics screen within one pipeline run.

**1g. Observability and end-to-end check.**
- Grafana dashboards: order rate and match latency, queue depth, WebSocket connections, error rate, pipeline freshness; exporters for Postgres, Redis and RabbitMQ.
- Full walkthrough of the verification list below.

### Phase 2 — real news

- RSS poller in news-service, deduplication by URL/GUID.
- llama.cpp embedding server introduced here (first use of a model): match headlines to open real-world markets and drop near-duplicates.
- Curated real-world markets from a seed file; Simulated / Real labels in the UI; analytics keep the two apart.

### Phase 3 — harden

- Standard unit tests (JUnit for services, Vitest for the web app) across all services; no Testcontainers for now (user's decision).
- Event schema checks as unit tests, so a producer cannot silently break a consumer.
- Dead-letter queues, retries with backoff, consumer idempotency.
- k6 load test against the API; failure drills (stop RabbitMQ, restart trading-service mid-run) with recovery shown on the dashboards.
- Grafana alert rules and an SLO dashboard; GitHub Actions CI running build and tests.

### Phase 4 — agents

- llama.cpp generation server alongside the embedding server.
- World engine: scenario seed file, hidden probability, headline writing, resolution, `truth.tick` events.
- Swarm: personas and traits, decision logic, Playwright sessions through the real UI (sign-up with invite code, sign-in, trade), session scheduler, shared headline-embedding cache.
- Trajectory logging of UI steps (passwords redacted) for Phase 6.
- Agent retirement and replacement; public trade comments.
- Agent-dependent gold tables and charts: price vs true odds, calibration, profit by persona group, reaction time to news.
- Scale up to 500 agents with about 50 online.

### Phase 5 — Kubernetes

kind cluster, Helm charts, ingress, autoscaling, trading-service sharded by market.

### Phase 6 — fine-tuned UI agent

The embedding "brain" still decides what to trade; a small generative model becomes the "hands".
- Build a training set from logged trajectories in the lake (dbt model → JSONL of goal + page snapshot → next action).
- Fine-tune a small Gemma (270M or 1B; check the current function-calling variant at build time) with LoRA using MLX on the M4; convert to GGUF and load it in `llama-server` (merged model or `--lora` adapter).
- Evaluate on held-out tasks (login, find market, place order, cancel order): task success rate and steps per task, compared with the scripted agent.
- Roll out to a share of agents (`SWARM_MODEL_DRIVEN_PCT`), with scripted fallback when a step fails; dashboard for model-agent success rate.

## Verification (end of Phase 1)

- `make up` starts everything healthy; `make smoke` creates two test accounts and trades between them.
- `http://localhost:3000`: sign in as admin, create a market, post a headline; in a second browser a trader sees both appear live, places an order, and sees it fill against the smoke script or another account.
- Resolve the market as admin: trading stops, the winning side is paid, Portfolio and Leaderboard update.
- A trader account gets "not allowed" on the Analytics screen and its API.
- RabbitMQ UI shows queues draining with no dead letters; MinIO console shows Parquet files under the raw area; after one Dagster run the Analytics screen shows the new activity.
- Grafana: the dashboard shows live order rate and latency; one order's trace can be followed from gateway to trading-service to RabbitMQ to realtime-service.
- `./gradlew test`: matching-engine unit tests and the conservation property test pass.
- Restart trading-service mid-run: the order book is rebuilt and trading resumes with no lost or duplicated trades.

## Design references

- Architecture diagram: [architecture.html](architecture.html) (open in a browser), also published at https://claude.ai/artifact/BMjjNB9CwknEj4E3scSfXZ
- UI mockups (sign in, markets list, market page, portfolio, leaderboard, admin analytics): https://claude.ai/artifact/UVqy2zWmADQBymUqLbbWLD
