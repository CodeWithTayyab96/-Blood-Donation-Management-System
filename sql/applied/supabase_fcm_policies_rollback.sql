-- Rollback for supabase_fcm_policies.sql. After this the table still exists
-- but reads/writes are denied to everyone except the service role.
drop policy if exists "device tokens own crud" on device_tokens;
drop policy if exists "device tokens read manager" on device_tokens;
