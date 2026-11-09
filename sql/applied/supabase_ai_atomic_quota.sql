-- Atomic global-quota increment for the ai-coach Edge Function.
--
-- Replaces the non-atomic read-then-upsert that let two concurrent callers
-- both read the same count and both proceed past the cap.
--
-- Returns the NEW call count on success, or -1 when the cap has been reached.
-- The caller (the Edge Function) treats -1 as "over limit".

create or replace function public.increment_provider_calls(p_day text, p_cap int)
returns int language plpgsql as $$
declare
  v_calls int;
begin
  -- Upsert with a locked read so concurrent calls serialise on the row.
  insert into ai_provider_calls (day, calls)
    values (p_day, 1)
  on conflict (day) do update
    set calls = ai_provider_calls.calls + 1
    where ai_provider_calls.calls < p_cap
  returning calls into v_calls;

  -- If no row was returned the cap was already reached.
  if v_calls is null then
    return -1;
  end if;
  return v_calls;
end;
$$;
