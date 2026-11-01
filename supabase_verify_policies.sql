-- ============================================================================
-- supabase_verify_policies.sql — READ-ONLY. Safe to run any number of times.
--
-- Answers one question: does this project's security state match the intended
-- final state (every file applied), or not?
--
-- Run it in the Supabase SQL editor after applying the SQL files, and again
-- after any change to them. Output is exactly one of:
--
--   ALL POLICIES OK                      — nothing to do
--   PROBLEM: <what is wrong>             — one row per problem
--
-- A PROBLEM row is a real mismatch, not advice. `policy <name> on <table>:
-- MISSING` means a file was not applied (or failed part-way); `UNEXPECTED`
-- means something is present that should not be — most often the permissive
-- "Authenticated full access" policies, or "requests write manager", which
-- supabase_p2p_policies.sql removes.
--
-- Expected final state: 27 policies across 8 tables, RLS on for all 9 tables,
-- no policy at all on ai_coach_usage, and no SELECT privilege for
-- `authenticated` on donors.phone or requests.contact.
--
-- A table that does not exist yet is SKIPPED, not reported as a problem, so
-- this file is usable between stages: before Stage B (push) there is no
-- device_tokens table, and before Stage C no ai_coach_usage, and neither is a
-- failure. A table that exists with the wrong policies is still a problem.
--
-- CI runs this file after the forward pass and again after re-applying the
-- policy files in the opposite order; a single PROBLEM row fails the job.
-- ============================================================================

with expected_all(tbl, pol, cmd) as (
  values
    ('donors','donors read','SELECT'),
    ('donors','donors insert manager','INSERT'),
    ('donors','donors insert own','INSERT'),
    ('donors','donors update manager','UPDATE'),
    ('donors','donors update own','UPDATE'),
    ('donors','donors delete manager','DELETE'),
    ('donations','donations read','SELECT'),
    ('donations','donations insert','INSERT'),
    ('donations','donations update manager','UPDATE'),
    ('donations','donations delete manager','DELETE'),
    ('requests','requests read','SELECT'),
    ('requests','requests insert owner or manager','INSERT'),
    ('requests','requests update owner or manager','UPDATE'),
    ('requests','requests delete manager','DELETE'),
    ('stock','stock read','SELECT'),
    ('stock','stock write manager','ALL'),
    ('offers','offers read','SELECT'),
    ('offers','offers insert','INSERT'),
    ('offers','offers update donor own','UPDATE'),
    ('offers','offers update requester own','UPDATE'),
    ('contact_details','contact_details read own manager or accepted party','SELECT'),
    ('contact_details','contact_details insert own','INSERT'),
    ('contact_details','contact_details update own','UPDATE'),
    ('reports','reports insert','INSERT'),
    ('reports','reports read manager','SELECT'),
    ('device_tokens','device tokens own crud','ALL'),
    ('device_tokens','device tokens read manager','SELECT')
), expected as (
  select e.tbl, e.pol, e.cmd
    from expected_all e
   where exists (
     select 1 from pg_tables t
      where t.schemaname = 'public' and t.tablename = e.tbl
   )
), actual as (
  select tablename::text as tbl, policyname::text as pol, cmd::text as cmd
    from pg_policies
   where schemaname = 'public'
), cmp as (
  select coalesce(e.tbl, a.tbl) as table_name,
         coalesce(e.pol, a.pol) as policy_name,
         case when a.pol is null then 'MISSING'
              when e.pol is null then 'UNEXPECTED'
              else 'ok' end     as status
    from expected e
    full join actual a on a.tbl = e.tbl and a.pol = e.pol
), policy_problems as (
  select format('policy %s on %s: %s', policy_name, table_name, status) as problem
    from cmp
   where status <> 'ok'
), rls_problems as (
  select format('row level security is OFF on %s', t.tablename) as problem
    from pg_tables t
   where t.schemaname = 'public'
     and t.tablename in ('donors','donations','requests','stock','offers',
                         'contact_details','reports','device_tokens','ai_coach_usage')
     and not t.rowsecurity
), privilege_problems as (
  select 'authenticated can still SELECT donors.phone' as problem
   where to_regclass('public.donors') is not null
     and has_column_privilege('authenticated', 'public.donors', 'phone', 'SELECT')
  union all
  select 'authenticated can still SELECT requests.contact'
   where to_regclass('public.requests') is not null
     and has_column_privilege('authenticated', 'public.requests', 'contact', 'SELECT')
), function_problems as (
  select 'missing function public.donor_phone(bigint)' as problem
   where to_regprocedure('public.donor_phone(bigint)') is null
  union all
  select 'missing function public.request_contact(bigint)'
   where to_regprocedure('public.request_contact(bigint)') is null
  union all
  select 'authenticated cannot execute public.donor_phone(bigint)'
   where to_regprocedure('public.donor_phone(bigint)') is not null
     and not has_function_privilege('authenticated', 'public.donor_phone(bigint)', 'EXECUTE')
  union all
  select 'authenticated cannot execute public.request_contact(bigint)'
   where to_regprocedure('public.request_contact(bigint)') is not null
     and not has_function_privilege('authenticated', 'public.request_contact(bigint)', 'EXECUTE')
), all_problems as (
  select problem from policy_problems
  union all select problem from rls_problems
  union all select problem from privilege_problems
  union all select problem from function_problems
)
select 'ALL POLICIES OK' as result
 where not exists (select 1 from all_problems)
union all
select 'PROBLEM: ' || problem from all_problems
 order by 1;
