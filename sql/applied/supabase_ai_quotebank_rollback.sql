-- ============================================================================
-- supabase_ai_quotebank_rollback.sql — undo the quote bank and the global cap.
--
-- This drops the two tables and therefore every stored quote. Quotes are
-- regenerated on the next request, so nothing permanent is lost; the only
-- effect is one extra provider call the next time someone opens the app.
--
-- It does NOT undo supabase_ai_migration.sql (ai_coach_usage), and it does not
-- remove the Edge Function — deploy ai-coach again to go back to a version that
-- never reads these tables.
-- ============================================================================

drop policy if exists "authenticated can read today's quotes" on public.ai_quotes;
drop index if exists public.ai_quotes_for_date_idx;
drop table if exists public.ai_quotes;
drop table if exists public.ai_provider_calls;

-- After this, supabase_verify_policies.sql will report the two tables as
-- missing if you have also added their checks — that is the expected state
-- while the rollback is in place.
