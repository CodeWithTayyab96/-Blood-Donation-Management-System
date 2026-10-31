-- Blood Donation v1.4 — security fix: remove the unused legacy `users` table.
--
-- Run ONCE in the Supabase SQL editor.
--
-- Why: supabase_migration.sql used to create a public `users` table that
-- stored password_hash values. The app stopped using it in v1.4 (Supabase
-- Auth owns accounts now), and it was the only public table with Row Level
-- Security switched off — supabase_auth_policies.sql covers donors,
-- donations, requests and stock only. Supabase grants the `anon` role full
-- table privileges by default, so anyone holding the anon key (which ships
-- inside every release APK) could read every stored password hash through
-- PostgREST.
--
-- Nothing in the app reads or writes `users`, so dropping it is safe.

drop table if exists users;
