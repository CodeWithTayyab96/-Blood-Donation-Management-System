# Changelog

Notable changes to the Blood Donation Management System. Versions follow the
`versionName` in `app/build.gradle.kts`.

## Unreleased — Phase 2: peer-to-peer requests

Any signed-in user can now ask for blood, and donors can offer to help
directly. **Requires the two new SQL files to be applied** — see
`supabase_p2p_migration.sql` (then `supabase_p2p_policies.sql`) and the
safe order in `DESIGN_P2P.md §5`. Until the migration is applied the app
simply behaves as before; the schema change is additive.

- **Post a request** (`CreateRequestActivity`) — any signed-in user, even
  without a donor profile: blood group, city, units, urgency, an optional
  needed-by date and a contact number. Capped at **3 open requests**; the
  cap is enforced by a server trigger as well as the app.
- **Browse / My requests** — the request list now has two views. *Browse*
  shows open requests other people posted that this donor's group can
  actually give to, nearest city first. *My requests* shows your own, with
  Cancel. Managers still see everything.
- **Offers** — a donor taps *I can donate*; the requester sees the donor's
  first name, blood group and city, and Accepts or Declines. Accepting moves
  the request to *Matched*, and **only then** is a phone number revealed to
  the other side. Phone numbers live in their own `contact_details` table and
  the database — not the app — decides who may read them.
- **Both sides confirm** before a donation is recorded. A peer donation is
  recorded on the donor's history and deliberately **does not** add units to
  blood-bank stock: it never entered the bank.
- **"I'm available to donate"** switch on the profile, shown only while the
  donor is eligible. Offering is refused by the server unless it is on.
- **Reports and moderation** — anyone can report a request; managers get a
  new moderation screen listing every report and offer, and can cancel a
  reported request.
- SQLite **DB v6**: additive `ALTER TABLE` plus three new tables, so an
  upgrade keeps every existing row. Legacy/manager rows keep a null
  `requester_id` and are treated as manager-owned.
- Unit tests for the request/offer state machine (`P2pRules`).

Known limits, stated plainly: nothing is verified (blood group, identity and
health are all self-reported) — hence the safety notice on the request
screen. There is still **no real-time**: until Phase 3 (FCM) offers arrive
on the existing ~15-minute poll.

## Unreleased — Phase 1: motivation, milestones, tips

All offline; no server or database changes.

- **Daily motivation card** on the dashboard: one short, accurate fact per day
  (WHO / Red Cross style — no "cures", weight-loss or disease-prevention
  claims), stable for the day and the same on every device. 32 facts in the
  bank so a month does not repeat.
- **Donation milestones**, computed locally from the donation history a donor
  already has: badges at the 1st, 3rd, 5th and 10th donation, a current streak
  (consecutive donations within 180 days), and a "you may have helped up to N
  people" estimate (3 patients per donation, labelled an estimate). Shown only
  when the signed-in person has a donor profile.
- **Optional daily tip notification** — a practical, non-medical tip, on its
  own low-importance channel. **Off by default**, at most one per day, never
  between 21:00 and 09:00, with a toggle in a new **Settings** screen. Reuses
  the existing notification permission handling; a missing permission is
  swallowed, not crashed on.
- **Thank-you message** after a donation is recorded.
- Unit tests for the milestone/streak math and the tip send-window rule.

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

### Fixes

- **Dark-mode contrast.** The dashboard's "Stock by blood group" heading and
  summary were invisible in dark mode: the page background is a fixed light pink
  while those two views inherited the dark theme's white text. They now set their
  colour explicitly.
- **Fulfil message contradicted the screen.** Fulfilling used the server's stock
  but the screen shows the local count, so "In stock: 2" could sit next to "not
  enough units". The message now names the server's number and the screen
  refreshes from it.
- **Silent half-synced donations.** A donation whose stock increment failed was
  reported as a plain success, leaving the local and online counts quietly
  different. It now says the stock did not update.
- **Local-only sessions are called out.** An account with no Supabase session
  cannot read or write the backend at all, because the policies only accept
  authenticated users. The dashboard now says so instead of looking like it is
  syncing.

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
