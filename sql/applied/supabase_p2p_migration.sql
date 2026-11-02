-- Blood Donation — P2P migration (Phase 2).
-- Run ONCE in the Supabase SQL editor, AFTER supabase_migration.sql and
-- supabase_auth_migration.sql.
--
-- ⚠️  READ THIS FIRST
--
-- 1. Everything here is ADDITIVE: new columns on requests/donors, and three
--    new tables (offers, contact_details, reports). No existing row is
--    dropped or rewritten, so an older APK keeps working against the new
--    schema (it just cannot see offers).
--
-- 2. Run this BEFORE supabase_p2p_policies.sql. The policies are what
--    actually start rejecting old clients; this file alone breaks nothing.
--
-- 3. Take a backup of the project first (Dashboard → Database → Backups).
--
-- 4. `donors.auth_id` is a uuid (added by supabase_auth_migration.sql). The
--    new requester_id / donor_auth_id / user_id columns are uuid to match.

-- ---------------------------------------------------------------------------
-- 1. requests: who asked, where, how it was fulfilled, by when
-- ---------------------------------------------------------------------------
alter table requests add column if not exists requester_id uuid;
alter table requests add column if not exists city text;
alter table requests add column if not exists fulfilled_by text
  check (fulfilled_by in ('stock', 'donor'));
alter table requests add column if not exists needed_by date;

-- Legacy / manager-created rows keep requester_id = null, which the policies
-- below treat as "manager-owned".

-- ---------------------------------------------------------------------------
-- 2. donors: can this donor be contacted by peers right now?
-- ---------------------------------------------------------------------------
alter table donors add column if not exists available_to_donate boolean
  not null default false;

-- ---------------------------------------------------------------------------
-- 3. offers — a donor's offer to fulfil a request
-- ---------------------------------------------------------------------------
create table if not exists offers (
  id bigint generated always as identity primary key,
  request_id bigint not null references requests(id) on delete cascade,
  donor_id bigint not null references donors(id) on delete cascade,
  donor_auth_id uuid,
  donor_name text,
  donor_group text,
  donor_city text,
  status text not null default 'Offered'
    check (status in ('Offered', 'Accepted', 'Declined', 'Completed')),
  donor_confirmed_at timestamptz,
  requester_confirmed_at timestamptz,
  created_at timestamptz not null default now()
);

-- One offer per (donor, request): re-offering updates the existing row.
create unique index if not exists offers_request_donor_key
  on offers (request_id, donor_id);

-- At most ONE accepted offer per request (partial index). This is what stops
-- two donors from both being accepted on the same request.
create unique index if not exists offers_one_accepted_per_request
  on offers (request_id) where status = 'Accepted';

-- ---------------------------------------------------------------------------
-- 4. contact_details — a donor's phone, kept OUT of `donors`
-- ---------------------------------------------------------------------------
-- Row Level Security hides ROWS, not COLUMNS. A phone stored on `donors` is
-- visible to everyone who can read that donor row (which is every signed-in
-- user), or hidden from the owner too. Splitting it into its own table is the
-- only way to make it readable by exactly: the owner, the manager, and the
-- other party on an Accepted/Completed offer.
create table if not exists contact_details (
  user_id uuid primary key,
  phone text not null
);

-- Copy existing donor phones across, but ONLY for donors that have an Auth
-- account. The 5 seeded demo donors have auth_id = null: they are manager
-- data, not peer accounts, so their phones deliberately stay out of this
-- table (and `donors.phone` is untouched, so nothing is lost).
insert into contact_details (user_id, phone)
select auth_id, phone from donors
 where auth_id is not null
   and phone is not null
on conflict (user_id) do nothing;

-- ---------------------------------------------------------------------------
-- 5. reports — spam / fake / abuse reports against a request
-- ---------------------------------------------------------------------------
create table if not exists reports (
  id bigint generated always as identity primary key,
  request_id bigint not null references requests(id) on delete cascade,
  reporter_id uuid,
  reason text not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

-- ---------------------------------------------------------------------------
-- 6. The hard 3-open-requests cap (RLS cannot count cheaply)
-- ---------------------------------------------------------------------------
create or replace function public.enforce_open_request_cap()
returns trigger
language plpgsql
as $$
declare
  v_open int;
begin
  -- Manager-created rows have no requester and are exempt.
  if new.requester_id is null then
    return new;
  end if;

  select count(*) into v_open
    from requests
   where requester_id = new.requester_id
     and status not in ('Fulfilled', 'Cancelled', 'Expired');

  if v_open >= 3 then
    raise exception
      'Open request limit reached: at most 3 open requests per user';
  end if;

  return new;
end;
$$;

drop trigger if exists requests_open_cap on requests;
create trigger requests_open_cap
  before insert on requests
  for each row execute function public.enforce_open_request_cap();

-- ---------------------------------------------------------------------------
-- 7. Atomic "both sides confirmed" completion
-- ---------------------------------------------------------------------------
-- Without this the app would do 4 separate calls (donation row, donor's
-- last-donation date, offer status, request status) and a failure between
-- them leaves the two sides disagreeing. This runs them in ONE transaction.
--
-- It deliberately does NOT touch `stock`: a peer donation is a private
-- arrangement between two people and must not alter the blood bank's
-- inventory (see DESIGN_P2P.md §6). Only a manager's stock fulfilment does.
create or replace function public.complete_peer_donation(p_offer_id bigint)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_offer offers%rowtype;
  v_today date := current_date;
begin
  select * into v_offer from offers where id = p_offer_id for update;

  if not found then return false; end if;
  if v_offer.status <> 'Accepted' then return false; end if;
  -- Both sides must have confirmed before anything is recorded.
  if v_offer.donor_confirmed_at is null
     or v_offer.requester_confirmed_at is null then
    return false;
  end if;

  insert into donations (donor_id, date, units)
  values (v_offer.donor_id, v_today, 1);

  update donors
     set last_donation_date = v_today
   where id = v_offer.donor_id;

  update offers set status = 'Completed' where id = p_offer_id;

  update requests
     set status = 'Fulfilled', fulfilled_by = 'donor'
   where id = v_offer.request_id;

  return true;
end;
$$;

grant execute on function public.complete_peer_donation(bigint) to authenticated;

-- ---------------------------------------------------------------------------
-- 8. Refuse a duplicate donation for the same donor on the same day
-- ---------------------------------------------------------------------------
-- This is the backstop for the concurrent both-confirm race: if two calls
-- land at once, the second insert is refused instead of recording twice.
-- Skipped (with a notice) if your data already contains duplicates — clean
-- those up first, then re-run just this block.
do $$
begin
  if exists (
    select 1 from donations group by donor_id, date having count(*) > 1
  ) then
    raise notice 'Skipped donations_donor_date_key: duplicate (donor_id, date) rows already exist. De-duplicate them, then re-run this block.';
  else
    create unique index if not exists donations_donor_date_key
      on donations (donor_id, date);
  end if;
end $$;

-- ---------------------------------------------------------------------------
-- Next: apply supabase_p2p_policies.sql (the enforcing step).
-- ---------------------------------------------------------------------------
