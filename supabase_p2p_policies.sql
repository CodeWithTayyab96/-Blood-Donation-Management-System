-- Blood Donation — P2P Row Level Security policies (Phase 2).
-- Run ONCE in the Supabase SQL editor, AFTER supabase_p2p_migration.sql.
--
-- ⚠️  READ THIS FIRST
--
-- 1. This is the ENFORCING step. The migration alone breaks nothing; this is
--    what starts rejecting writes from older clients. See DESIGN_P2P.md §5
--    for the safe rollout order (deploy the new APK BEFORE applying this).
--
-- 2. This file SUPERSEDES supabase_role_policies.sql. It redefines the
--    `requests` write policy and adds policies for the three new tables.
--    The donors / donations / stock policies from that file stay as they are.
--
-- 3. Every policy below is `to authenticated`. The anon key that ships inside
--    every APK therefore gets NO policy and is denied on all P2P tables —
--    that is deliberate, and it is what keeps `contact_details` unreadable
--    by anyone holding just the anon key.
--
-- 4. Requires public.is_manager() from supabase_role_policies.sql. If you
--    never applied that file, uncomment the helper in section 0 below.
--
-- 5. Test on a throwaway project first. A miswritten policy here can lock
--    everyone out of their own data.

-- ---------------------------------------------------------------------------
-- 0. Helper (skip if supabase_role_policies.sql was already applied)
-- ---------------------------------------------------------------------------
create or replace function public.is_manager()
returns boolean
language sql
stable
as $$
  select coalesce((auth.jwt() -> 'app_metadata' ->> 'role') = 'MANAGER', false);
$$;

grant execute on function public.is_manager() to authenticated;

-- ---------------------------------------------------------------------------
-- 1. Enable RLS on the new tables and grant the basics
-- ---------------------------------------------------------------------------
alter table offers          enable row level security;
alter table contact_details enable row level security;
alter table reports         enable row level security;

grant select, insert, update, delete on offers          to authenticated;
grant select, insert, update          on contact_details to authenticated;
grant select, insert                  on reports         to authenticated;

-- ---------------------------------------------------------------------------
-- 2. requests — anyone signed in can read; only the owner or a manager writes
-- ---------------------------------------------------------------------------
-- Replaces the manager-only write policy from supabase_role_policies.sql.
drop policy if exists "requests write manager" on requests;
drop policy if exists "requests insert owner or manager" on requests;
drop policy if exists "requests update owner or manager" on requests;
drop policy if exists "requests delete owner or manager" on requests;

create policy "requests insert owner or manager" on requests
  for insert to authenticated
  with check (requester_id = auth.uid() or is_manager());

-- Cancelling is an UPDATE to status, so the owner needs update, not delete.
create policy "requests update owner or manager" on requests
  for update to authenticated
  using (requester_id = auth.uid() or is_manager())
  with check (requester_id = auth.uid() or is_manager());

-- Hard delete stays manager-only: a user cancels (updates), they do not erase.
create policy "requests delete manager" on requests
  for delete to authenticated using (is_manager());

-- ---------------------------------------------------------------------------
-- 3. offers — donor owns theirs, requester sees offers on their own request
-- ---------------------------------------------------------------------------
create policy "offers read" on offers
  for select to authenticated
  using (
    donor_auth_id = auth.uid()
    or request_id in (select id from requests where requester_id = auth.uid())
    or is_manager()
  );

-- Insert requires the donor to have a profile AND to have switched
-- "I'm available to donate" on. A donor who is not eligible cannot offer.
create policy "offers insert" on offers
  for insert to authenticated
  with check (
    donor_auth_id = auth.uid()
    and exists (
      select 1 from donors
       where auth_id = auth.uid()
         and available_to_donate = true
    )
  );

-- The donor updates their own offer (decline, re-offer, confirm);
-- the requester updates offers on their own request (accept, decline, confirm).
create policy "offers update donor own" on offers
  for update to authenticated
  using (donor_auth_id = auth.uid())
  with check (donor_auth_id = auth.uid());

create policy "offers update requester own" on offers
  for update to authenticated
  using (request_id in (select id from requests where requester_id = auth.uid()))
  with check (request_id in (select id from requests where requester_id = auth.uid()));

-- ---------------------------------------------------------------------------
-- 4. contact_details — THE ONLY PATH THAT REVEALS A PHONE NUMBER
-- ---------------------------------------------------------------------------
-- Readable by: the owner, the manager, or the other party on an
-- Accepted/Completed offer. Nothing else. This is enforced by the database,
-- not by the app UI.
create policy "contact_details read own manager or accepted party" on contact_details
  for select to authenticated
  using (
    user_id = auth.uid()
    or is_manager()
    or exists (
      select 1
        from offers o
        join requests r on r.id = o.request_id
        join donors d   on d.id = o.donor_id
       where o.status in ('Accepted', 'Completed')
         and (
           -- I am the requester, reading the donor who offered to help
           (r.requester_id = auth.uid() and d.auth_id = contact_details.user_id)
           or
           -- I am the donor, reading the requester I am helping
           (d.auth_id = auth.uid() and r.requester_id = contact_details.user_id)
         )
    )
  );

-- A user can only ever write their OWN phone number.
create policy "contact_details insert own" on contact_details
  for insert to authenticated
  with check (user_id = auth.uid());

create policy "contact_details update own" on contact_details
  for update to authenticated
  using (user_id = auth.uid())
  with check (user_id = auth.uid());

-- NOTE: there is deliberately NO delete policy. Removing your phone should
-- set it blank, not delete the row, so the audit of who could see it stays
-- intact; a manager can delete rows directly in the table editor if needed.

-- ---------------------------------------------------------------------------
-- 5. donors — a donor may switch their own availability
-- ---------------------------------------------------------------------------
-- The existing "donors update own" policy (from supabase_role_policies.sql)
-- already lets a donor update their own row. RLS is ROW-level, so it cannot
-- itself restrict WHICH COLUMNS change — that is the same reason the phone
-- lives in its own table. The app only ever writes `available_to_donate`
-- from the donor side; a manager edits the rest.
--
-- To lock the other columns down at the database level too, revoke the
-- column-level UPDATE privilege (optional hardening):
--
--   revoke update (name, age, gender, blood_group, phone, city, weight,
--                  last_donation_date, auth_id) on donors from authenticated;
--   grant  update (available_to_donate) on donors to authenticated;
--
-- Do this only after checking nothing else in your setup updates donors as
-- an authenticated (non-manager) user.

-- ---------------------------------------------------------------------------
-- 6. reports — anyone can file one; only the manager reads them
-- ---------------------------------------------------------------------------
create policy "reports insert" on reports
  for insert to authenticated
  with check (reporter_id = auth.uid() or reporter_id is null);

create policy "reports read manager" on reports
  for select to authenticated using (is_manager());

-- ---------------------------------------------------------------------------
-- 7. Verify the important one (must return ZERO rows / permission denied
--    when using the anon key, and your own row when signed in):
--
--      select * from contact_details;
--
--    And confirm the anon key cannot read offers either. If anon can read
--    either table, RLS is not enabled on it — re-run section 1.
-- ---------------------------------------------------------------------------
