-- ============================================================================
-- supabase_role_hardening.sql — an ordinary user cannot write donations or
-- stock. Phase 2 of the user-app plan.
--
-- RUN ORDER: after supabase_role_policies.sql (it needs public.is_manager())
-- and after fix_stock_rpc.sql (this file replaces adjust_stock()). Safe to
-- re-run: it drops the policy before creating it and replaces the function.
--
-- What changes, and why:
--
--   1. donations INSERT was "manager OR the donor whose auth_id matches".
--      A donor could therefore record their own donation from the app, which
--      is exactly what the peer flow must NOT allow (the donor would be
--      writing the bank's records). It is now manager-only. Peer donations are
--      still recorded — by complete_peer_donation(), a SECURITY DEFINER
--      function that runs as the table owner, so it is unaffected by RLS.
--
--   2. adjust_stock() was executable by every signed-in user and had no check
--      inside, so any donor could move blood-bank stock. The grant has to stay
--      (managers are also the `authenticated` role), so the guard is inside the
--      function: it raises 42501 unless the caller's JWT says MANAGER.
--
-- Rollback: supabase_role_hardening_rollback.sql
-- Verify:   supabase_verify_policies.sql must still print ALL POLICIES OK.
-- ============================================================================

-- 1. Donations are recorded by a manager, or by the peer-donation function.
drop policy if exists "donations insert" on donations;
drop policy if exists "donations insert manager" on donations;

create policy "donations insert" on donations
  for insert to authenticated
  with check (is_manager());

-- 2. Stock moves only through a manager's call to adjust_stock().
create or replace function public.adjust_stock(p_group text, p_delta int)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_units integer;
begin
  if not public.is_manager() then
    raise exception 'adjust_stock: only a manager may change stock'
      using errcode = '42501';
  end if;

  update stock
     set units = greatest(0, units + p_delta)
   where blood_group = p_group
  returning units into v_units;

  if v_units is null then
    -- Unknown blood group: do not silently create a row.
    raise exception 'adjust_stock: unknown blood group %', p_group
      using errcode = '22023';
  end if;

  return v_units;
end;
$$;

revoke all on function public.adjust_stock(text, int) from public;
grant execute on function public.adjust_stock(text, int) to authenticated;
