-- Kairo: esquema inicial
create extension if not exists vector;
create extension if not exists pg_trgm;

create table users (
    id            uuid primary key default gen_random_uuid(),
    email         text not null unique,
    display_name  text not null,
    password_hash text not null,
    timezone      text not null default 'UTC',
    created_at    timestamptz not null default now()
);

create table user_settings (
    user_id uuid primary key references users(id) on delete cascade,
    data    jsonb not null default '{}'::jsonb
);

create table devices (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null references users(id) on delete cascade,
    name               text not null,
    platform           text not null,
    refresh_token_hash text not null unique,
    created_at         timestamptz not null default now(),
    last_seen_at       timestamptz not null default now(),
    revoked_at         timestamptz
);
create index devices_user_idx on devices(user_id);

create table files (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users(id) on delete cascade,
    content_type text not null,
    name         text,
    bytes        bytea not null,
    created_at   timestamptz not null default now()
);

create table notes (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    title       text not null default '',
    content     text not null default '',
    tags        text[] not null default '{}',
    pinned      boolean not null default false,
    source      text not null default 'dashboard',
    attachments uuid[] not null default '{}',
    embedding   vector(1024),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    deleted_at  timestamptz
);
create index notes_user_idx on notes(user_id, updated_at desc) where deleted_at is null;
create index notes_embedding_idx on notes using hnsw (embedding vector_cosine_ops);
create index notes_fts_idx on notes using gin (to_tsvector('spanish', title || ' ' || content));
create index notes_trgm_idx on notes using gin ((title || ' ' || content) gin_trgm_ops);

create table projects (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    name       text not null,
    color      text,
    sort       double precision not null default 0,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);
create unique index projects_name_idx on projects(user_id, lower(name)) where deleted_at is null;

create table tasks (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users(id) on delete cascade,
    project_id   uuid references projects(id) on delete set null,
    title        text not null,
    notes        text not null default '',
    status       text not null default 'todo' check (status in ('todo','doing','done')),
    priority     text not null default 'medium' check (priority in ('low','medium','high','urgent')),
    due_date     date,
    due_at       timestamptz,
    tags         text[] not null default '{}',
    sort         double precision not null default 0,
    source       text not null default 'dashboard',
    completed_at timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);
create index tasks_user_idx on tasks(user_id, status, sort) where deleted_at is null;

create table places (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    name       text not null,
    latitude   double precision not null,
    longitude  double precision not null,
    radius_m   integer not null default 150,
    address    text,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);
create unique index places_name_idx on places(user_id, lower(name)) where deleted_at is null;

create table reminders (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users(id) on delete cascade,
    text          text not null,
    due_at        timestamptz,
    rrule         text,
    timezone      text not null default 'UTC',
    place_id      uuid references places(id) on delete set null,
    place_trigger text check (place_trigger in ('arrive','leave')),
    status        text not null default 'scheduled' check (status in ('scheduled','fired','done','cancelled')),
    next_fire_at  timestamptz,
    last_fired_at timestamptz,
    task_id       uuid references tasks(id) on delete set null,
    source        text not null default 'dashboard',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    deleted_at    timestamptz
);
create index reminders_due_idx on reminders(next_fire_at) where status = 'scheduled' and deleted_at is null;
create index reminders_user_idx on reminders(user_id, status) where deleted_at is null;

create table lists (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    name       text not null,
    icon       text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);
create unique index lists_name_idx on lists(user_id, lower(name)) where deleted_at is null;

create table list_items (
    id         uuid primary key default gen_random_uuid(),
    list_id    uuid not null references lists(id) on delete cascade,
    text       text not null,
    checked    boolean not null default false,
    sort       double precision not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index list_items_list_idx on list_items(list_id, sort);

create table events (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid not null references users(id) on delete cascade,
    title              text not null,
    description        text not null default '',
    location           text not null default '',
    start_at           timestamptz not null,
    end_at             timestamptz not null,
    all_day            boolean not null default false,
    rrule              text,
    timezone           text not null default 'UTC',
    reminder_minutes   integer[] not null default '{}',
    color              text,
    source             text not null default 'kairo',
    google_event_id    text,
    google_calendar_id text,
    google_etag        text,
    google_recurring_id text,
    dirty              boolean not null default false,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    deleted_at         timestamptz
);
create index events_range_idx on events(user_id, start_at) where deleted_at is null;
create unique index events_google_idx on events(user_id, google_event_id) where google_event_id is not null;

create table memories (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    content    text not null,
    category   text not null default 'general',
    embedding  vector(1024),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index memories_user_idx on memories(user_id) where deleted_at is null;
create index memories_embedding_idx on memories using hnsw (embedding vector_cosine_ops);

create table conversations (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    title      text not null default '',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index conversations_user_idx on conversations(user_id, updated_at desc);

create table messages (
    id                uuid primary key default gen_random_uuid(),
    conversation_id   uuid not null references conversations(id) on delete cascade,
    role              text not null check (role in ('user','assistant','tool')),
    content           text not null default '',
    tool_calls        jsonb,
    tool_call_id      text,
    tool_name         text,
    reasoning_content text,
    meta              jsonb,
    created_at        timestamptz not null default now()
);
create index messages_conv_idx on messages(conversation_id, created_at);

create table recordings (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users(id) on delete cascade,
    title        text not null default '',
    status       text not null default 'recording' check (status in ('recording','processing','done','failed')),
    duration_s   integer not null default 0,
    transcript   text not null default '',
    summary      text not null default '',
    key_points   jsonb not null default '[]'::jsonb,
    action_items jsonb not null default '[]'::jsonb,
    note_id      uuid references notes(id) on delete set null,
    error        text,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);

create table recording_segments (
    id           uuid primary key default gen_random_uuid(),
    recording_id uuid not null references recordings(id) on delete cascade,
    seq          integer not null,
    duration_s   integer not null default 0,
    transcript   text not null default '',
    created_at   timestamptz not null default now(),
    unique (recording_id, seq)
);

create table activity (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    kind        text not null,
    entity_type text not null,
    entity_id   uuid,
    summary     text not null,
    source      text not null default 'system',
    data        jsonb,
    undone_at   timestamptz,
    created_at  timestamptz not null default now()
);
create index activity_user_idx on activity(user_id, created_at desc);

create table integrations (
    user_id    uuid not null references users(id) on delete cascade,
    provider   text not null,
    secret     text not null,
    meta       jsonb not null default '{}'::jsonb,
    status     text not null default 'connected',
    last_sync_at timestamptz,
    last_error text,
    updated_at timestamptz not null default now(),
    primary key (user_id, provider)
);

create table mcp_servers (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    name       text not null,
    url        text not null,
    headers    text,
    enabled    boolean not null default true,
    created_at timestamptz not null default now()
);
create unique index mcp_servers_name_idx on mcp_servers(user_id, lower(name));
