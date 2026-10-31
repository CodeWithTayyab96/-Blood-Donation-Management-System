-- Blood Donation — Phase 3: database triggers that ask the push-notify Edge
-- Function to send FCM pushes. Run ONCE in the Supabase SQL editor AFTER
-- supabase_fcm_migration.sql (tokens table) and the P2P migration/policies.
--
-- ⚠️  REPLACE THE PLACEHOLDERS FIRST (two edits, then run) — this file stores
-- NOTHING sensitive in the repository:
--   1. __PUSH_FUNCTION_URL__  → your deployed Edge Function URL:
--        https://<project-ref>.functions.supabase.co/push-notify
--   2. __PUSH_WEBHOOK_SECRET__ → any long random string you ALSO upload as a
--        Supabase secret:  supabase secrets set PUSH_WEBHOOK_SECRET=<value>
--      The Edge Function rejects every call whose Authorization header does
--      not carry this value, so only these triggers can cause pushes.
--
-- Delivery is asynchronous through pg_net. The Edge Function decides the
-- recipients (from device_tokens and the pinned donor/request state), so a
-- trigger failure never blocks the change that fired it — and the app keeps
-- working without pushes entirely (the WorkManager poll is the fallback).
--
-- Three events, mirroring Phase 2's state transitions:
--   * an offer is created          → push to the REQUESTER
--   * an offer becomes Accepted    → push to the DONOR
--   * an Urgent/Critical request is created → push to every compatible,
--     available, eligible donor (the server-side version of what the poll
--     does client-side; Normal requests are deliberately NOT pushed).
--
-- Trigger arguments cannot reference NEW.* (they are plain constants), so
-- each trigger fires a one-line wrapper function that reads NEW itself.

create extension if not exists pg_net;

-- The two deploy-time constants, so no trigger body repeats them.
create or replace function public.push_url()
returns text language sql stable as $$
  select '__PUSH_FUNCTION_URL__';
$$;

create or replace function public.push_secret()
returns text language sql stable as $$
  select '__PUSH_WEBHOOK_SECRET__';
$$;

-- One HTTP call to the Edge Function. Returns void: a failure here is logged
-- by pg_net and can be audited, but must not roll back the data change.
create or replace function public.push_send(payload jsonb)
returns void language plpgsql as $$
begin
  perform net.http_post(
    url := public.push_url(),
    body := payload,
    params := '{}'::jsonb,
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ' || public.push_secret()
    ),
    timeout_milliseconds := 8000
  );
end;
$$;

-- 1. Offer created → notify the requester. -----------------------------------
create or replace function public.on_offer_inserted()
returns trigger language plpgsql as $$
begin
  perform public.push_send(jsonb_build_object(
    'type', 'offer',
    'offer_id', NEW.id,
    'request_id', NEW.request_id,
    'requester_id', NEW.requester_id,
    'donor_name', NEW.donor_name,
    'donor_group', NEW.donor_group,
    'donor_city', NEW.donor_city
  ));
  return null;
end;
$$;

drop trigger if exists push_after_offer_insert on offers;
create trigger push_after_offer_insert
  after insert on offers
  for each row
  when (NEW.status = 'Offered')
  execute function on_offer_inserted();

-- 2. Offer accepted → notify the donor. --------------------------------------
create or replace function public.on_offer_accepted()
returns trigger language plpgsql as $$
begin
  perform public.push_send(jsonb_build_object(
    'type', 'accepted',
    'offer_id', NEW.id,
    'request_id', NEW.request_id,
    'donor_auth_id', NEW.donor_auth_id
  ));
  return null;
end;
$$;

drop trigger if exists push_after_offer_accepted on offers;
create trigger push_after_offer_accepted
  after update of status on offers
  for each row
  when (NEW.status = 'Accepted')
  execute function on_offer_accepted();

-- 3. Urgent request created → compatible + available donors. -----------------
create or replace function public.on_urgent_request()
returns trigger language plpgsql as $$
begin
  perform public.push_send(jsonb_build_object(
    'type', 'urgent_request',
    'request_id', NEW.id,
    'blood_group', NEW.blood_group,
    'urgency', NEW.urgency,
    'units_needed', NEW.units_needed,
    'hospital', NEW.hospital,
    'contact', NEW.contact,
    'city', NEW.city
  ));
  return null;
end;
$$;

drop trigger if exists push_after_urgent_request on requests;
create trigger push_after_urgent_request
  after insert on requests
  for each row
  when (NEW.status = 'Pending' and NEW.urgency in ('Urgent', 'Critical'))
  execute function on_urgent_request();
