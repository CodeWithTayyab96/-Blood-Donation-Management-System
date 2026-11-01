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
 where email = 'tayyabuq1@gmail.com';

-- Verify (expect exactly one row with role = MANAGER):
--   select email, raw_app_meta_data -> 'role' as role
--     from auth.users where email = 'tayyabuq1@gmail.com';

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

-- Also drop this file's OWN policies first: the file is deliberately safe to
-- re-run (the CREATE POLICY statements below fail if the policy already
-- exists, so a re-run — e.g. after its own rollback or a changed claim —
-- would otherwise stop half-way).
drop policy if exists "donors read" on donors;
drop policy if exists "donors insert manager" on donors;
drop policy if exists "donors insert own" on donors;
drop policy if exists "donors update manager" on donors;
drop policy if exists "donors update own" on donors;
drop policy if exists "donors delete manager" on donors;
drop policy if exists "donations read" on donations;
drop policy if exists "donations insert" on donations;
drop policy if exists "donations update manager" on donations;
drop policy if exists "donations delete manager" on donations;
drop policy if exists "requests read" on requests;
drop policy if exists "requests write manager" on requests;
drop policy if exists "stock read" on stock;
drop policy if exists "stock write manager" on stock;

-- donors -------------------------------------------------------------------
-- Everyone signed in can read the donor list (the app's whole point is
-- matching compatible donors to a request).
create policy "donors read" on donors
  for select to authenticated using (true);

create policy "donors insert manager" on donors
  for insert to authenticated with check (is_manager());

-- A new donor creates their own profile at registration, and the app sends
-- auth_id = their own Auth user id. This is the only self-service insert: it
-- cannot create a row for somebody else, and a payload with a null auth_id
-- (or no Auth id at all) is refused, because `auth_id = auth.uid()` is then
-- NULL, not true. Without this policy, registration's profile insert is
-- rejected and the donor can never offer to help.
create policy "donors insert own" on donors
  for insert to authenticated with check (auth_id = auth.uid());

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

-- Requests: until the peer-to-peer policies are applied, only the manager may
-- write them. supabase_p2p_policies.sql replaces this with owner-or-manager
-- policies AND drops this one, so creating it unconditionally would make the
-- final policy list depend on which of the two files ran last. It is therefore
-- created only while the P2P policies are absent: role-then-p2p and
-- p2p-then-role both end in the same state, and either file can be re-run.
do $policy$
begin
  if not exists (
    select 1
      from pg_policies
     where schemaname = 'public'
       and tablename  = 'requests'
       and policyname = 'requests insert owner or manager'
  ) then
    execute 'create policy "requests write manager" on requests '
         || 'for all to authenticated using (is_manager()) with check (is_manager())';
  end if;
end $policy$;

-- stock --------------------------------------------------------------------
create policy "stock read" on stock
  for select to authenticated using (true);

-- Writes go through public.adjust_stock() (see fix_stock_rpc.sql), which is
-- SECURITY DEFINER, so a donor's own donation can still move stock without
-- granting every donor a direct UPDATE on the table.
create policy "stock write manager" on stock
  for all to authenticated using (is_manager()) with check (is_manager());

-- ---------------------------------------------------------------------------
-- STEP 3 — column privileges: a phone number is not a column everyone may read
-- ---------------------------------------------------------------------------
-- RLS is row-level, so it cannot hide one column of a row it lets you read.
-- Both phone numbers in this schema therefore come out of the SELECT grant:
--
--   donors.phone     the donor's personal number
--   requests.contact the number a requester typed on their request
--
-- The app reads donors/requests with an explicit column list that omits both,
-- and asks the SECURITY DEFINER helpers at the bottom of this file when it
-- needs the number: a manager may read any, a user may read their own.
--
-- INSERT is untouched, so registration and the manager's "add donor" still
-- write the phone; only reading it back is restricted. PostgREST is called
-- with ?select=id on inserts and return=minimal on updates, so no query ever
-- asks for the hidden columns implicitly.
revoke select on public.donors   from authenticated;
revoke select on public.requests from authenticated;

grant select (id, name, age, gender, blood_group, city, weight,
              last_donation_date, created_at, auth_id)
  on public.donors to authenticated;

-- Two columns arrive later than this file: donors.available_to_donate and the
-- four P2P columns on requests come from supabase_p2p_migration.sql. A grant
-- naming a column that does not exist aborts the file, so those are granted
-- only when present — here, and again (unconditionally) in
-- supabase_p2p_policies.sql, which always runs after that migration. Either
-- order therefore ends with every column readable.
grant select (id, patient_name, hospital, blood_group, units_needed, urgency,
              status, created_at)
  on public.requests to authenticated;

do $grants$
declare
  cols text;
begin
  select string_agg(quote_ident(c), ', ')
    into cols
    from unnest(array['available_to_donate']) as c
   where exists (
     select 1 from information_schema.columns
      where table_schema = 'public' and table_name = 'donors' and column_name = c
   );
  if cols is not null then
    execute 'grant select (' || cols || ') on public.donors to authenticated';
  end if;

  select string_agg(quote_ident(c), ', ')
    into cols
    from unnest(array['requester_id', 'city', 'fulfilled_by', 'needed_by']) as c
   where exists (
     select 1 from information_schema.columns
      where table_schema = 'public' and table_name = 'requests' and column_name = c
   );
  if cols is not null then
    execute 'grant select (' || cols || ') on public.requests to authenticated';
  end if;
end $grants$;

-- The donor's own phone, or any donor's phone for a manager; NULL otherwise.
create or replace function public.donor_phone(p_donor_id bigint)
returns text
language sql
stable
security definer
set search_path = public
as $$
  select d.phone
    from public.donors d
   where d.id = p_donor_id
     and (public.is_manager() or d.auth_id = auth.uid());
$$;

-- The number typed on a request, for its requester or a manager; NULL
-- otherwise (the app then says the contact is shared after an offer is
-- accepted, which is when contact_details opens up).
--
-- plpgsql with one fixed statement on purpose: a SQL-language function is
-- parsed and column-checked when it is created, and `requests.requester_id`
-- only exists once supabase_p2p_migration.sql has run. This body is checked
-- on first call instead, so the file applies in either order. The statement
-- is a constant string; the id is a bound parameter.
create or replace function public.request_contact(p_request_id bigint)
returns text
language plpgsql
stable
security definer
set search_path = public
as $$
declare
  v_contact text;
begin
  execute
    'select r.contact from public.requests r
      where r.id = $1
        and (public.is_manager() or r.requester_id = auth.uid())'
    into v_contact
    using p_request_id;
  return v_contact;
end;
$$;

revoke all on function public.donor_phone(bigint)     from public;
revoke all on function public.request_contact(bigint) from public;
grant execute on function public.donor_phone(bigint)     to authenticated;
grant execute on function public.request_contact(bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- To undo: drop the policies above and re-create "Authenticated full access"
-- from supabase_auth_policies.sql. That does NOT undo the column grants —
-- restore them with:
--   grant select on public.donors, public.requests to authenticated;
--   drop function public.donor_phone(bigint);
--   drop function public.request_contact(bigint);
-- ---------------------------------------------------------------------------
