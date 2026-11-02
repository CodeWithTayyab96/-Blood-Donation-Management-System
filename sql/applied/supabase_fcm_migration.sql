-- Blood Donation — Phase 3: device tokens for FCM push.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_p2p_migration.sql.
--
-- One row per registered device per signed-in Auth user. The push-notify
-- Edge Function (supabase/functions/push-notify) reads these with the
-- service role to ask Firebase to send a message; the app writes its own
-- token at sign-in and whenever Firebase rotates it.
--
-- Additive and safe: nothing here touches rows from earlier phases. Run
-- supabase_fcm_migration_rollback.sql to undo all of it.
--
-- Why a fresh 45-day row matters: a token that never updates can hang the
-- table intact; stale tokens (uninstalled app) are filtered out at read
-- time by updated_at, so nothing needs to be deleted eagerly.

-- ---------------------------------------------------------------------------
-- 1. The table
-- ---------------------------------------------------------------------------
create table if not exists device_tokens (
  id bigint generated always as identity primary key,
  -- Which signed-in user is reachable on this device. Requests and offers
  -- notify by Auth user id, so every signed-in role (donor AND a plain
  -- requester with no donor profile) stores tokens here.
  user_id uuid not null references auth.users (id) on delete cascade,
  -- The FCM registration token itself. Unique so one device = one row no
  -- matter how many times the app re-uploads it (the client upserts by it).
  token text not null unique,
  platform text not null default 'android',
  updated_at timestamptz not null default now()
);

-- The Edge Function filters out tokens untouched for 45 days; this keeps
-- that read reasonable.
create index if not exists device_tokens_user_updated
  on device_tokens (user_id, updated_at desc);

-- ---------------------------------------------------------------------------
-- 2. Enable RLS now. The policies live in supabase_fcm_policies.sql.
--    Enabling RLS without policies denies everything to anon key holders
--    immediately, which is the safe default while nothing is applied yet.
-- ---------------------------------------------------------------------------
alter table device_tokens enable row level security;
