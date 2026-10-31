-- Rollback for supabase_ai_migration.sql. Also resets everyone's rate-limit
-- counters once the table is gone (which is the point — a future fresh
-- table starts fresh).
drop table if exists ai_coach_usage;
