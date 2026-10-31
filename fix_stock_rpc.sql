-- Blood Donation — atomic stock adjustment.
-- Run ONCE in the Supabase SQL editor. Safe to re-run (create or replace).
--
-- WHY: the app used to read a blood group's units, add or subtract in Kotlin,
-- and write the absolute value back. Two devices recording a donation at the
-- same moment both read "4" and both wrote "5", so a unit vanished. This
-- function does the arithmetic in a single UPDATE, so concurrent calls queue
-- instead of overwriting each other.
--
-- ORDER RELATIVE TO THE APP UPDATE: run this BEFORE installing the new APK.
-- It is purely additive — it creates a function and touches no table, so
-- OLD app versions (which still PATCH /rest/v1/stock) keep working unchanged
-- whether or not this has been run. If the new app starts before this exists,
-- the RPC returns 404 and the app simply falls back to saving the donation
-- locally only — no crash, no data loss, just an unsynced stock count until
-- the function is in place.
--
-- SECURITY: SECURITY DEFINER, deliberately. The donation flow lets a donor
-- record their own donation, which must increment stock; under
-- supabase_role_policies.sql only managers may write to `stock` directly, so
-- an INVOKER function would fail for donors. The escalation is kept narrow:
-- one blood group, one integer delta, clamped at zero, unknown groups
-- rejected. The stricter alternative is to move "record a donation" into a
-- server-side function too, so the client never touches stock at all.

create or replace function public.adjust_stock(p_group text, p_delta int)
returns integer
language plpgsql
security definer
set search_path = public
as $$
declare
  v_units integer;
begin
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

-- Quick check after running (should return the new unit count, e.g. 0):
--   select public.adjust_stock('O+', 0);
