-- =====================================================================
-- REVIEW ONLY — DO NOT RUN THIS BLIND, AND IT HAS NOT BEEN APPLIED.
-- =====================================================================
-- Why this file exists: the v1.3 app talks to Supabase PostgREST with
-- the project's anon key. If Row Level Security (RLS) is switched on
-- for a table without a policy that allows the anon role, PostgREST
-- answers 401/403 and the app silently falls back to local SQLite
-- (that is the "Supabase unreachable" path). If you see 401/403 in
-- SupabaseClient.lastError, missing policies are the first thing to
-- check. The tables themselves were created by supabase_migration.sql,
-- which does NOT enable RLS, so with RLS off the anon key can already
-- read/write — these policies only matter once RLS is turned on.
--
-- What the policies below do: they let the anon role read and write
-- ALL rows of all five tables. That is fine for a classroom demo and
-- for the seeded sample donors, and it is what makes the v1.3 demo
-- work without Supabase Auth. It is NOT safe for real donor data.
--
-- HONEST WARNING — read before applying:
-- With permissive anon policies, anyone who holds the project URL and
-- the anon key can read and write this demo data (donor names, phone
-- numbers, cities). The anon key is baked into the APK, so treat it as
-- public. Do NOT enter real donor phone numbers or any real personal
-- data into this project until proper authentication is added (for
-- example Supabase Auth + policies that restrict each donor to their
-- own row and managers to everything). Until then, demo/sample data
-- only.
--
-- To apply (only after reading the warning above): paste this whole
-- file into the Supabase SQL editor for this project and run it once.
-- To undo: drop the policies, or turn RLS off again per table.
-- =====================================================================

alter table donors enable row level security;
alter table donations enable row level security;
alter table requests enable row level security;
alter table stock enable row level security;
alter table users enable row level security;

-- One permissive policy per table, for the anon role, all operations.
-- "using (true)" allows reading every row; "with check (true)" allows
-- writing every row. There is intentionally no per-user restriction
-- here — see the warning above before applying.

create policy "Demo: anon full access"
  on donors for all to anon using (true) with check (true);

create policy "Demo: anon full access"
  on donations for all to anon using (true) with check (true);

create policy "Demo: anon full access"
  on requests for all to anon using (true) with check (true);

create policy "Demo: anon full access"
  on stock for all to anon using (true) with check (true);

create policy "Demo: anon full access"
  on users for all to anon using (true) with check (true);

-- NOTE: the app never reads or writes the users table online — login
-- stays on local SQLite. The users policy above exists only so the
-- table is not the odd one out if RLS is enabled project-wide; it can
-- be dropped without affecting the app.
