# Changelog

Notable changes to the Blood Donation Management System. Versions follow the
`versionName` in `app/build.gradle.kts`.

## 1.9 — 2026-11-01

versionCode 10. The app now has two homes: one for donors, one for the blood
bank. The manager role comes from the sign-in token, not from the phone.

### A home for ordinary users

- Signing in as anything other than a manager opens a new home screen: today's
  fact, an "I want to donate" switch with the next eligible date, and one
  button per job — ask for blood, my requests, browse requests, the AI helper,
  my profile, settings, log out. The manager dashboard is unchanged and only
  managers see it.
- **The role is read from the JWT's `app_metadata`, never from the phone.** The
  local `users` row no longer grants anything, so editing it — or an old build
  that wrote MANAGER into it — cannot unlock the manager screens. A token-less
  (offline) session is an ordinary user, which is correct: every manager action
  needs the server anyway.

### Ordinary users cannot touch the blood bank's records

- **Recording a donation** is manager-only in the database now, not just on the
  screen. A donor's own peer donation is still recorded, by
  `complete_peer_donation()`.
- **Stock** can only be moved by a manager: `adjust_stock()` refuses anyone
  whose token is not MANAGER. The grant has to stay (managers are the
  `authenticated` role too), so the guard is inside the function.
- What an ordinary user can still do through the API: read donors (no phone
  numbers), read requests (no contact numbers), read their own contact row and
  any pair they are party to on an accepted offer, create/update/delete their
  own requests, create offers when available, confirm their side of a
  donation, file a report, and manage their own device token. Nothing else.

### Sign-in

- **The local-only `manager` / `manager123` login is gone.** A username without
  an email is refused, and the seeded row is deleted from the database on
  upgrade. Every account is a Supabase Auth account.
- **Registration, requests, offers and donations are online-only.** They no
  longer save a local-only copy that nobody else can see: if the server refuses
  or there is no connection, the screen says so and writes nothing. Cached data
  is still readable offline.
- **Real reasons instead of "not synced":** wrong email or password, email not
  confirmed, too many attempts, no internet, or the server's own message with
  its HTTP status. An unconfirmed address offers **"Resend confirmation email"**
  right there.
- **Settings → Connection check:** five checks in the order they can fail —
  configuration, server reachable, session valid, a read works, helper
  functions answer — each with the reason.

## 1.8 — 2026-11-01

versionCode 9. Updates install themselves, and a release is now something a
push to `main` does.

### In-app updates

- The app checks for a new version **at launch and every ~12 hours** in the
  background (WorkManager, network required). A newer `versionCode` produces a
  notification; tapping it opens the app's update prompt. While the app is
  open, the prompt appears directly instead.
- The download shows real progress, and the file is **verified before anything
  is installed**: it must be this package, signed with the same certificate as
  the installed app, and a higher `versionCode`. A mismatch is reported in
  words instead of being handed to Android to fail obscurely.
- Installation goes through **`PackageInstaller` with
  `USER_ACTION_NOT_REQUIRED`** on Android 12+, so a same-signer update installs
  without the system's "Do you want to update?" screen. Android still asks once
  for **"install unknown apps"** for this app, and on Android 8–11 the system
  may still show its own confirmation. The result comes back to the app, which
  says "update installed" or shows the system's reason for refusing.

### One signed build, published automatically

- A push to `main` that changes `app/`, the build files or this changelog runs
  the SQL checks and the unit tests, builds the **signed** APK, tags
  `v<versionName>` and publishes a **normal** release — Latest — with
  `app-release.apk`, `update.json` and the notes from this file.
- **No debug APKs are built anywhere any more.** An unsigned or debug-signed
  build can never update an installed copy in place, so it was never worth
  producing.
- The release refuses to run if `versionCode` did not increase, or if the tag
  already exists. `[skip release]` in a commit message opts out.

## 1.7 — 2026-11-01 (pre-release)

versionCode 8. Server-side privacy work, plus the client changes it requires.
Install this build **before** applying the updated SQL in RUNBOOK Stage A: the
previous build asks for `select=*` on `donors` and `requests`, which the new
column privileges reject.

### Phone numbers are no longer readable columns

- `donors.phone` and `requests.contact` are out of the `authenticated` SELECT
  grant. The app asks for explicit columns and reads a number through
  `donor_phone()` / `request_contact()`, which answer only for the owner and
  the manager. A signed-in user who is not a manager can no longer read
  anyone else's phone number — not from the donor list, the request screen, or
  the API. What they can still read about other donors: name, blood group,
  city, age, gender, weight, last-donation date and availability.
- The donor list shows city and age only; the donor detail shows
  "Phone: hidden" unless the number is yours or you are the manager; a
  request shows "Contact: shared after you accept an offer" until it is
  accepted.
- Cached numbers learned under the old permissive policies are dropped on the
  next sync unless the row is your own (or you are the manager).

### Registration works under the final policies

- New `"donors insert own"` policy: a donor may create their own profile row
  (`auth_id = auth.uid()`) and nothing else — not a row for another auth id,
  not one with no auth id. Without it, registration's profile insert was
  rejected as soon as the permissive policies were replaced, and a donor who
  could not create a profile could never offer to help.

### Policy files are order-independent

- `supabase_role_policies.sql` and `supabase_p2p_policies.sql` can be applied
  in either order, and either can be re-run. Both end in the same 27-policy
  state, with `"requests write manager"` gone once the P2P policies are in.
- New `supabase_verify_policies.sql` prints `ALL POLICIES OK` or one
  `PROBLEM` row per mismatch. CI runs it after the forward pass and again
  after re-applying the two policy files in the opposite order.

## 1.6 — 2026-10-31 (pre-release)

Not tagged or published yet. This section is the source of the release notes:
the GitHub release body in `.github/workflows/release.yml` carries the same
list in short form, so the two cannot drift apart. Everything below is already
on `main` and green in CI.

### Peer-to-peer requests

- **Post a request** (`CreateRequestActivity`) — any signed-in user, even one
  without a donor profile: blood group, city, units, urgency, an optional
  needed-by date and a contact number. Capped at **3 open requests**; the cap
  is enforced by a server trigger as well as by the app.
- **Browse / My requests** — *Browse* shows open requests other people posted
  that this donor's group can actually give to, nearest city first. *My
  requests* shows your own, with Cancel. Managers still see everything.
- **Offers** — a donor taps *I can donate*; the requester sees the donor's
  first name, blood group and city, and Accepts or Declines. Accepting moves
  the request to *Matched*, and **only then** is a phone number revealed — to
  the two parties, nobody else. Phones live in their own `contact_details`
  table and the database, not the app, decides who may read them.
- **Both sides confirm** before a donation is recorded. A peer donation is
  recorded on the donor's history and deliberately **does not** add units to
  blood-bank stock: it never entered the bank.
- **"I'm available to donate"** switch on the profile, shown only while the
  donor is eligible. The server refuses an offer unless it is on.
- **Moderation** — a manager-only screen listing reports and peer offers.
- Limits, stated plainly: without the push deployment (below) offers and
  acceptances arrive by the ~15-minute poll, not instantly; request expiry is
  lazy and client-driven, not `pg_cron`; eligibility, health and identity are
  self-reported and unverified.

### Alerts for urgent requests

- A signed-in donor is notified when a pending **Urgent** or **Critical**
  request appears that their blood group can serve and they are eligible for.
  **Normal** requests never alert.
- Delivery is approximate: a `WorkManager` job polls about every 15 minutes
  and only while online. Doze can delay it further.
- **Once per request, ever** — notified ids are remembered on the device, so a
  restart or a later poll cannot repeat a notification.
- **Lock-screen privacy** — the notification is `VISIBILITY_PRIVATE` with a
  generic public version; hospital, contact number and patient name are never
  shown. Tapping opens that request.
- **Android 13+** asks for the notification permission once, behind a short
  explanation; declining is respected and granting it later takes effect
  without a restart.
- Debug builds gain a "Check requests now" button that runs the worker
  immediately; it is hidden in release builds.

### Motivation, milestones and tips

All offline; no server or database changes.

- **Daily motivation card** on the dashboard: one short, accurate fact per day
  (WHO / Red Cross style — no "cures", weight-loss or disease-prevention
  claims), stable for the day and the same on every device. 32 facts, so a
  month does not repeat.
- **Donation milestones**, computed from the history a donor already has:
  badges at the 1st, 3rd, 5th and 10th donation, a current streak (consecutive
  donations within 180 days), and a "you may have helped up to N people"
  estimate (3 patients per donation, labelled an estimate). Shown only when
  the signed-in person has a donor profile.
- **Optional daily tip** — a practical, non-medical tip on its own
  low-importance channel. **Off by default**, at most one per day, never
  between 21:00 and 09:00, with a toggle in a new **Settings** screen.
- **Thank-you message** after a donation is recorded.
- Unit tests for the milestone/streak math and the tip send-window rule.

### Push notifications (optional — needs a Firebase project)

**The published APK is built without Firebase values** (no `FIREBASE_*`
repository secrets exist), so it polls and never pushes. The code is complete
and inert until those secrets are added — see `PUSH_SETUP.md`.

- **FCM without the google-services plugin** — the four identifiers arrive as
  BuildConfig fields and `FcmPush` initialises Firebase explicitly, so a build
  with no Firebase values compiles and behaves exactly like 1.5.
- **Device tokens** (`device_tokens`, with migration/policies/rollbacks): a
  user may only read and write their own rows; the manager may read all. Rows
  are upserted at sign-in and on token rotation, deleted on sign-out.
- **Three server events** (pg_net triggers): offer created → the requester's
  devices; offer accepted → the donor's devices; Urgent/Critical request
  created → every compatible, available, eligible donor's devices.
- **`push-notify` Edge Function** verifies the webhook secret, resolves
  recipients, signs a service-account JWT itself and sends data-only FCM v1
  messages. The service-account JSON lives only in a Supabase secret.
- **Client notifications** for offers and acceptances on their own
  `peer_activity` channel — first name, blood group, city, never a phone
  number. Polling stays as the fallback, and both paths share the same
  remembered-id files, so nothing is announced twice.

### AI helper (optional — needs the `ai-coach` function deployed)

**Everything works exactly as before until that function is deployed** — see
`AI_SETUP.md`. No new app dependency: `HttpURLConnection` + `org.json`.

- **"Can I donate? (AI helper)"** on every signed-in user's dashboard, with a
  visible not-medical-advice disclaimer, questions capped at 500 characters,
  answers at 600, and one fallback answer whenever the helper is offline,
  unconfigured or rate-limited: ask a licensed blood bank.
- **Personalised daily motivation line** — the fact card is upgraded to a
  once-a-day personalised line (fetched at most once per calendar day; a failed
  day is not retried until tomorrow). Without AI, the local fact bank still
  appears.
- **Privacy by construction** — the only personal data that ever reaches the
  provider is four neutral facts (first name, donation count, days since last
  donation, blood group). No phone, city, age, gender, weight, Auth id or
  token is serialised, and the exact wire payload is pinned by
  `AiCoachPayloadTest` against a hand-built JSON string.
- **Guardrails both sides** — system prompts forbid medical claims and asking
  for more personal data; the function clamps replies to 600 characters and
  answers only signed-in users (verify_jwt ON, token re-verified server-side).
- **Rate limit** — 30 calls per user per 24 h in `ai_coach_usage`, RLS ON with
  no policies, so only the function's service role can touch it.

### Fixes

- **Dark-mode contrast.** The dashboard's "Stock by blood group" heading and
  summary were invisible in dark mode; they now set their colour explicitly.
- **Fulfil message contradicted the screen.** The message now names the
  server's number and the screen refreshes from it.
- **Silent half-synced donations.** A donation whose stock increment failed was
  reported as a plain success; it now says the stock did not update.
- **Local-only sessions are called out.** The dashboard says an account with no
  Supabase session cannot read or write the backend, instead of looking like it
  is syncing.

## 1.5 — 2026-10-31

A correctness and security pass over v1.4, driven by a full code review. No
feature was added or removed.

### Security

- **Dropped the legacy `users` table.** It stored password hashes and was the
  only table with Row Level Security switched off, so the anon key that ships
  in every APK could read every hash through PostgREST. It is no longer created
  by `supabase_migration.sql`; `fix_users_table.sql` drops it on a project that
  already ran the old migration.
- **Session store encrypted.** Tokens and the offline password hash now live in
  `EncryptedSharedPreferences` backed by the Android Keystore. The old plain
  file is migrated once and then deleted. `allowBackup` is off and data
  extraction rules exclude every domain for cloud backup and device transfer.
- **Passwords use PBKDF2.** `PBKDF2WithHmacSHA256`, 200,000 iterations,
  256-bit, random 16-byte salt, with the iteration count stored alongside the
  hash. The previous single-round SHA-256 cannot be verified, so affected
  accounts are asked to sign in online once — which rewrites the hash — instead
  of being told their password is wrong. The two seeded demo managers are
  repaired automatically during the database upgrade.
- **Logout revokes the session.** It now calls `POST /auth/v1/logout` with the
  access token on a background thread. A network failure cannot block or undo
  the local logout.
- **Role checks in the UI.** Creating a request and fulfilling one are now
  manager-only in the app. `supabase_role_policies.sql` adds the matching
  server-side policies; **it is not applied by default** and must be run
  deliberately.

### Data correctness

- **`onUpgrade` no longer drops tables.** The old `else` branch dropped every
  table, so the first schema-version bump after v1.4 would have wiped all
  donors, donations, requests, stock and accounts. Migration steps are now
  sequential and additive.
- **Donations are no longer duplicated.** A donation recorded online is stored
  locally under its server id instead of being inserted a second time with a
  fresh local id and downloaded again.
- **Transactions.** Recording a donation and fulfilling a request each run in a
  single transaction, so stock, donor dates and history cannot disagree.
- **Foreign keys enforced.** `PRAGMA foreign_keys` is on, `donations.donor_id`
  cascades on delete, and the upserts no longer use `CONFLICT_REPLACE` (which
  would have cascade-deleted a donor's history during an ordinary pull).
- **Atomic stock changes.** Stock is adjusted by `adjust_stock()` in one
  statement rather than read-modify-write, which lost a unit when two devices
  recorded a donation at the same time.
- **Duplicate accounts.** `registerDonorAccount` and `registerDonorAccountWithId`
  run in a transaction and report a duplicate username instead of leaving an
  orphan donor with no account.

### Robustness

- Age, weight and units are validated instead of crashing on `toInt()`.
- One shared single-thread executor replaces the bare `Thread` per screen.
- AppCompat dialogs throughout; the deprecated `ProgressDialog` is gone.
- `getDonor`, `getRequest` and `pendingRequestCount` use `WHERE` queries
  instead of scanning every row.

### Tests and CI

- Unit tests for the transfusion matrix (all 64 donor/recipient pairs), the
  90-day rule and the `nextEligibleDate` boundary, plus the version comparison
  used by the in-app updater.
- `.github/workflows/ci.yml` runs `testDebugUnitTest` and `assembleDebug` on
  every branch and pull request, and uploads the debug APK.

### Upgrading from 1.4

- The database upgrades in place to v5. Existing donors, donations, requests
  and stock are preserved.
- Existing sessions carry over — the prefs migration keeps you signed in.
- Donor accounts with a pre-PBKDF2 password hash must sign in online once.
- `manager` / `manager123` keeps working without a re-login.

## 1.4

Supabase Auth accounts, donor profiles linked by `auth_id`, authenticated-only
Row Level Security, and one automatic token refresh per 401.

## 1.3

Supabase PostgREST backend for donors, donations, requests and stock, with
SQLite as the offline fallback.

## 1.2

Local SQLite only.
