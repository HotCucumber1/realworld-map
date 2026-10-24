"""Checks supabase/schema.sql against a real PostgreSQL 15+ server with a stub of Supabase's auth schema.

Usage:  pip install "psycopg[binary]"
        python supabase/verify_schema.py supabase/schema.sql "host=127.0.0.1 port=5432 user=postgres dbname=postgres"
WARNING: drops and recreates the `public` and `auth` schemas of the target database — use a throwaway one.
"""
import sys, psycopg
schema = open(sys.argv[1]).read()
c = psycopg.connect(sys.argv[2] if len(sys.argv) > 2 else "host=127.0.0.1 port=5432 user=postgres dbname=postgres", autocommit=True)
x = c.execute
x("drop schema if exists auth cascade; drop schema public cascade; create schema public;")
for r in ("anon", "authenticated"):
    x(f"do $$ begin if not exists (select from pg_roles where rolname='{r}') then create role {r}; end if; end $$;")
x("""create schema auth; create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
grant usage on schema public to authenticated, anon; grant usage on schema auth to authenticated;""")
x(schema); x(schema)  # applying twice proves it is re-runnable
print("schema applied twice: OK")
U1, U2 = "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"
x("insert into auth.users values (%s), (%s)", (U1, U2))
def as_user(u): x("set role authenticated"); x("select set_config('request.jwt.claim.sub', %s, false)", (u,))
as_user(U1)
up_t = "insert into tracks values (%s,'t1',1,2,%s,10,1,0,2,1,%s) on conflict (user_id,id) do update set status=excluded.status, updated_at=excluded.updated_at"
x(up_t, (U1, "COMPLETED", 100)); x(up_t, (U1, "INTERRUPTED", 50))
assert x("select status from tracks").fetchone()[0] == "COMPLETED"; print("tracks: last-writer-wins OK")
p = "insert into location_points values (%s,'p1','t1',48,11,5,3,null,null,null,'GPS',20) on conflict do nothing"
x(p, (U1,)); x(p, (U1,))
assert x("select count(*) from location_points").fetchone()[0] == 1; print("points: duplicates ignored OK")
cu = "insert into explored_cells values (%s,1,2,%s) on conflict (user_id,cell_row,cell_col) do update set first_visited_at=excluded.first_visited_at"
x(cu, (U1, 500)); s1 = x("select seq from explored_cells").fetchone()[0]
x(cu, (U1, 900)); assert x("select first_visited_at, seq from explored_cells").fetchone() == (500, s1)
x(cu, (U1, 300)); fv, s2 = x("select first_visited_at, seq from explored_cells").fetchone()
assert fv == 300 and s2 > s1; print("cells: earliest visit kept, seq bumps only on change OK")
as_user(U2)
assert x("select count(*) from tracks").fetchone()[0] == 0; print("RLS: other user sees nothing OK")
try:
    x("insert into tracks values (%s,'evil',1,2,'COMPLETED',0,0,0,null,1,1)", (U1,)); print("RLS insert NOT blocked!"); sys.exit(1)
except psycopg.errors.InsufficientPrivilege: print("RLS: writing another user's rows blocked OK")
x("reset role"); x("set role anon")
try:
    x("select * from tracks"); print("anon NOT blocked!"); sys.exit(1)
except psycopg.errors.InsufficientPrivilege: print("anon: no access OK")
x("reset role"); as_user(U1)
x("select delete_user()"); x("reset role")
assert x("select (select count(*) from tracks)+(select count(*) from location_points)+(select count(*) from explored_cells)").fetchone()[0] == 0
assert x("select count(*) from auth.users where id=%s", (U1,)).fetchone()[0] == 0
print("delete_user: account and all rows removed OK")
