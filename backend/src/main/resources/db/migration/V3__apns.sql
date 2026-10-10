-- Push nativo de Apple (APNs): tokens de la app de iPhone, ligados al dispositivo (sesión) que los registró.
-- Igual que push_endpoints: al revocar una sesión se borran sus tokens; al borrar el usuario, en cascada.
create table apns_tokens (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    device_id   uuid references devices(id) on delete cascade,
    token       text not null unique,
    environment text not null check (environment in ('sandbox', 'production')),
    kind        text not null default 'device',
    activity_id text,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);
create index apns_tokens_user_idx on apns_tokens(user_id);
