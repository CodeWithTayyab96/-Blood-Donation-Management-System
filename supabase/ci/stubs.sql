-- ============================================================================
-- TEST-ONLY — supabase/ci/stubs.sql
-- NEVER run this on the live Supabase project (or anywhere but CI).
--
-- What the SQL CI job does: it replays every repository SQL file against a
-- plain Postgres 16 service container and runs the security tests in
-- supabase/ci/tests.sql. Vanilla Postgres does not have the platform pieces
-- that Supabase provides around its database, so this file builds the
-- cheapest faithful stubs of them:
--
--   * roles anon / authenticated / service_role (service_role with BYPASSRLS,
--     as on the platform). Test SQL switches with `set local role ...`.
--   * the auth schema: auth.users (the columns our SQL touches), and
--     auth.uid() / auth.jwt() reading the `request.jwt.claims` GUC exactly
--     like PostgREST does — so the test file can impersonate a signed-in
--     user by setting that GUC.
--   * Supabase's default grants: on the platform, PostgREST can read the
--     catalogue because anon/authenticated/table grants come "for free";
--     here they are declared so the tests exercise RLS, NOT missing grants.
--
-- The live project equivalent: Supabase does all of this internally. Do not
-- run this file there — it would only re-create existing objects.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. Roles (no members, no logins — SQL switches between them).
-- ---------------------------------------------------------------------------
create role anon            nologin;
create role authenticated   nologin;
create role service_role    nologin bypassrls;

-- ---------------------------------------------------------------------------
-- 2. The auth schema and the two functions every policy uses.
--    Faithfulness note: auth.jwt()/auth.uid() read `request.jwt.claims`
--    (the GUC PostgREST sets per request) — the same source of truth the
--    real ones use.
-- ---------------------------------------------------------------------------
create schema if not exists auth;

create table if not exists auth.users (
  id                 uuid primary key,
  aud                text,
  role               text,
  email              text unique,
  encrypted_password text,
  raw_app_meta_data  jsonb not null default '{}'::jsonb,
  raw_user_meta_data jsonb not null default '{}'::jsonb,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create or replace function auth.jwt()
returns jsonb
language sql
stable
as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb;
$$;

create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select (coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb ->> 'sub')::uuid;
$$;

create or replace function auth.role()
returns text
language sql
stable
as $$
  select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb ->> 'role';
$$;

-- ---------------------------------------------------------------------------
-- 3. Default grants an authenticated/anon key would get on the platform.
--    Declared for all FUTURE objects created by postgres (the role every
--    migration file runs as in CI), so each fresh table/function is covered.
-- ---------------------------------------------------------------------------
grant usage on schema public, auth to anon, authenticated, service_role;

alter default privileges for role postgres in schema public
  grant select, insert, update, delete on tables
  to anon, authenticated, service_role;

alter default privileges for role postgres in schema public
  grant usage, select on sequences
  to anon, authenticated, service_role;

alter default privileges for role postgres in schema public
  grant execute on functions
  to anon, authenticated, service_role;
