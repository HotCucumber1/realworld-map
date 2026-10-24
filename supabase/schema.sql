-- World Explorer — Supabase schema (PostgreSQL 15+).
-- Run once in the Supabase dashboard: SQL Editor → New query → paste → Run.
-- Safe to re-run: objects are created with IF NOT EXISTS / OR REPLACE.
--
-- Every row belongs to an auth user (user_id) and Row Level Security guarantees that a user can
-- read and write only their own rows. Times are epoch milliseconds (UTC) as stored by the app.
-- `seq` is a server-assigned, monotonically increasing number used for incremental pulls.

create sequence if not exists public.sync_seq;

create or replace function public.assign_sync_seq() returns trigger
language plpgsql as $$
begin
  new.seq := nextval('public.sync_seq');
  return new;
end $$;

-- ---------------------------------------------------------------- tracks
create table if not exists public.tracks (
  user_id              uuid    not null references auth.users (id) on delete cascade,
  id                   text    not null,
  started_at           bigint  not null,
  ended_at             bigint,
  status               text    not null check (status in ('ACTIVE', 'COMPLETED', 'INTERRUPTED')),
  distance_meters      double precision not null default 0,
  point_count          integer not null default 0,
  rejected_point_count integer not null default 0,
  last_point_at        bigint,
  created_at           bigint  not null,
  updated_at           bigint  not null,
  seq                  bigint  not null default 0,
  primary key (user_id, id)
);
create index if not exists tracks_user_seq on public.tracks (user_id, seq);

-- Last-writer-wins: an upsert carrying an older updated_at does not overwrite a newer row.
create or replace function public.tracks_lww() returns trigger
language plpgsql as $$
begin
  if new.updated_at < old.updated_at then
    return null; -- keep the existing, newer row (seq unchanged)
  end if;
  new.seq := nextval('public.sync_seq');
  return new;
end $$;

drop trigger if exists tracks_seq_insert on public.tracks;
create trigger tracks_seq_insert before insert on public.tracks for each row execute function public.assign_sync_seq();
drop trigger if exists tracks_lww_update on public.tracks;
create trigger tracks_lww_update before update on public.tracks for each row execute function public.tracks_lww();

-- ---------------------------------------------------------------- location points (immutable)
create table if not exists public.location_points (
  user_id               uuid    not null,
  id                    text    not null,
  track_id              text    not null,
  latitude              double precision not null check (latitude between -90 and 90),
  longitude             double precision not null check (longitude between -180 and 180),
  timestamp_utc         bigint  not null,
  accuracy_meters       real    not null,
  altitude_meters       double precision,
  speed_mps             real,
  bearing_degrees       real,
  source                text    not null,
  explore_radius_meters integer not null,
  seq                   bigint  not null default 0,
  primary key (user_id, id),
  foreign key (user_id, track_id) references public.tracks (user_id, id) on delete cascade
);
create index if not exists points_user_seq on public.location_points (user_id, seq);

drop trigger if exists points_seq_insert on public.location_points;
create trigger points_seq_insert before insert on public.location_points for each row execute function public.assign_sync_seq();

-- ---------------------------------------------------------------- explored cells (grow-only set)
create table if not exists public.explored_cells (
  user_id          uuid    not null references auth.users (id) on delete cascade,
  cell_row         integer not null check (cell_row between 0 and 3599999),
  cell_col         integer not null check (cell_col between 0 and 7199999),
  first_visited_at bigint  not null,
  seq              bigint  not null default 0,
  primary key (user_id, cell_row, cell_col)
);
create index if not exists cells_user_seq on public.explored_cells (user_id, seq);

-- Merge rule: keep the earliest first visit; only an actual change gets a new seq.
create or replace function public.cells_keep_earliest() returns trigger
language plpgsql as $$
begin
  if new.first_visited_at >= old.first_visited_at then
    return null;
  end if;
  new.seq := nextval('public.sync_seq');
  return new;
end $$;

drop trigger if exists cells_seq_insert on public.explored_cells;
create trigger cells_seq_insert before insert on public.explored_cells for each row execute function public.assign_sync_seq();
drop trigger if exists cells_earliest_update on public.explored_cells;
create trigger cells_earliest_update before update on public.explored_cells for each row execute function public.cells_keep_earliest();

-- ---------------------------------------------------------------- row level security
alter table public.tracks enable row level security;
alter table public.location_points enable row level security;
alter table public.explored_cells enable row level security;

do $$
declare t text;
begin
  foreach t in array array['tracks', 'location_points', 'explored_cells'] loop
    execute format('drop policy if exists "own rows select" on public.%I', t);
    execute format('drop policy if exists "own rows insert" on public.%I', t);
    execute format('drop policy if exists "own rows update" on public.%I', t);
    execute format('drop policy if exists "own rows delete" on public.%I', t);
    execute format('create policy "own rows select" on public.%I for select to authenticated using (user_id = auth.uid())', t);
    execute format('create policy "own rows insert" on public.%I for insert to authenticated with check (user_id = auth.uid())', t);
    execute format('create policy "own rows update" on public.%I for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid())', t);
    execute format('create policy "own rows delete" on public.%I for delete to authenticated using (user_id = auth.uid())', t);
  end loop;
end $$;

-- Anonymous (not signed in) clients get nothing.
revoke all on public.tracks, public.location_points, public.explored_cells from anon;
grant select, insert, update, delete on public.tracks, public.location_points, public.explored_cells to authenticated;
-- The seq triggers run as the calling user and need the sequence.
grant usage, select on sequence public.sync_seq to authenticated;

-- ---------------------------------------------------------------- account deletion
-- Lets a signed-in user delete their own account; all rows are removed by ON DELETE CASCADE.
create or replace function public.delete_user() returns void
language sql security definer set search_path = public as $$
  delete from auth.users where id = auth.uid();
$$;
revoke all on function public.delete_user() from public, anon;
grant execute on function public.delete_user() to authenticated;
