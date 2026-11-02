-- Blood Donation — Phase 4: ai-coach rate limiting table.
-- Run ONCE in the Supabase SQL editor AFTER the Phase 2/3 files.
--
-- The ai-coach Edge Function (supabase/functions/ai-coach) tracks how many
-- times each user has called it today here, and answers 429 when a user is
-- over the daily limit. Additive and safe; undo with the rollback file.
--
-- Deliberately NO Row Level Security policies: RLS is enabled with no
-- policies, so NO user (not even a signed-in one, not the manager) can read
-- or write this table with their own token. Only the SERVICE ROLE — what the
-- Edge Function runs with — can. A rate-limit table must not be writable by
-- the users it limits; the function is the trusted layer.

create table if not exists ai_coach_usage (
  user_id uuid primary key references auth.users (id) on delete cascade,
  window_started_at timestamptz not null default now(),
  calls int not null default 0
);

alter table ai_coach_usage enable row level security;
