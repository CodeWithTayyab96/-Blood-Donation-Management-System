-- ============================================================================
-- TEST-ONLY — supabase/ci/pg_net_stub.sql
-- NEVER run this on the live Supabase project.
--
-- Vanilla Postgres has no pg_net extension, and supabase_fcm_triggers.sql
-- calls net.http_post(). On the platform pg_net is installed as a real
-- extension (the migration file runs `create extension if not exists pg_net`,
-- which the CI job strips because a bare Postgres cannot install it).
--
-- This stub replaces that one function so the trigger bodies can be created
-- and EXECUTED in CI. It does nothing and delivers nothing — CI only proves
-- the SQL is valid and the triggers fire without error, NOT that a push
-- anywhere leaves the machine.
-- ============================================================================

create schema if not exists net;

create or replace function net.http_post(
  url                  text,
  body                 jsonb default null,
  params               jsonb default '{}'::jsonb,
  headers              jsonb default '{}'::jsonb,
  timeout_milliseconds integer default 5000
)
returns bigint
language plpgsql volatile
as $$
begin
  -- No-op: a real push test needs the Edge Function and Firebase, not SQL.
  return null;
end;
$$;
