-- ============================================================================
-- supabase_privacy_rollback.sql — undoes ONLY the phone-column restrictions.
--
-- When to use it: the column privileges broke an app build older than 1.7.
-- That build asks PostgREST for `select=*` on donors and requests, which the
-- restricted grant rejects with 42501, so it falls back to its local copy.
-- Installing the 1.7 APK is the real fix; this file is the stop-gap when you
-- cannot do that yet.
--
-- What it does: gives `authenticated` table-level SELECT back on the two
-- tables, so `phone` and `contact` are readable again exactly as before.
--
-- What it does NOT do: it does not touch the policies, the manager/donor
-- split, or the two helper functions. Those are not what breaks an old build,
-- and undoing them would remove the rest of the protection with it.
--
-- To re-apply the restrictions afterwards: re-run supabase_role_policies.sql.
-- It is idempotent, and it re-grants the explicit column lists.
--
-- ⚠️  While this is applied, EVERY signed-in account can read EVERY donor's
--     phone number and EVERY request's contact number — through the API, not
--     only the manager. supabase_verify_policies.sql will say so, with two
--     PROBLEM rows; that is the state you asked for, not a fault.
-- ============================================================================

grant select on public.donors   to authenticated;
grant select on public.requests to authenticated;

-- Optional: remove the helpers too, so nothing can call them. Harmless while
-- the grants are open, which is why they are left in place by default — the
-- 1.7 app calls them on two screens and copes with NULL.
--
-- drop function if exists public.donor_phone(bigint);
-- drop function if exists public.request_contact(bigint);

-- Verify the rollback took effect (expect 2 rows, both "can still SELECT"):
--
--   select 'donors.phone' as col
--    where has_column_privilege('authenticated', 'public.donors', 'phone', 'SELECT')
--   union all
--   select 'requests.contact'
--    where has_column_privilege('authenticated', 'public.requests', 'contact', 'SELECT');
