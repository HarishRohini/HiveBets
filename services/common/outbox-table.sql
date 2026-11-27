-- Copy into each service's Flyway migration, replacing SCHEMA.
create table SCHEMA.outbox (
    seq          bigserial primary key,
    id           uuid not null unique,
    event_type   text not null,
    event_key    text,
    envelope     jsonb not null,
    traceparent  text,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished on SCHEMA.outbox (seq) where published_at is null;
