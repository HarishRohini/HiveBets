create table identity.users (
    id            uuid primary key,
    username      text not null unique,
    password_hash text not null,
    roles         text not null,
    account_type  text not null check (account_type in ('HUMAN', 'AGENT', 'SERVICE')),
    created_at    timestamptz not null default now()
);

create table identity.signing_keys (
    kid         text primary key,
    private_jwk text not null,
    created_at  timestamptz not null default now()
);
