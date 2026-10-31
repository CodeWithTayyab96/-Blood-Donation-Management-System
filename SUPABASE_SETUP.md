# Blood Donation v1.4 — Supabase setup

## What changed in v1.4 (Auth)

Accounts are now real Supabase Auth accounts. Everything from v1.3
below still applies to donors, donations, requests and stock; this
section only covers what Auth adds.

- **Sign-up / sign-in** (`util/SupabaseAuth.kt`, plain
  HttpURLConnection + org.json, no new library): the app calls
  GoTrue directly — `POST /auth/v1/signup` and
  `POST /auth/v1/token?grant_type=password` — and stores the
  session (user id, access token, refresh token) in `Session`.
  Registration requires an email and an internet connection: with
  no Supabase config it stops with "Registration needs an internet
  connection" and creates nothing, and a failed sign-up (email
  already registered, no network) also creates nothing. The old
  v1.3 behaviour — a silent local-only account that looked synced
  but was not — is gone.
- **Donor profiles link by `auth_id`.** Registration inserts the
  donor profile with `auth_id` = the new Auth user id (see
  `supabase_auth_migration.sql`: `donors.auth_id uuid` + a partial
  unique index, so one profile per Auth user; the 5 seeded demo
  donors have no `auth_id` and are unaffected). Signing in on a new
  phone looks the profile up by `auth_id` and recreates the local
  account + profile, so a donor gets their own profile back on any
  device. If the profile insert fails after a successful sign-up,
  the account still works on the phone, the profile is marked
  pending, and the profile screen offers a "Sync profile" retry
  that re-links the local row to the server id.
- **Every online call carries the user's token.**
  `SupabaseClient` sends the signed-in user's access token as the
  Bearer token (anon key only when no Auth session exists). On a
  401 it refreshes the session once via the stored refresh token
  and retries the call once; a dead session (refresh rejected for
  a non-network reason) has its tokens cleared, so the user signs
  in again instead of retrying a dead token forever. No network
  call ever runs on the main thread, and no Auth function ever
  throws — failures return null/false, and callers fall back to
  local SQLite exactly as before.
- **Row Level Security is authenticated-only**
  (`supabase_auth_policies.sql`): RLS is ON for donors, donations,
  requests and stock, and the `authenticated` role gets full access
  to all rows. The anon key alone reads/writes nothing any more —
  which is why the v1.3 review file below must NOT be applied.
  Demo/sample data only: every signed-in user can read/write every
  row (a classroom demo, not per-user row ownership). Do NOT enter
  real donor phone numbers.
- **Demo logins.** `manager` / `manager123` still works local-only
  (unchanged v1.3 path). `manager@blood.local` / `manager123`
  works through Supabase Auth (a matching Auth user exists in the
  project, and the app seeds the local mirror row). Old local-only
  v1.3 accounts do NOT carry over: their username + password hash
  still exist on the phone, but they have no Auth user, so they
  must re-register with an email to sign in through Auth. (A
  v1.3 local username without "@" still signs in locally, exactly
  as before — that path is untouched.)
- **Dashboard Auth settings.** Email provider ON, "Confirm email"
  OFF (so sign-up returns a session immediately). If Confirm email
  is ever turned back ON, sign-up returns a user but no session,
  and the app reports "Email confirmation is required" instead of
  pretending the sign-up succeeded.

## What changed in v1.3

The app now talks to Supabase PostgREST for donors, donations, requests
and stock, using plain HttpURLConnection + org.json (the same pattern as
the in-app updater) — no new library was added. Login/accounts still
live on local SQLite exactly as in v1.2; there is no Supabase Auth.

Data flow:

- **Reads** — each screen first shows the local SQLite copy, then
  quietly pulls the online rows in the background and merges them in
  (upsert by id). If Supabase is unreachable, the local copy simply
  stays on screen.
- **Writes** — add donor / register donor / record donation / delete
  donor / add request / fulfil request go online first, then into
  SQLite with the server-assigned id. If the online step fails, the
  change still lands in SQLite and the app says it was saved on this
  phone (Supabase unreachable). Fulfilling is the one exception: if
  the online stock is genuinely short, the request is NOT fulfilled
  locally either, so the two copies cannot disagree.
- **Fallback** — with no anon key baked in, or on any Supabase failure,
  the app behaves exactly like v1.2 (local SQLite only) and never
  crashes. No network call ever runs on the main thread.

Known limits (see also `util/SupabaseSync.kt`):

- A multi-step online write (donation = row + donor date + stock;
  fulfil = stock + status) is not a single transaction, so a network
  drop halfway can leave the copies briefly disagreeing. The local
  copy always has the full change the user saw.
- A row created while offline gets a local-only id; if a different
  online row later arrives with that same id, the upsert replaces it.
  In normal online use this never happens, because online writes take
  the server id first. Related: an offline-created donor cannot be
  pushed later by id, because Supabase would reject it as an unknown
  donor — such rows stay local until they are re-created online.
- Pulls never delete local rows: a row deleted directly in the
  Supabase dashboard will still show locally until it is also deleted
  in the app (the app's own delete removes it from both copies).

## ⚠️ Security fix: drop the unused `users` table

`supabase_migration.sql` used to create a public `users` table holding
`password_hash` values. The app never read or wrote it from v1.4 on
(Supabase Auth owns accounts), and it was the **only** public table with
Row Level Security switched off — `supabase_auth_policies.sql` covers
`donors`, `donations`, `requests` and `stock` only. Supabase grants the
`anon` role full table privileges by default, so anyone holding the anon
key (which is baked into every release APK) could read every stored
password hash through PostgREST.

**If you already ran `supabase_migration.sql`, run `fix_users_table.sql`
in the Supabase SQL editor now.** It is a single statement:

```sql
drop table if exists users;
```

`supabase_migration.sql` no longer creates the table, so a fresh setup is
unaffected, and nothing in the app reads or writes `users`.

## How the anon key reaches the build (no values in this repo)

1. Gradle property `SUPABASE_ANON_KEY` (and `SUPABASE_URL`) — see
   `app/build.gradle.kts`, which turns them into BuildConfig fields
   `SUPABASE_ANON_KEY` / `SUPABASE_URL`. The URL defaults to this
   project's public URL; the key defaults to empty (offline mode).
2. GitHub Actions — both `.github/workflows/release.yml` and
   `.github/workflows/android.yml` pass
   `-PSUPABASE_URL` / `-PSUPABASE_ANON_KEY` from the repo secrets
   `SUPABASE_URL` and `SUPABASE_ANON_KEY` (Settings → Secrets and
   variables → Actions). Use the project's legacy **anon public**
   key, never the service_role key — service_role bypasses RLS and
   must never ship inside an app.

## Before the app can actually read/write online (v1.4 setup steps)

- Add the two secrets above (names only — never commit a key value).
- Run, in order, in the Supabase SQL editor: `supabase_migration.sql`
  (already applied), then `supabase_auth_migration.sql` (adds
  `donors.auth_id` + the partial unique index), then
  `supabase_auth_policies.sql` (RLS ON + authenticated full access).
  All three are one-time. The migration file is safe to re-run
  (`if not exists` throughout); the policies file will error on a
  duplicate policy name if re-run, which simply means the policies
  are already there.
- In Authentication → Providers: Email ON, "Confirm email" OFF.
- Do NOT run `supabase_policies_review.sql`: it was the v1.3
  review-only idea (permissive anon access), it was never applied,
  and v1.4 replaces it with the authenticated policies above. With
  the authenticated policies in place, a PostgREST 401 means the
  access token expired (the app refreshes once and retries) or the
  user is not signed in — not a missing anon policy.

## Files

- `app/src/main/java/com/tayyab/blooddonation/util/SupabaseAuth.kt`
  — v1.4: GoTrue sign-up / sign-in / refresh / profile-by-auth-id
  (blocking; background threads only).
- `app/src/main/java/com/tayyab/blooddonation/util/SupabaseClient.kt`
  — raw PostgREST calls (blocking; background threads only);
  v1.4: user Bearer token + one 401 refresh-and-retry.
- `app/src/main/java/com/tayyab/blooddonation/util/SupabaseSync.kt`
  — pull/merge and multi-step write orchestration.
- `app/src/main/java/com/tayyab/blooddonation/util/Session.kt`
  — v1.4: also stores Auth email, user id, tokens, pending flag.
- `app/src/main/java/com/tayyab/blooddonation/db/DbHelper.kt`
  — upsert / explicit-id helpers added; v1.4 adds the
  manager@blood.local seed, a v3 upgrade branch, and the profile
  relink helper. All v1.2 methods unchanged.
- `supabase_migration.sql` — the schema already applied to the
  project (donors, donations, requests, stock + seeds). No longer
  creates the legacy `users` table.
- `fix_users_table.sql` — one-time security fix: drops the unused
  `users` table (see the warning above). Run this if you applied an
  older `supabase_migration.sql`.
- `supabase_auth_migration.sql` — v1.4: `donors.auth_id` + partial
  unique index. Apply after `supabase_migration.sql`.
- `supabase_auth_policies.sql` — v1.4: RLS ON + authenticated full
  access. Apply after `supabase_auth_migration.sql`. REPLACES the
  v1.3 review file below — do not apply both.
- `fix_stock_rpc.sql` — adds `public.adjust_stock(text, int)`, the
  atomic stock adjustment the app now calls instead of
  read-then-write. Purely additive: safe to run before or after the
  APK update, and it does not affect older app versions.
- `supabase_role_policies.sql` — role-aware RLS that replaces
  "Authenticated full access" with per-role policies. **Do not run
  this until you have read its header**: it needs the manager's
  `app_metadata.role` set first, and every session must sign in
  again before its token carries the claim.
- `supabase_policies_review.sql` — v1.3 RLS policies for review
  only. NEVER applied; do NOT apply it under v1.4.
