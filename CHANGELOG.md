# Changelog

Notable changes to the Blood Donation Management System. Versions follow the
`versionName` in `app/build.gradle.kts`.

## 1.6 — 2026-10-31

### Urgent request alerts

- A signed-in donor is notified when a pending **Urgent** or **Critical** request
  appears that their blood group can serve, provided they are eligible to donate.
  Requests marked **Normal** do not alert — waking someone for routine stock
  replenishment would only train donors to ignore the notifications.
- Delivery is **approximate**. A `WorkManager` job polls about every 15 minutes
  (the platform minimum for periodic work) and only while online, so an alert can
  arrive late, and Doze can delay it further. There is no push service, no
  Firebase and no server of our own.
- **Once per request, ever.** Notified request ids are stored on the device, so a
  restart or a later poll cannot produce a second notification for the same
  request. Fulfilled requests stay remembered.
- **Lock-screen privacy.** The notification is `VISIBILITY_PRIVATE` with a
  generic public version ("A blood request needs your help"), so the hospital and
  contact number are not readable on a locked screen. The patient's name is never
  shown at all.
- Tapping an alert opens that request's detail screen.
- **Android 13+** asks for the notification permission once, behind a short
  explanation. Declining is respected: the prompt does not return on every
  launch, and granting it later from system settings takes effect automatically.
- **Debug builds** gain a "Check requests now" button that runs the same worker
  immediately instead of waiting for the next poll, and reports how many alerts
  it sent. The button is hidden in release builds.

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
