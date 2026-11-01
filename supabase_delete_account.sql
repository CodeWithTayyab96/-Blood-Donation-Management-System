-- ============================================================================
-- supabase_delete_account.sql — the data half of "delete my account".
--
-- Called by the `delete-account` Edge Function (service role) right before it
-- removes the Auth user with auth.admin.deleteUser(). Also executable by a
-- signed-in user for their OWN id, so the rule can be tested; it never deletes
-- the Auth user itself.
--
-- RUN ORDER: last. It touches the P2P and FCM tables, and guards each of them
-- with to_regclass so a project that never applied those files still works.
--
-- What is deleted, and what is kept:
--
--   contact_details   deleted — it is a phone number, nothing else
--   device_tokens     deleted — push endpoints are personal
--   reports           deleted — the reason text can name people
--   offers            deleted — the donor's own offers
--   requests          OPEN (Pending) ones deleted; matched/fulfilled ones are
--                     KEPT with requester_id = null and contact = '' so the
--                     bank's record of a real event survives without the person
--   donors            ANONYMISED, not deleted: name, phone, city and auth_id are
--                     cleared. Deleting the row would cascade into donations
--                     (FK on delete cascade) and erase the blood bank's
--                     donation history — a record of a real event that also
--                     underpins the stock counts.
--   donations         KEPT, attached to the anonymised donor row.
--
-- Rollback: supabase_delete_account_rollback.sql (drops the function; data that
-- was already deleted cannot be restored).
-- ============================================================================

create or replace function public.delete_account_data(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_donor_id bigint;
begin
  -- Defence in depth: the Edge Function already verifies the caller, but a
  -- direct RPC call must not be able to delete somebody else's account.
  if auth.uid() is not null and p_user_id <> auth.uid() then
    raise exception 'delete_account_data: you may only delete your own account'
      using errcode = '42501';
  end if;

  -- Personal data, deleted outright. Every table here arrived with a later
  -- stage (contact_details and reports with the P2P migration, device_tokens
  -- with push), so each is guarded: this file must run on a project that never
  -- applied them, not just on one that applied everything.
  if to_regclass('public.contact_details') is not null then
    delete from public.contact_details where user_id = p_user_id;
  end if;

  if to_regclass('public.device_tokens') is not null then
    delete from public.device_tokens where user_id = p_user_id;
  end if;

  if to_regclass('public.reports') is not null then
    delete from public.reports where reporter_id = p_user_id;
  end if;

  if to_regclass('public.offers') is not null then
    delete from public.offers where donor_auth_id = p_user_id;
  end if;

  -- Open requests disappear (their offers cascade with them).
  if exists (
    select 1 from information_schema.columns
     where table_schema = 'public' and table_name = 'requests'
       and column_name = 'requester_id'
  ) then
    delete from public.requests
     where requester_id = p_user_id
       and status = 'Pending';

    -- Requests that already matched or were fulfilled stay as a record, with
    -- the person and their contact number removed.
    update public.requests
       set requester_id = null,
           contact = ''
     where requester_id = p_user_id;
  end if;

  -- The donor profile is anonymised, not deleted: the donation history points
  -- at it, and that history is the blood bank's record.
  select id into v_donor_id from public.donors where auth_id = p_user_id;
  if v_donor_id is not null then
    update public.donors
       set name = 'Deleted donor',
           phone = '',
           city = '',
           auth_id = null,
           available_to_donate = false
     where id = v_donor_id;
  end if;
end;
$$;

revoke all on function public.delete_account_data(uuid) from public;
grant execute on function public.delete_account_data(uuid) to authenticated, service_role;
