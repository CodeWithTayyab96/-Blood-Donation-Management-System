-- Blood Donation — Phase 3: Row Level Security for device_tokens.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_fcm_migration.sql.
--
-- What each signed-in user may do:
--   * insert / update / delete ONLY their own rows (the upsert their app
--     does at sign-in, and the delete on sign-out);
--   * read ONLY their own rows — a user MUST NOT be able to read someone
--     else's tokens and send pushes impersonating them;
--   * the manager (app_metadata role = MANAGER) may read all tokens, so a
--     future moderation screen can be built without schema changes.
--
-- The anon key (what ships inside every APK and what the push triggers use
-- as their identity into PostgREST) gets NO policy here: the triggers talk
-- only to the Edge Function, which runs with the service role and ignores
-- RLS by design.

-- Requires public.is_manager() from supabase_role_policies.sql (already the
-- Phase 2 dependency). If it is missing, create it before this file:
-- create or replace function public.is_manager() returns boolean
-- language sql stable as $$
--   select coalesce((auth.jwt() -> 'app_metadata' ->> 'role') = 'MANAGER', false);
-- $$;

create policy "device tokens own crud" on device_tokens
  for all to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

create policy "device tokens read manager" on device_tokens
  for select to authenticated
  using (is_manager());
