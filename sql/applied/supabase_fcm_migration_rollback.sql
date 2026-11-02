-- Rollback for supabase_fcm_migration.sql. Run in the SQL editor when you
-- want to remove push support entirely (also consider the policies rollback
-- file — dropping the table removes its policies with it; the explicit drops
-- here are just tidy).
drop table if exists device_tokens;
