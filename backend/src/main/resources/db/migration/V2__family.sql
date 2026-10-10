-- Kairo: familia (hogar con varios perfiles), elementos compartidos, avisos y push propio

create table households (
    id         uuid primary key default gen_random_uuid(),
    name       text not null,
    created_at timestamptz not null default now()
);

alter table users
    add column household_id uuid references households(id) on delete restrict,
    add column role         text not null default 'member' check (role in ('admin','member')),
    add column color        text;

-- Cada usuario existente (hoy, el propietario) queda como administrador de su propio hogar
do $$
declare
    u record;
    h uuid;
begin
    for u in select id, display_name from users where household_id is null order by created_at loop
        insert into households(name) values ('Familia de ' || u.display_name) returning id into h;
        update users set household_id = h, role = 'admin' where id = u.id;
    end loop;
end $$;

alter table users alter column household_id set not null;
create index users_household_idx on users(household_id);

-- Invitaciones (nuevo miembro) y códigos de acceso (restablecer la contraseña de un miembro)
create table invitations (
    id             uuid primary key default gen_random_uuid(),
    household_id   uuid not null references households(id) on delete cascade,
    code_hash      text not null unique,
    name           text not null default '',
    role           text not null default 'member' check (role in ('admin','member')),
    target_user_id uuid references users(id) on delete cascade,
    created_by     uuid references users(id) on delete set null,
    created_at     timestamptz not null default now(),
    expires_at     timestamptz not null,
    used_at        timestamptz,
    used_by        uuid references users(id) on delete set null
);
create index invitations_household_idx on invitations(household_id, created_at desc);

-- Elementos que se pueden compartir con la familia: la fila guarda el hogar de su dueño
alter table notes  add column household_id uuid references households(id) on delete cascade, add column shared boolean not null default false;
alter table tasks  add column household_id uuid references households(id) on delete cascade, add column shared boolean not null default false,
                   add column assignee_id uuid references users(id) on delete set null;
alter table lists  add column household_id uuid references households(id) on delete cascade, add column shared boolean not null default false;
alter table events add column household_id uuid references households(id) on delete cascade, add column shared boolean not null default false;
alter table places add column household_id uuid references households(id) on delete cascade, add column shared boolean not null default false;

update notes  t set household_id = u.household_id from users u where u.id = t.user_id;
update tasks  t set household_id = u.household_id from users u where u.id = t.user_id;
update lists  t set household_id = u.household_id from users u where u.id = t.user_id;
update events t set household_id = u.household_id from users u where u.id = t.user_id;
update places t set household_id = u.household_id from users u where u.id = t.user_id;

alter table notes  alter column household_id set not null;
alter table tasks  alter column household_id set not null;
alter table lists  alter column household_id set not null;
alter table events alter column household_id set not null;
alter table places alter column household_id set not null;

create index notes_shared_idx  on notes(household_id, updated_at desc) where shared and deleted_at is null;
create index tasks_shared_idx  on tasks(household_id, status) where shared and deleted_at is null;
create index tasks_assignee_idx on tasks(assignee_id) where assignee_id is not null and deleted_at is null;
create index lists_shared_idx  on lists(household_id) where shared and deleted_at is null;
create index events_shared_idx on events(household_id, start_at) where shared and deleted_at is null;
create index places_shared_idx on places(household_id) where shared and deleted_at is null;

-- Recordatorios que un miembro programa para otro ("recuérdale a Sofía...")
alter table reminders add column created_by uuid references users(id) on delete set null;

-- Avisos para un miembro: algo compartido, una tarea asignada, un mensaje de la familia
create table notices (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    actor_id    uuid references users(id) on delete set null,
    kind        text not null,
    title       text not null,
    body        text not null default '',
    entity_type text,
    entity_id   uuid,
    created_at  timestamptz not null default now(),
    read_at     timestamptz
);
create index notices_user_idx on notices(user_id, created_at desc);

-- Push sin Firebase: endpoints Web Push (UnifiedPush) por dispositivo
create table push_endpoints (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    device_id   uuid references devices(id) on delete cascade,
    endpoint    text not null unique,
    p256dh      text not null,
    auth        text not null,
    created_at  timestamptz not null default now(),
    last_ok_at  timestamptz,
    failures    integer not null default 0
);
create index push_endpoints_user_idx on push_endpoints(user_id);

-- Secretos del servidor generados en el arranque (clave VAPID), cifrados con KAIRO_ENCRYPTION_KEY
create table server_secrets (
    name       text primary key,
    value      text not null,
    created_at timestamptz not null default now()
);
