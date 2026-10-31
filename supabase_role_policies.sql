-- Blood Donation — role-aware Row Level Security.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_auth_migration.sql and
-- supabase_auth_policies.sql.
--
-- ⚠️  READ THIS FIRST
--
-- 1. This REPLACES the "Authenticated full access" policies with per-role ones.
--    Until it is applied, every signed-in user can write every row — that is
--    the C5 finding. Applying it is what actually enforces the manager/donor
--    split; the app's Session.isManager check is only a UI guard.
--
-- 2. The manager role lives in the Auth user's `app_metadata`, NOT in the
--    local SQLite row. Step 1 below sets it. Run step 1 FIRST and verify it
--    before running the policies — if no user has the claim, every account
--    becomes read-only and the app cannot add donors, record donations or
--    fulfil requests.
--
-- 3. Existing sessions carry the OLD token. After applying this, sign out and
--    sign back in (or wait for the access token to refresh) so the new JWT
--    contains the role claim. A token issued before step 1 has no
--    app_metadata.role and is therefore treated as a donor.
--
-- 4. This file is NOT applied automatically and the app does not depend on it
--    being present. Do not run it until you are ready to test the write paths.

-- ---------------------------------------------------------------------------
-- STEP 1 — mark the manager account. Run this and check the result first.
-- ---------------------------------------------------------------------------
update auth.users
   set raw_app_meta_data =
         coalesce(raw_app_meta_data, '{}'::jsonb) || jsonb_build_object('role', 'MANAGER')
 where email = 'manager@blood.local';

-- Verify (expect exactly one row with role = MANAGER):
--   select email, raw_app_meta_data -> 'role' as role
--     from auth.users where email = 'manager@blood.local';

-- ---------------------------------------------------------------------------
-- STEP 2 — helper + policies.
-- ---------------------------------------------------------------------------

create or replace function public.is_manager()
returns boolean
language sql
stable
as $$
  select coalesce((auth.jwt() -> 'app_metadata' ->> 'role') = 'MANAGER', false);
$$;

grant execute on function public.is_manager() to authenticated;

-- Drop the permissive v1.4 policies this file replaces.
drop policy if exists "Authenticated full access" on donors;
drop policy if exists "Authenticated full access" on donations;
drop policy if exists "Authenticated full access" on requests;
drop policy if exists "Authenticated full access" on stock;

-- donors -------------------------------------------------------------------
-- Everyone signed in can read the donor list (the app's whole point is
-- matching compatible donors to a request).
create policy "donors read" on donors
  for select to authenticated using (true);

create policy "donors insert manager" on donors
  for insert to authenticated with check (is_manager());

create policy "donors update manager" on donors
  for update to authenticated using (is_manager()) with check (is_manager());

-- A donor may edit their own profile. `with check` also stops them moving the
-- row to another auth_id.
create policy "donors update own" on donors
  for update to authenticated
  using (auth_id = auth.uid())
  with check (auth_id = auth.uid());

create policy "donors delete manager" on donors
  for delete to authenticated using (is_manager());

-- donations ----------------------------------------------------------------
create policy "donations read" on donations
  for select to authenticated using (true);

-- Managers record donations for anyone; a donor may only record their own.
create policy "donations insert" on donations
  for insert to authenticated
  with check (
    is_manager()
    or donor_id in (select id from donors where auth_id = auth.uid())
  );

create policy "donations update manager" on donations
  for update to authenticated using (is_manager()) with check (is_manager());

create policy "donations delete manager" on donations
  for delete to authenticated using (is_manager());

-- requests -----------------------------------------------------------------
create policy "requests read" on requests
  for select to authenticated using (true);

create policy "requests write manager" on requests
  for all to authenticated using (is_manager()) with check (is_manager());

-- stock --------------------------------------------------------------------
create policy "stock read" on stock
  for select to authenticated using (true);

-- Writes go through public.adjust_stock() (see fix_stock_rpc.sql), which is
-- SECURITY DEFINER, so a donor's own donation can still move stock without
-- granting every donor a direct UPDATE on the table.
create policy "stock write manager" on stock
  for all to authenticated using (is_manager()) with check (is_manager());

-- ---------------------------------------------------------------------------
-- To undo: drop the policies above and re-create "Authenticated full access"
-- from supabase_auth_policies.sql.
-- ---------------------------------------------------------------------------
