-- ============================================================================
-- supabase_ai_quotebank.sql — the AI helper's shared quote bank, and the
-- global daily cap on provider calls.
--
-- WHY: free OpenRouter models allow roughly 50 requests a DAY for the whole
-- account. One call per user per day would mean the 51st user gets nothing,
-- and the helper would look broken at random. So the motivational line is
-- generated ONCE per day for everyone:
--
--   * the first request of a day asks the model for ~30 short quotes in a
--     single call, validates them, and stores the ones that pass;
--   * every other user that day is served from the table — zero provider calls.
--
-- No pg_cron, no scheduled job: the bank is built lazily by whoever asks first.
--
-- RUN ORDER: after supabase_ai_migration.sql (which creates ai_coach_usage).
-- Idempotent. Rollback: supabase_ai_quotebank_rollback.sql.
-- ============================================================================

-- ---------------------------------------------------------------- quote bank
create table if not exists public.ai_quotes (
  id           bigint generated always as identity primary key,
  for_date     date        not null default current_date,
  quote        text        not null,
  created_at   timestamptz not null default now()
);

-- One row per quote per day is enough; the function never inserts duplicates
-- for the same day, but the index makes that cheap to check and keeps the
-- "today's quotes" read a single index scan.
create index if not exists ai_quotes_for_date_idx on public.ai_quotes (for_date);

alter table public.ai_quotes enable row level security;

drop policy if exists "authenticated can read today's quotes" on public.ai_quotes;
create policy "authenticated can read today's quotes"
  on public.ai_quotes
  for select to authenticated
  using (true);

-- Writing is deliberately left to the service role only: NO insert/update/
-- delete policy exists, so no signed-in user — not even a manager — can put
-- text into the bank. The Edge Function is the only writer, and it validates
-- every quote before it lands. A user-writable quote bank is a prompt-injection
-- surface: the text is shown to every other user on their home screen.

comment on table public.ai_quotes is
  'AI-generated motivational quotes, ~30 per day, validated by the ai-coach '
  'Edge Function. Readable by signed-in users; writable by the service role '
  'only.';

-- ------------------------------------------------------- global daily usage
-- Counts provider calls made by the whole project today, across all users.
-- RLS on with no policies = nobody can read or reset it from the client; only
-- the function's service role can. This is the cap that keeps the account under
-- the free tier's ~50/day.
create table if not exists public.ai_provider_calls (
  day   date primary key default current_date,
  calls int  not null default 0
);

alter table public.ai_provider_calls enable row level security;

comment on table public.ai_provider_calls is
  'Provider calls made by the whole project today. Service-role only: a rate '
  'limit that users can read or reset is not a rate limit.';
