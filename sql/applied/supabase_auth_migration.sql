-- Blood Donation v1.4 — Supabase Auth migration.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_migration.sql.
-- Links each donor profile to the Supabase Auth user who owns it, so a
-- donor who logs in on any device gets their own profile back.

alter table donors add column if not exists auth_id uuid;

-- One donor profile per Auth user (partial index: rows without auth_id,
-- e.g. the 5 seeded demo donors, are unaffected).
create unique index if not exists donors_auth_id_key
  on donors (auth_id) where auth_id is not null;

-- The Supabase "users" table is legacy: v1.2 mirrored local accounts
-- there, but the app never read or wrote it, and v1.4 uses Supabase
-- Auth (auth.users) instead. It is deliberately NOT dropped, so nothing
-- that references it breaks; it is simply unused from v1.4 on.
