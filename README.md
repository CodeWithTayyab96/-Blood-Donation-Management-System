# Blood Donation Management System

An Android app for managing blood donors, blood stock, donation records and
blood requests for a blood bank / hospital blood unit.

**Platform:** Android 8.0 (API 26) and above
**Language:** Kotlin · **Storage:** Supabase (online) with SQLite as the
offline fallback — see "Supabase mode (v1.3)" below. Version 1.2
(local SQLite only) remains the safe fallback release.

## Features

- **Sign-in and roles** — local, offline accounts: a Manager (full access)
  and Donors (view everything, but only edit their own profile/donations).
  Demo manager account: `manager` / `manager123`. Donors register from the
  sign-in screen, which creates their account and donor profile together.
  Passwords are stored only as SHA-256 hashes, never in plain text.
- **My Profile** — a donor's own details, donation history, and their
  **next eligible date** (last donation + 90 days, or "eligible now").
- **Dashboard** — live counts: registered donors, units in stock, pending
  requests, and units donated this month, plus stock per blood group.
- **Donor management** — register donors (name, age, gender, blood group,
  phone, city, weight, last donation date), search by name/city, and filter
  by blood group **and by city** (nearby-donor search).
- **Eligibility checker** — a donor is eligible only if they are 18–65 years
  old, weigh at least 50 kg, and have not donated in the last 90 days.
- **Record donations** — one tap records a donation, updates the donor's
  last-donation date, and adds the unit to stock.
- **Blood stock** — available units for all 8 blood groups with
  AVAILABLE / LOW / OUT OF STOCK indicators.
- **Blood requests** — create patient requests (hospital, group, units,
  urgency, contact), fulfil them from stock, and instantly see every
  **compatible, eligible donor** using standard transfusion compatibility
  rules (O− is the universal donor, AB+ the universal recipient).

## Screenshots

_Add screenshots here after running the app._

## Supabase mode (v1.3)

Donors, donations, requests and stock now live in Supabase (PostgREST)
as well as in local SQLite:

- **Online first, offline safe.** Each screen shows the local copy
  immediately, then refreshes it from Supabase in the background.
  Writes go online first and then into SQLite with the server id; if
  Supabase cannot be reached, the change still saves on the phone and
  the app says so. The app never crashes because Supabase is down, and
  no network call ever runs on the main thread.
- **Login stays local.** Sign-in, manager/donor roles and password
  hashes remain on SQLite exactly as in v1.2. There is no Supabase
  Auth in this version, and the `users` table is never synced.
- **Where the key comes from.** The app reads the project URL and
  anon key from BuildConfig fields (`SUPABASE_URL`,
  `SUPABASE_ANON_KEY`), which Gradle fills from project properties of
  the same names. In CI those properties come from the repository
  secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY` (Settings → Secrets
  and variables → Actions) — use the project's legacy **anon public**
  key, never the service_role key. No key value is stored anywhere in
  this repo. Without an anon key, the app runs fully offline, exactly
  like v1.2.
- **Row Level Security warning.** If RLS is enabled on the Supabase
  tables without a policy for the anon role, PostgREST answers 401/403
  and the app silently falls back to SQLite. A review-only policy file
  is included (`supabase_policies_review.sql`) — it has NOT been
  applied. Its policies are permissive (anyone with the project URL
  and anon key could read/write the demo data), so do not enter real
  donor phone numbers until proper authentication is added. Full
  details, data flow and known limits: `SUPABASE_SETUP.md`.

## How to run

1. Open the project in **Android Studio** (Hedgehog or newer).
2. Let Gradle sync (JDK 17 required).
3. Run on an emulator or a physical device running Android 8.0+.

For a local Supabase-enabled build, pass the two properties (values
stay on your machine / in CI secrets, never in the repo), e.g.
`./gradlew assembleDebug -PSUPABASE_URL=... -PSUPABASE_ANON_KEY=...`.
Without them the app runs offline on SQLite, like v1.2.

The app seeds five sample donors on first launch so every screen can be
explored immediately; the same five donors are seeded in Supabase, so
the online and local copies start in step.

## Project structure

```
app/src/main/java/com/tayyab/blooddonation/
├── db/DbHelper.kt            SQLite schema + all queries (v2 adds users)
│                             + v1.3 upsert / explicit-id merge helpers
├── model/Models.kt           Donor, Donation, BloodRequest, UserAccount
├── util/BloodCompatibility.kt  Compatibility table + eligibility rules
├── util/Session.kt           Signed-in account + password hashing
├── util/SupabaseClient.kt    v1.3: PostgREST client (HttpURLConnection)
├── util/SupabaseSync.kt      v1.3: online/offline pull + push logic
└── ui/                       Activities and RecyclerView adapters
    ├── LoginActivity.kt      Sign-in (launcher activity)
    ├── RegisterActivity.kt   Donor account + profile creation
    └── ProfileActivity.kt    Own profile + next eligible date
```

## Inspiration and attribution

The login/manager split, profile view, and city-based donor search were
inspired by public open-source blood-donation apps (for example
Vinayak2002/Blood_Alliance and Amine-Smahi/Blood-Donation, the latter
MIT-licensed). No code was copied from those projects: every feature above
was written fresh for this app's own SQLite schema. The dashboard,
eligibility rules, compatibility matching, stock, and request fulfilment
are this project's own work.

## Author

Muhammad Tayyab Imran — BS Software Engineering, The University of Faisalabad
