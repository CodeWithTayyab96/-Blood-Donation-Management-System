# Blood Donation Management System

An Android app for managing blood donors, blood stock, donation records and
blood requests for a blood bank / hospital blood unit.

**Platform:** Android 8.0 (API 26) and above
**Language:** Kotlin · **Storage:** Supabase (online) with SQLite as the
offline fallback — see "Supabase mode (v1.4)" below. Version 1.2
(local SQLite only) remains the safe fallback release.## Features

- **Sign-in and roles** — real accounts through Supabase Auth
  (email + password). A Manager (full access) and Donors (view
  everything, but only edit their own profile/donations). Demo
  manager logins: `manager` / `manager123` (local-only, as before)
  and `manager@blood.local` / `manager123` (through Supabase Auth).
  Donors register from the sign-in screen with an email address,
  which creates their Auth account and donor profile together.
  Registration needs an internet connection — it never creates a
  silent local-only account any more. Passwords are stored only as
  SHA-256 hashes on the phone, never in plain text, and the Auth
  tokens are kept in the app's private session store. Old
  local-only accounts from v1.3 do NOT carry over: they must
  re-register with an email to get an Auth account.
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
- **Urgent request alerts** — a signed-in donor is notified about pending
  requests their blood group can serve, at most once per request. Delivery
  is **approximate**, not instant: see "Urgent request alerts" below.
- **Daily motivation card & milestones** — the dashboard shows one accurate
  blood-donation fact per day, plus a donor's own milestones (badges at the
  1st/3rd/5th/10th donation, current streak, and an estimate of how many
  people they may have helped). All computed locally from the donation history.
- **Optional daily tip** — a practical, non-medical tip, off by default, at
  most one a day and never at night. Toggle it in **Settings**.

## Urgent request alerts

A signed-in donor gets a notification when a pending **Urgent** or **Critical**
request appears that their blood group can serve and that they are eligible to
donate for. Requests marked **Normal** are skipped — they are routine stock
replenishment and alerting on them would only teach donors to ignore the
notifications.

How it works, and why it is not instant:

- **Polling, not push.** A `WorkManager` periodic job
  (`RequestAlertWorker`) wakes roughly **every 15 minutes** — the minimum
  period Android allows for periodic work — pulls the pending requests from
  Supabase, and compares them with the donor's own group using the same
  `BloodCompatibility` rules as the request screen. There is no Firebase,
  no push service and no server of our own.
- **Approximate delivery.** An alert can arrive up to a poll interval after
  the request is created, and later still when the phone is asleep, in Doze,
  or low on battery — the system batches and defers background work. Treat
  these as "you'll hear about it soon", not "you'll hear about it now".
- **Online only.** The job carries a `CONNECTED` network constraint, so it
  never runs without a connection. If Supabase is not configured the job is
  not scheduled at all.
- **Once per request, ever.** Notified request ids are stored in their own
  SharedPreferences file, so the same request never alerts twice — including
  across app restarts and repeated polls. Fulfilled requests keep their id
  remembered, so they cannot resurface.
- **Donors only.** The job is scheduled when a donor reaches the dashboard
  and cancelled on sign-out or when a manager signs in.
- **Android 13+ needs permission.** The app asks once, behind a short
  explanation, the first time a donor reaches the dashboard. Declining is
  respected: the prompt does not return on every launch, and granting the
  permission later in system settings takes effect on its own.
- **Nothing sensitive on the lock screen.** The notification is
  `VISIBILITY_PRIVATE`, with a public version that says only "A blood request
  needs your help", so the hospital and contact number stay hidden while the
  device is locked. The patient's name is never shown at all. Tapping the alert
  opens that request's detail screen.
- **Debug builds** add a "Check requests now" button that runs the same worker
  immediately instead of waiting for the next poll, and reports how many alerts
  it sent. It is hidden in release builds.


## Push notifications (v1.7, Phase 3)

**Requests and offers need an internet connection.** Notification delivery
is "best effort" in every mode, and the app says so honestly:

- **With Firebase configured**, offers, acceptances and compatible
  Urgent/Critical requests arrive as data-only FCM pushes within seconds of
  being created. The message carries no patient name; the app builds the
  notification itself, so the exact same lock-screen, channel and
  once-per-event rules apply as for the poll. Setup steps: `PUSH_SETUP.md`
  (a Firebase project, a service-account JSON stored only as a Supabase
  secret, and one Edge Function).
- **Without Firebase (fallback build), the 15-minute WorkManager poll
  remains** — schedule unchanged, dedup shared, and Normal requests still
  never alert. Peer events (offers/acceptances) are also announced by the
  poll while no push path exists.
- **Dedup is shared between both paths**, configured per device in its own
  plain prefs file (`request_alerts`, `peer_alerts`): a request or offer the
  push already announced can never be announced again by the poll, and vice
  versa.
- **Sign-out stops pushes** for that device (token row deleted) and stops
  the poll for that user. The token is re-uploaded on the next sign-in.

## Screenshots

_Add screenshots here after running the app._


## Supabase mode (v1.4)

Donors, donations, requests and stock live in Supabase (PostgREST)
as well as in local SQLite, and accounts are now real Supabase Auth
accounts (v1.3 had local-only accounts — see SUPABASE_SETUP.md for
what changed):

- **Online first, offline safe.** Each screen shows the local copy
  immediately, then refreshes it from Supabase in the background.
  Writes go online first and then into SQLite with the server id; if
  Supabase cannot be reached, the change still saves on the phone and
  the app says so. The app never crashes because Supabase is down, and
  no network call ever runs on the main thread.
- **Sign-in is Supabase Auth.** Registering creates an Auth user and
  a donor profile linked by `auth_id`, and every online call carries
  that user's access token (refreshed automatically once when it
  expires). The tables have Row Level Security for the
  `authenticated` role (`supabase_auth_policies.sql`, applied), so
  the anon key alone reads/writes nothing. That is authentication,
  not authorisation — it does not separate managers from donors; see
  the Row Level Security note below. Signing in with an email
  on a new phone finds the donor profile by `auth_id` and recreates
  the local account. If there is no network but the phone already
  knows the email + password, the app signs in offline and says so.
  The plain-username login (`manager` / `manager123`) still works, but it is
  **local-only**: it carries no Supabase session, so every online call goes out
  anonymously and is rejected by the policies above. Nothing on that device
  syncs, and the dashboard says so. Use `manager@blood.local` to work against
  the shared data.
- **Where the key comes from.** The app reads the project URL and
  anon key from BuildConfig fields (`SUPABASE_URL`,
  `SUPABASE_ANON_KEY`), which Gradle fills from project properties of
  the same names. In CI those properties come from the repository
  secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY` (Settings → Secrets
  and variables → Actions) — use the project's legacy **anon public**
  key, never the service_role key. No key value is stored anywhere in
  this repo. Without an anon key, the app runs fully offline, exactly
  like v1.2.
- **Row Level Security note.** The old review-only file
  (`supabase_policies_review.sql`, permissive anon access) must NOT
  be applied — v1.4 replaces it with authenticated-only policies.

  **Role enforcement is client-side only unless you apply
  `supabase_role_policies.sql`.** With just
  `supabase_auth_policies.sql`, every signed-in user — donor or
  manager — can read and write every row. The Manager/Donor split
  lives in `Session.isManager` and in hidden buttons, which is a UI
  guard, not a security boundary: anyone with a donor account could
  call PostgREST directly and change stock, edit other donors or
  delete them. `supabase_role_policies.sql` moves the role into the
  Auth user's `app_metadata` and enforces it in the database. It is
  **not** applied by default, and its header explains the ordering —
  set the manager's role claim first, then the policies, then sign
  in again so the new token carries it.

  Demo/sample data only: do not enter real donor phone numbers.
  Full details, data flow and known limits: `SUPABASE_SETUP.md`.

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
├── db/DbHelper.kt            SQLite schema + all queries (v3 adds the
│                             manager@blood.local seed) + v1.3 upsert /
│                             explicit-id merge helpers + v1.4 profile relink
├── model/Models.kt           Donor, Donation, BloodRequest, UserAccount
├── util/BloodCompatibility.kt  Compatibility table + eligibility rules
├── util/Session.kt           Signed-in account + password hashing +
│                             v1.4 Auth tokens + pending-profile flag
├── util/SupabaseAuth.kt      v1.4: Supabase Auth client (HttpURLConnection)
├── util/SupabaseClient.kt    PostgREST client (HttpURLConnection);
│                             v1.4: user Bearer token + one 401 refresh
├── util/SupabaseSync.kt      Online/offline pull + push logic
└── ui/                       Activities and RecyclerView adapters
    ├── LoginActivity.kt      Sign-in: email via Supabase Auth, or
    │                         plain username local-only (launcher activity)
    ├── RegisterActivity.kt   Email registration: Auth account + donor
    │                         profile linked by auth_id
    └── ProfileActivity.kt    Own profile + next eligible date +
                              "Sync profile" retry for unsynced profiles
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
