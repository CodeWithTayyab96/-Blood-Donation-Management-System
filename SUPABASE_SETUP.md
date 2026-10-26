# Blood Donation v1.3 — Supabase setup

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

## Before the app can actually read/write online

- Add the two secrets above (names only — never commit a key value).
- If PostgREST answers 401/403, Row Level Security is the likely
  cause: review `supabase_policies_review.sql` first. It contains
  permissive anon policies for the five tables. It has NOT been
  applied anywhere — applying it is a deliberate decision, because
  with permissive anon policies anyone holding the project URL and
  the anon key can read/write the demo data. Do not enter real donor
  phone numbers until proper auth is added.

## Files

- `app/src/main/java/com/tayyab/blooddonation/util/SupabaseClient.kt`
  — raw PostgREST calls (blocking; background threads only).
- `app/src/main/java/com/tayyab/blooddonation/util/SupabaseSync.kt`
  — pull/merge and multi-step write orchestration.
- `app/src/main/java/com/tayyab/blooddonation/db/DbHelper.kt`
  — upsert / explicit-id helpers added; all v1.2 methods unchanged.
- `supabase_migration.sql` — the schema already applied to the
  project (donors, donations, requests, stock, users + seeds).
- `supabase_policies_review.sql` — RLS policies for review only.
