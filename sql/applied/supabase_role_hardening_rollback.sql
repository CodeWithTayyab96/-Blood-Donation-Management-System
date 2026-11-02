-- ============================================================================
-- supabase_role_hardening_rollback.sql — undoes supabase_role_hardening.sql.
--
-- Restores exactly what was there before: a donor may record their own
-- donation, and adjust_stock() has no role check inside (the grant is
-- unchanged in both directions).
--
-- Run it if the hardened rules break a screen you still need, then re-run
-- supabase_role_hardening.sql when the app is ready for them.
-- ============================================================================

-- 1. Donations: manager, or the donor whose own auth_id matches the row.
drop policy if exists "donations insert" on donations;
drop policy if exists "donations insert manager" on donations;

create policy "donations insert" on donations
  for insert to authenticated
  with check (
    is_manager()
    or donor_id in (select id from donors where auth_id = auth.uid())
  );

-- 2. adjust_stock() back to the version in fix_stock_rpc.sql (no role check).
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
