create table trading.markets (
    id            uuid primary key,
    slug          text unique,
    question      text not null,
    yes_statement text not null,
    no_statement  text not null,
    kind          text not null check (kind in ('SIMULATED', 'REAL')),
    status        text not null check (status in ('OPEN', 'CLOSED', 'RESOLVED')),
    close_at      timestamptz not null,
    created_at    timestamptz not null,
    resolved_at   timestamptz,
    outcome       text check (outcome in ('YES', 'NO')),
    last_price    int,
    volume_cents  bigint not null default 0,
    trade_count   int not null default 0,
    trader_count  int not null default 0,
    escrow_cents  bigint not null default 0 check (escrow_cents >= 0),
    created_by    uuid
);
create index markets_status on trading.markets (status, close_at);

create table trading.wallets (
    user_id         uuid primary key,
    username        text not null,
    account_type    text not null,
    available_cents bigint not null check (available_cents >= 0),
    reserved_cents  bigint not null check (reserved_cents >= 0),
    created_at      timestamptz not null default now()
);

create table trading.positions (
    user_id        uuid not null,
    market_id      uuid not null references trading.markets (id),
    yes_shares     bigint not null default 0 check (yes_shares >= 0),
    no_shares      bigint not null default 0 check (no_shares >= 0),
    yes_reserved   bigint not null default 0 check (yes_reserved >= 0 and yes_reserved <= yes_shares),
    no_reserved    bigint not null default 0 check (no_reserved >= 0 and no_reserved <= no_shares),
    yes_cost_cents bigint not null default 0,
    no_cost_cents  bigint not null default 0,
    payout_cents   bigint,
    primary key (user_id, market_id)
);
create index positions_market on trading.positions (market_id);

create table trading.orders (
    id                uuid primary key,
    seq               bigint not null,
    market_id         uuid not null references trading.markets (id),
    user_id           uuid not null,
    username          text not null,
    side              text not null,
    action            text not null,
    price             int not null check (price between 1 and 99),
    yes_price         int not null,
    book_side         text not null,
    shares            int not null check (shares > 0),
    filled_shares     int not null default 0,
    status            text not null,
    reserved_cents    bigint not null default 0 check (reserved_cents >= 0),
    filled_cost_cents bigint not null default 0,
    idempotency_key   text,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    unique (user_id, idempotency_key)
);
create sequence trading.order_seq;
create index orders_open on trading.orders (market_id, seq) where status = 'OPEN';
create index orders_user on trading.orders (user_id, created_at desc);

create table trading.trades (
    id             uuid primary key,
    seq            bigserial,
    market_id      uuid not null references trading.markets (id),
    price          int not null,
    shares         int not null,
    kind           text not null,
    bid_order_id   uuid not null,
    ask_order_id   uuid not null,
    bid_user_id    uuid not null,
    ask_user_id    uuid not null,
    taker_order_id uuid not null,
    taker_user_id  uuid not null,
    taker_username text not null,
    taker_side     text not null,
    taker_action   text not null,
    taker_price    int not null,
    maker_username text not null,
    executed_at    timestamptz not null
);
create index trades_market on trading.trades (market_id, seq desc);

create table trading.market_traders (
    market_id uuid not null,
    user_id   uuid not null,
    primary key (market_id, user_id)
);

-- Double-entry ledger: the legs of each tx_id sum to zero.
create table trading.ledger_entries (
    id           bigserial primary key,
    tx_id        uuid not null,
    kind         text not null,
    account      text not null,
    user_id      uuid,
    market_id    uuid,
    amount_cents bigint not null,
    created_at   timestamptz not null default now()
);
create index ledger_tx on trading.ledger_entries (tx_id);
create index ledger_user on trading.ledger_entries (user_id);

create table trading.outbox (
    seq          bigserial primary key,
    id           uuid not null unique,
    event_type   text not null,
    event_key    text,
    envelope     jsonb not null,
    traceparent  text,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished on trading.outbox (seq) where published_at is null;
