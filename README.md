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
  manager account: `tayyabuq1@gmail.com` (through Supabase Auth — its
  password is the one set when the account was created; see RUNBOOK
  A5). There is no local-only demo login any more, and the manager
  role comes from the token, not from the phone.
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
- **Call / WhatsApp** — both contact screens show a **Call** (ACTION_DIAL, no
  CALL_PHONE permission) and a **WhatsApp** button when a usable number is
  available. Pakistani numbers are normalised to `+92…` by one tested helper,
  and the buttons disappear when the number is missing or this account is not
  allowed to see it. A phone without WhatsApp gets a clear message instead of
  a dead button.
- **Share a request** — a **Share request** button sends the blood group,
  units, hospital, city, contact (only when visible) and a short app mention
  through ACTION_SEND. The text is built by a pure, tested function; all
  wording is in `strings.xml`.
- **English / اردو** — Settings → **Language** switches the whole app with
  `AppCompatDelegate.setApplicationLocales`. `values-ur/strings.xml` translates
  every string; the choice is persisted and Urdu mirrors the layout (RTL).
- **Donation reminder** — one WorkManager notification on the day a donor
  becomes eligible again (the same 90-day rule as the eligibility checker).
  It is rescheduled whenever a donation is recorded and cancelled on logout or
  account deletion. Uses its own notification channel and honours
  POST_NOTIFICATIONS on Android 13+.

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

## AI helper (optional)

Two optional helpers on the signed-in user's dashboard, backed by your own
OpenRouter key through one Edge Function. **Nothing changes until the
`ai-coach` function is deployed** — without it the dashboard keeps showing the
local fact of the day and the helper screen says *"The AI helper is not set up
yet"*. Setup: `RUNBOOK.md` Stage C (two SQL files, one secret, one deploy; no
client secrets and no new app dependency).

- **"Can I donate?"** — general, plain-text answers about donating. Stated on
  the screen and enforced in the model's instructions: this is information,
  never individual medical advice, and only a licensed blood bank's screening
  actually decides.
- **Daily motivation** — the card shows a short line from the shared daily
  quote bank, fetched at most once per calendar day, with the local fact bank
  as the offline / failure fallback.
- **Privacy, by construction:** **no personal data leaves the phone.** The
  payload is the request kind and, for a question, the question itself —
  nothing else. (An earlier version sent a first name, donation count, days
  since the last donation and blood group; that is gone, and a unit test fails
  if any of those keys comes back.) Nothing is stored server-side, and a
  session with no token never reaches the provider at all.
- **Limits:** questions ≤ 500 chars, replies ≤ 600, **5 questions per user per
  day**, and a project cap of 40 provider calls a day.
- **Built for the free tier:** OpenRouter's free models allow roughly 50
  requests a day for the whole account, so the motivational line is generated
  **once per day for everyone** — the first request of a day asks for ~30
  quotes in a single call, validates them (length, no medical claims, no
  personal data) and stores them; every other user that day is served from the
  table for free. No scheduled job. The model is `openrouter/free` by default,
  with a fallback list of other `:free` models.

## Phase 1 — quick wins (contacts, sharing, Urdu, reminder)

**SQL files to run in Supabase: none.** Phase 1 adds no tables, columns or RLS
policies. It only reads the contact numbers the existing `donor_phone` and
`request_contact` RPCs already gate, so a build without an anon key still works
fully offline. There is no SQLite schema change either: `DB_VERSION` stays at 8.

- **Call** opens the dialler with `tel:+92…` (ACTION_DIAL, no CALL_PHONE
  permission). **WhatsApp** opens `https://wa.me/<number>`; the two package
  names are declared as `<queries>` for Android 11+ package visibility, and a
  phone without either app gets a plain message. Both buttons are hidden when
  `PhoneNumber.normalize()` rejects the stored value.
- **Share request** never sends a contact the account cannot see: the RLS-gated
  `contact` column is blank for everyone except the requester and a manager,
  and a blank line is dropped from the share text.
- **Language** lives in Settings. AppCompat's per-app locales persist the
  choice across restarts; Urdu is right-to-left and the touched layouts use
  `start`/`end` margins and padding, never `left`/`right`.
- **Donation reminder** is a unique one-time WorkManager job
  (`donation-eligibility-reminder`) due at 09:00 on the 90th day after the last
  recorded donation. It reads only local SQLite, carries a linear backoff, and
  posts on its own `donation_reminders` channel. Both home screens already ask
  for POST_NOTIFICATIONS on Android 13+; declining it is respected and logged.
  The job is replaced when a donation is recorded and cancelled on logout and
  on account deletion.

## Phase 2 — medium features (navigate, certificate, badges, timeline, reports)

**SQL files to run in Supabase (Phase 2):**
`sql/applied/supabase_phase2_migration.sql` — a new `request_status_history`
table, role-aware RLS (read by the requester of the request or a manager;
append-only, no UPDATE/DELETE policy), a server trigger that appends one row per
real status change, and a `Fulfilled → requester` push trigger. Rollback is
`sql/applied/supabase_phase2_migration_rollback.sql`. Apply it **after**
`supabase_role_policies.sql` and `supabase_fcm_triggers.sql`. The Supabase Edge
Function `push-notify` also gained a `fulfilled` handler.
**Local SQLite:** `DB_VERSION` bumped 8 → 9; `onUpgrade` adds the same table
additively, so an upgrade never wipes data.

- **Navigate** (geo intent) — opens the map at the request's hospital with
  `geo:` URI. No Maps SDK, no API key, no location permission; a missing hospital
  name or absent maps app shows a message instead of crashing.
- **Donation certificate** — a one-page `PdfDocument` with the donor's name,
  blood group and last donation date, shared through the already-declared
  `FileProvider` (no storage permission). A donor with no recorded donation is
  told to record one first.
- **Badges / milestones** — `DonationStats` derives `FIRST / 3 / 5 / 10 /
  LIFESAVER` badges from the donation history; shown on the profile and home
  screens. The "helped" count is a clearly-labelled estimate (up to 3 patients
  per donation).
- **Request status timeline** — the first `ViewModel` + coroutines screen:
  `request_status_history` is shown oldest-first, pulled from Supabase when
  online (local copy shown first, so rotation never blanks), with `STARTED`
  collection via `repeatOnLifecycle`. Every status string is translated.
- **Manager CSV report** — donations this month, current stock and low-stock
  groups in one file. Every cell goes through `CsvCells`, which escapes
  commas/quotes **and** neutralises spreadsheet formula injection (`=`, `+`, `-`,
  `@` are prefixed with a quote) so a crafted donor or hospital name cannot run a
  formula when a manager opens the export. Shared via `FileProvider`.
- **Fulfilled push** — mapping the brief's "donor accepts" to the real flow
  (the donor *offers*, the requester *accepts*): the existing new-offer push to
  the requester is kept, and a new `Fulfilled → requester` push is added.

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
  A username without an email is refused: every account is a Supabase Auth
  account, and the manager role is read from the token's `app_metadata`. Use
  `tayyabuq1@gmail.com` to work against the shared data; everyone else lands
  on the donor home screen.
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
  (`supabase_policies_review_DO_NOT_APPLY.sql`, permissive anon
  access) must NOT be applied — v1.4 replaces it with authenticated-only
  policies. The filename is the warning.

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
`./gradlew assembleRelease -PSUPABASE_URL=... -PSUPABASE_ANON_KEY=...`.
Without them the app runs offline on SQLite, like v1.2.

`assembleDebug` still exists for a quick local run, but **no debug APK is ever
built or published**: a debug-signed build cannot update an installed copy in
place, so the only APK that leaves CI is the signed release one.

## Releases and updates

**A push to `main` that changes the app is the release.** The `Release APK`
workflow runs the SQL checks, the unit tests, builds the signed APK, tags
`v<versionName>` and publishes a normal release that shows as **Latest**. No
debug APKs and no manual tagging — see `RELEASING.md` for the gates and the
rollback.

The only manual step is bumping `versionCode` (+1) and `versionName` in
`app/build.gradle.kts`, and adding a `## <versionName>` section to
`CHANGELOG.md` in the same commit; the workflow uses that section as the
release notes and refuses to publish if the version did not increase.

**In the app**, the updater checks on launch and every ~12 hours in the
background, posts a notification when a newer `versionCode` exists, and offers
the update on the dashboard. The APK is downloaded with a progress dialog, its
signing certificate is compared with the installed app's before anything is
installed, and the install is handed to `PackageInstaller` with
`USER_ACTION_NOT_REQUIRED`. Android still asks once for "install unknown apps"
for this app, and on Android 8–11 the system may still show its own
confirmation screen.

## Accounts

**Forgot password?** on the sign-in screen runs Supabase's code flow: the app
asks for a 6-digit code by email, the user types it with a new password, and
the code is exchanged for a session — because only a session may change a
password. It needs the **Reset password** email template to show
`{{ .Token }}`, and a real SMTP server: Supabase's built-in one sends to
organisation members only, about 2 messages an hour.

**Settings → Delete my account** states what goes and what stays, and asks you
to type DELETE. The account itself is removed by a `delete-account` Edge
Function, because deleting an Auth user needs the service role key and that key
never leaves the server. It takes the account from the caller's verified token,
refuses managers, and keeps the donation history — the blood bank's own record —
by anonymising the donor row instead of deleting it.

Setup steps, rate limits and the phone checklist are in `RUNBOOK.md` Stage D.

## Project structure

```
app/src/main/java/com/tayyab/blooddonation/
├── db/DbHelper.kt            SQLite schema + all queries (v3 adds the
│                             seeded manager row; v7 moves it to
│                             tayyabuq1@gmail.com) + v1.3 upsert /
│                             explicit-id merge helpers + v1.4 profile relink
├── model/Models.kt           Donor, Donation, BloodRequest, UserAccount
├── util/BloodCompatibility.kt  Compatibility table + eligibility rules
├── util/Session.kt           Signed-in account + password hashing +
│                             v1.4 Auth tokens + pending-profile flag
├── util/SupabaseAuth.kt      v1.4: Supabase Auth client (HttpURLConnection)
├── util/SupabaseClient.kt    PostgREST client (HttpURLConnection);
│                             v1.4: user Bearer token + one 401 refresh
├── util/SupabaseSync.kt      Online/offline pull + push logic
├── util/Jwt.kt               Reads app_metadata.role from the access token
├── util/AccountDeletion.kt   "Delete my account": calls the Edge Function
│                             and reads deleted / partial / refused
└── ui/                       Activities and RecyclerView adapters
    ├── LoginActivity.kt      Sign-in: email via Supabase Auth
    │                         (launcher activity)
    ├── UserHomeActivity.kt   Home for everyone who is not a manager
    ├── RegisterActivity.kt   Email registration: Auth account + donor
    │                         profile linked by auth_id
    ├── ForgotPasswordActivity.kt  Recovery by 6-digit emailed code
    ├── DeleteAccountActivity.kt   Delete my account (confirmation)
    ├── SettingsActivity.kt   Notifications, connection check, delete
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
