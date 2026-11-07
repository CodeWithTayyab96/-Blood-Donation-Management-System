-- Blood Donation — Phase 2 migration: request status timeline.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_p2p_policies.sql and
-- supabase_role_policies.sql (this file uses public.is_manager()) and after
-- supabase_fcm_triggers.sql if you want the Fulfilled push below to work.
--
-- ⚠️  Everything here is ADDITIVE: one new table, its policies and a trigger.
--     No existing row is rewritten and no existing policy is dropped, so an
--     older APK keeps working against the new schema.
--
-- 1. The timeline is APPEND-ONLY from the client's point of view: there is no
--    UPDATE or DELETE policy, so history cannot be silently rewritten.
-- 2. Status CHANGES on requests are already restricted by the existing
--    "requests update owner or manager" policy in supabase_p2p_policies.sql
--    (requester_id = auth.uid() or is_manager()). This migration does not
--    relax it. The SECURITY DEFINER RPCs bypass RLS by design, as before.
-- 3. The server trigger is authoritative: a status change made by any device
--    (or by an RPC) produces exactly one history row. Client inserts are a
--    best-effort offline fallback and are deduplicated by the app.

-- ---------------------------------------------------------------------------
-- 1. table
-- ---------------------------------------------------------------------------
create table if not exists public.request_status_history (
  id          bigint generated always as identity primary key,
  request_id  bigint not null references public.requests(id) on delete cascade,
  status      text not null
    check (status in ('Pending','Matched','Fulfilled','Cancelled','Expired')),
  changed_at  timestamptz not null default now(),
  -- A CODE, never a name or an Auth id: the app translates it for Urdu.
  actor       text
);

create index if not exists request_status_history_request_idx
  on public.request_status_history (request_id, changed_at, id);

alter table public.request_status_history enable row level security;

-- ---------------------------------------------------------------------------
-- 2. role-aware RLS policies (donor / requester and manager)
-- ---------------------------------------------------------------------------
-- A requester may read the history of THEIR OWN request; a manager may read
-- every request's history. The join is inside the policy so a donor cannot
-- enumerate other people's timelines.
drop policy if exists "status history read owner or manager" on public.request_status_history;
create policy "status history read owner or manager"
  on public.request_status_history
  for select to authenticated
  using (
    public.is_manager()
    or exists (
      select 1 from public.requests r
       where r.id = request_id and r.requester_id = auth.uid()
    )
  );

-- Only the requester (on their own request) or a manager may append a row.
-- The server trigger below is SECURITY DEFINER and therefore not subject to
-- this policy; it is what makes the history complete even for offline clients.
drop policy if exists "status history insert owner or manager" on public.request_status_history;
create policy "status history insert owner or manager"
  on public.request_status_history
  for insert to authenticated
  with check (
    public.is_manager()
    or exists (
      select 1 from public.requests r
       where r.id = request_id and r.requester_id = auth.uid()
    )
  );

-- No UPDATE / DELETE policy on purpose: append-only audit trail.

-- ---------------------------------------------------------------------------
-- 3. server-side history: one row per real status transition
-- ---------------------------------------------------------------------------
-- SECURITY DEFINER so an RPC-driven fulfilment (which bypasses RLS) still
-- writes the row. search_path is pinned, as in the other RPCs in this repo.
create or replace function public.log_request_status_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if new.status is distinct from old.status then
    insert into public.request_status_history(request_id, status, actor)
    values (new.id, new.status, 'server');
  end if;
  return null;
end;
$$;

drop trigger if exists trg_log_request_status on public.requests;
create trigger trg_log_request_status
  after update of status on public.requests
  for each row
  execute function public.log_request_status_change();

-- ---------------------------------------------------------------------------
-- 4. push the requester when their request is Fulfilled
-- ---------------------------------------------------------------------------
-- "A donor accepts" does not exist in this app's flow: the donor OFFERS and
-- the requester ACCEPTS. The existing push_after_offer_insert trigger already
-- notifies the requester about that new offer. This adds the other half the
-- brief asks for — a Fulfilled notification to the requester.
--
-- public.push_send() comes from supabase_fcm_triggers.sql. The guard below
-- makes the trigger a NO-OP when that file has not been applied: a missing
-- pg_net / push helper must never roll back the status update itself.
create or replace function public.on_request_fulfilled()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  if new.requester_id is null then
    return null;
  end if;
  if to_regprocedure('public.push_send(jsonb)') is null then
    return null;
  end if;
  perform public.push_send(jsonb_build_object(
    'type', 'fulfilled',
    'request_id', new.id,
    'requester_id', new.requester_id,
    -- v1.19: fulfilled_by ('stock' or 'donor') so the notification can
    -- say who fulfilled it; without it every Fulfilled read as "donors".
    'fulfilled_by', new.fulfilled_by
  ));
  return null;
end;
$$;

drop trigger if exists push_after_request_fulfilled on public.requests;
create trigger push_after_request_fulfilled
  after update of status on public.requests
  for each row
  when (new.status = 'Fulfilled' and old.status is distinct from 'Fulfilled')
  execute function public.on_request_fulfilled();
