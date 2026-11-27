# HiveBets API and event contracts

Every browser and script call goes through the gateway at `http://localhost:8080`. Services trust the identity headers the gateway adds and never read the session cookie themselves.

## Conventions

- Money is integer **cents**. A share pays 100 cents.
- Prices are integer cents, 1–99. Book prices, trade prices and `lastPrice` are always in **YES terms**. An order's `price` is in the terms of its own `side` (a NO buy at 37 is a YES ask at 63).
- Timestamps are ISO-8601 UTC strings.
- Errors: `{"error": "CODE", "message": "human text"}` with a 4xx status. Codes: `VALIDATION`, `UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `MARKET_CLOSED`, `INSUFFICIENT_FUNDS`, `INSUFFICIENT_SHARES`, `CONFLICT`, `RATE_LIMITED`.

## Identity and roles

- Session: httpOnly cookie `hb_session` holding an RS256 JWT, set by sign-up and sign-in. Scripts may send `Authorization: Bearer <token>` instead; `token` is also returned in the sign-in body.
- JWT claims: `sub` user id, `name` username, `roles` (`ADMIN`, `TRADER`, `ENGINE`), `acct` (`HUMAN`, `AGENT`, `SERVICE`).
- The gateway strips any incoming `X-User-*` headers, validates the token, and forwards `X-User-Id`, `X-User-Name`, `X-User-Roles` (comma separated), `X-Account-Type`, `X-Request-Id`.

| Path | Who |
|---|---|
| `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/events`, `GET /api/live/stats` | anyone |
| `POST /api/markets`, `POST /api/markets/{id}/resolve` | `ADMIN` or `ENGINE` |
| `POST /api/news` | `ADMIN` or `ENGINE` |
| `/api/analytics/**`, `/api/admin/**` | `ADMIN` |
| everything else under `/api/**`, `/ws` | any signed-in user |

## identity-service (port 8101)

- `POST /api/auth/signup` `{username, password, inviteCode?}` → `201 {user, token}` and cookie. Username: 3–32 chars of `a-z 0-9 - _`. Password: at least 8 chars. `inviteCode` equal to `AGENT_INVITE_CODE` makes the account `AGENT`; any other non-empty code is a `VALIDATION` error.
- `POST /api/auth/login` `{username, password}` → `200 {user, token}` and cookie; `401` on bad credentials. Five failures in a minute lock that username for a minute (`429`).
- `POST /api/auth/logout` → `204`, clears the cookie.
- `GET /api/auth/me` → `user`.
- `GET /.well-known/jwks.json` → public keys (internal; the gateway reads it).
- `user` = `{id, username, roles: [..], accountType, createdAt}`.
- Seeded at boot: the admin (`ADMIN_USERNAME`/`ADMIN_PASSWORD`, roles `ADMIN`,`TRADER`) and the engine service account (`ENGINE_USERNAME`/`ENGINE_PASSWORD`, role `ENGINE`, account type `SERVICE`).

## trading-service (port 8102)

`Market` = `{id, question, yesStatement, noStatement, kind: SIMULATED|REAL, status: OPEN|CLOSED|RESOLVED, closeAt, createdAt, resolvedAt, outcome: YES|NO|null, lastPrice, volumeCents, tradeCount, traderCount}`

- `GET /api/markets?status=OPEN|CLOSED|RESOLVED|ALL&kind=SIMULATED|REAL` → `[Market]` (default `status=ALL`, newest first, max 200).
- `GET /api/markets/{id}` → `Market`.
- `POST /api/markets` `{question, yesStatement?, noStatement?, kind?, closeAt? | durationMinutes?}` → `201 Market`.
- `POST /api/markets/{id}/resolve` `{outcome: YES|NO}` → `Market`. Cancels unfilled orders, releases their reserves, pays 100 cents per winning share.
- `GET /api/markets/{id}/book` → `{bids: [{price, shares}], asks: [{price, shares}]}` (YES terms, best first).
- `GET /api/markets/{id}/trades?limit=50` → `[Trade]`.
- `POST /api/orders` header `Idempotency-Key: <uuid>` (optional) body `{marketId, side: YES|NO, action: BUY|SELL, price, shares}` → `201 Order`. Same key again returns the first order with `200`.
- `DELETE /api/orders/{id}` → `Order`.
- `GET /api/orders?status=OPEN|ALL&marketId=` → own `[Order]`.
- `GET /api/portfolio` → `{wallet, positions, resolved}`:
  - `wallet` = `{availableCents, reservedCents, equityCents, profitCents, startingCents}`
  - `positions[]` = `{marketId, question, kind, status, closeAt, yesShares, noShares, yesCostCents, noCostCents, lastPrice, valueCents, profitCents}`
  - `resolved[]` = `{marketId, question, outcome, yesShares, noShares, costCents, payoutCents, profitCents, resolvedAt}`

`Order` = `{id, marketId, side, action, price, shares, filledShares, status: OPEN|FILLED|CANCELLED, reservedCents, avgFillPrice, createdAt, updatedAt}`

`Trade` = `{id, marketId, price, shares, kind: MINT|TRANSFER_YES|TRANSFER_NO|BURN, takerUserId, takerUsername, takerSide, takerAction, takerPrice, makerUsername, executedAt}`. `takerPrice` is the price in the taker's own side terms.

Every account starts with 100000 cents. Buying reserves `price × shares` cents; selling reserves the shares. Unfilled parts stay reserved until filled or cancelled.

## news-service (port 8103)

`Headline` = `{id, marketId, text, source: synthetic|real|manual, url, publisher, publishedAt}`

- `GET /api/news?marketId=&limit=50` → `[Headline]`, newest first.
- `POST /api/news` `{marketId, text, source?, url?, publisher?}` → `201 Headline`. `source` defaults to `manual` for admins and `synthetic` for the engine.
- `GET /api/admin/news/ingested?limit=100` → `[{id, title, url, publisher, feed, similarity, matchedMarketId, status: MATCHED|UNMATCHED|DUPLICATE|NO_EMBEDDINGS, headlineId, ingestedAt}]`, newest first (Phase 2).
- `POST /api/admin/news/poll` → `{fetched, added, outcomes}`: runs one RSS poll now (admin).

## realtime-service (port 8104)

- `GET /api/live/markets` → `{"<marketId>": {lastPrice, volumeCents, traderCount, spark: [price, ...]}}`.
- `GET /api/live/markets/{id}` → `{marketId, lastPrice, book: {bids, asks}, candles: [{t, o, h, l, c, v}], trades: [Trade], volumeCents, traderCount, news: [Headline]}`. Candles are 10 seconds; `t` is epoch seconds.
- `GET /api/live/leaderboard?limit=50` → `{rows: [LeaderRow], me: LeaderRow|null, total}`, where `LeaderRow` = `{rank, userId, username, accountType, profitCents, equityCents, marketsTraded, marketsWon, marketsResolved}`.
- `GET /api/live/equity` → own `[{t, equityCents}]` (epoch seconds, last hour).
- `GET /api/live/stats` → `{tradersOnline, connections}`.
- `WS /ws`: the client sends `{"op": "subscribe" | "unsubscribe", "topics": ["markets", "market:<id>", "leaderboard", "news", "user"]}`. The server sends `{"topic", "type", "data"}`, where `type` is the event type below and `data` is its payload.

## gateway (port 8080)

- `POST /api/events` `{events: [{type, sessionId, path, ts, props}]}` → `202`. Accepts `application/json` or `text/plain` (for `navigator.sendBeacon`). `type` is one of `page_view`, `click`, `order_submitted`, `session_start`, `session_end`, `web_vital`. Each is published as a `client.<type>` event with the user id attached when signed in.

## analytics-service (port 8106, admin only)

Reads the gold tables in the Postgres `analytics` schema.

- `GET /api/analytics/summary` → `{asOf, marketsResolved, trades, volumeCents, sessions, orders}`
- `GET /api/analytics/markets` → `[{marketId, question, kind, status, volumeCents, trades, traders, avgSpreadCents, avgDepthShares}]`
- `GET /api/analytics/funnel` → `[{step, sessions}]` in funnel order
- `GET /api/analytics/web-vitals` → `[{metric, page, p75, samples}]`
- `GET /api/analytics/traders` → `[{userId, username, accountType, profitCents, trades, volumeCents}]`

## Events

Exchange `hivebets.events` (topic, durable). The routing key is the event type. Every consumer owns a durable queue with a dead-letter queue (`<queue>.dlq` via exchange `hivebets.dlx`).

```json
{"eventId": "uuid", "type": "trade.executed", "version": 1, "occurredAt": "2026-11-27T10:00:00Z",
 "source": "trading-service", "key": "<marketId or userId>", "traceId": "hex or null", "payload": {}}
```

| type | producer | payload |
|---|---|---|
| `market.created` | trading | `Market` |
| `market.closed` | trading | `{marketId, closedAt}` |
| `market.resolved` | trading | `{marketId, outcome, resolvedAt, payouts: [{userId, payoutCents}]}` |
| `order.accepted` | trading | `{orderId, marketId, userId, side, action, price, shares, yesPrice, bookSide: BID|ASK}` |
| `order.cancelled` | trading | `{orderId, marketId, userId, remainingShares, reason}` |
| `trade.executed` | trading | `Trade` plus `bidUserId, askUserId` |
| `book.changed` | trading | `{marketId, bids, asks}` (top 10 levels each) |
| `wallet.changed` | trading | `{userId, username, accountType, availableCents, reservedCents, equityCents, profitCents, marketsTraded, marketsWon, marketsResolved}` |
| `news.published` | news | `Headline` |
| `client.*` | gateway | `{sessionId, userId, path, ts, props, userAgent}` |
