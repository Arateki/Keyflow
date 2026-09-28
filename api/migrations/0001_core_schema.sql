-- Schema núcleo (design.md §Schema). Nenhuma coluna de PII, áudio ou texto transcrito (ID-03).

create extension if not exists pgcrypto;

create table accounts (
    id uuid primary key default gen_random_uuid(),
    device_key text unique not null,
    token_hash bytea not null,
    balance_seconds bigint not null default 0,
    ads_off boolean not null default false,
    skins_unlocked boolean not null default false,
    created_at timestamptz not null default now()
);

create table ledger (
    id bigserial primary key,
    account_id uuid not null references accounts (id),
    delta_seconds bigint not null,
    reason text not null check (reason in ('purchase', 'transcription', 'reward', 'revoke')),
    ref text,
    created_at timestamptz not null default now()
);

create index ledger_account_id_idx on ledger (account_id);

create table purchases (
    token_hash bytea primary key,
    account_id uuid not null references accounts (id),
    product_id text not null,
    status text not null,
    credited_seconds bigint not null,
    granted_entitlements boolean not null default false,
    created_at timestamptz not null default now()
);

create table rewards (
    transaction_id text primary key,
    account_id uuid not null references accounts (id),
    seconds bigint not null,
    created_at timestamptz not null default now()
);

create table transcribe_requests (
    account_id uuid not null references accounts (id),
    request_id text not null,
    seconds bigint not null,
    created_at timestamptz not null default now(),
    primary key (account_id, request_id)
);

create table documents (
    key text primary key,
    body jsonb not null,
    updated_at timestamptz not null default now()
);
