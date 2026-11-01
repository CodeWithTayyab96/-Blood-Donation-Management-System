-- ============================================================================
-- supabase_delete_account_rollback.sql — removes the delete-account function.
--
-- It cannot undo a deletion: rows that were already removed are gone, and the
-- anonymised donor row cannot be turned back into a real person. It only stops
-- the function from existing, so the Edge Function fails closed (HTTP 500,
-- "data deletion failed") instead of deleting anything.
--
-- Re-run supabase_delete_account.sql to put it back.
-- ============================================================================

drop function if exists public.delete_account_data(uuid);
