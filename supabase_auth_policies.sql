-- Blood Donation v1.4 — Row Level Security for authenticated users.
-- Run ONCE in the Supabase SQL editor, AFTER supabase_auth_migration.sql.
--
-- This REPLACES the v1.3 idea in supabase_policies_review.sql (permissive
-- anon access), which was review-only and was NEVER applied. Do NOT apply
-- that file: with Auth in place, the anon key must no longer read or
-- write donor data on its own.
--
-- What this does: RLS is switched on for the four data tables, and the
-- "authenticated" role (i.e. any user signed in through Supabase Auth,
-- whose JWT the app sends as the Bearer token) gets full access to all
-- rows. The app requires sign-in before any data screen, so every
-- online call it makes carries a user JWT. Logged-out use falls back
-- to the local SQLite copy, exactly as before — nothing crashes.
--
-- Demo-data note: every signed-in user can read/write every row (a
-- classroom demo, not per-user row ownership). Do NOT enter real donor
-- phone numbers into this project; demo/sample data only.

alter table donors enable row level security;
alter table donations enable row level security;
alter table requests enable row level security;
alter table stock enable row level security;

create policy "Authenticated full access"
  on donors for all to authenticated using (true) with check (true);

create policy "Authenticated full access"
  on donations for all to authenticated using (true) with check (true);

create policy "Authenticated full access"
  on requests for all to authenticated using (true) with check (true);

create policy "Authenticated full access"
  on stock for all to authenticated using (true) with check (true);
