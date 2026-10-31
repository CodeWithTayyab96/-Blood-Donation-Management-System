-- Blood Donation — P2P migration ROLLBACK.
--
-- Use this to undo supabase_p2p_migration.sql if the rollout has to be
-- reversed. Run it in the Supabase SQL editor, in the order shown.
--
-- ⚠️  WHAT THIS DOES AND DOES NOT DESTROY
--
-- - It drops the three NEW tables (offers, contact_details, reports) and the
--   new columns. It does NOT touch donors, donations, requests rows, stock,
--   or auth.users.
-- - `donors.phone` was the SOURCE of the copy into contact_details and was
--   never altered, so dropping contact_details loses only the copy — every
--   donor phone still exists on `donors`.
-- - Dropping requests.requester_id / fulfilled_by / needed_by / city and
--   donors.available_to_donate removes P2P data that only the new APK can
--   produce. An already-installed new APK will then fall back to local SQLite
--   for those features.
-- - Nothing here is reversible by re-running the migration against data the
--   rollback deleted: offers, reports and contact_details rows are gone.
--   Take a backup before running this if any of that matters.

-- ---------------------------------------------------------------------------
-- 1. Triggers and functions first (they depend on the columns/tables below)
-- ---------------------------------------------------------------------------
drop trigger if exists requests_open_cap on requests;
drop function if exists public.enforce_open_request_cap();
drop function if exists public.complete_peer_donation(bigint);

-- ---------------------------------------------------------------------------
-- 2. Index created in step 8 of the migration
-- ---------------------------------------------------------------------------
drop index if exists donations_donor_date_key;

-- ---------------------------------------------------------------------------
-- 3. Drop the new tables (order matters: offers/reports reference requests)
-- ---------------------------------------------------------------------------
drop table if exists offers;
drop table if exists reports;
drop table if exists contact_details;

-- ---------------------------------------------------------------------------
-- 4. Drop the new columns
-- ---------------------------------------------------------------------------
alter table requests drop column if exists requester_id;
alter table requests drop column if exists city;
alter table requests drop column if exists fulfilled_by;
alter table requests drop column if exists needed_by;

alter table donors drop column if exists available_to_donate;

-- NOTE: `donors.auth_id` is NOT dropped here. It predates P2P (it belongs to
-- supabase_auth_migration.sql from v1.4) and removing it would unlink every
-- donor profile from its Supabase Auth account.

-- ---------------------------------------------------------------------------
-- 5. If you had already applied the policies
-- ---------------------------------------------------------------------------
-- supabase_p2p_policies.sql supersedes supabase_role_policies.sql. Rolling
-- back the schema does not restore the old policies; if you need the pre-P2P
-- behaviour back, re-apply supabase_role_policies.sql (or
-- supabase_auth_policies.sql) after this file.
--
-- After this rollback the database is back to its pre-P2P shape and the old
-- APK works exactly as before.
