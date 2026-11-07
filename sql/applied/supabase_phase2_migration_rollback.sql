-- Rollback for supabase_phase2_migration.sql. Run ONLY if you need to undo
-- Phase 2. Drops the triggers, functions, policies and the history table.
-- The requests / offers tables and their existing policies are untouched.
drop trigger if exists push_after_request_fulfilled on public.requests;
drop trigger if exists trg_log_request_status on public.requests;
drop function if exists public.on_request_fulfilled();
drop function if exists public.log_request_status_change();
drop policy if exists "status history insert owner or manager" on public.request_status_history;
drop policy if exists "status history read owner or manager" on public.request_status_history;
drop table if exists public.request_status_history;
