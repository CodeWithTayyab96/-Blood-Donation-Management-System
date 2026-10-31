# Peer-to-peer design (P2P)

Companion to `SUPABASE_SETUP.md`. Lets any signed-in user request blood and offer to
donate; donors and requesters connect directly; the manager keeps full access and
moderates. Phase 1 (motivation, milestones, tips) is already shipped. This document
covers Phase 2 only. Two pages, on purpose.

## 1. Tables, columns, and the SQLite migration

`DB_VERSION` goes **5 → 6**. All changes are additive; `onUpgrade` runs `ALTER TABLE … ADD
COLUMN` (SQLite supports this without a table rebuild) plus `CREATE TABLE` for the new
tables, so no existing data is dropped.

**requests** (existing, `status` already exists with `Pending`/`Fulfilled`):
- `requester_id TEXT` — Supabase auth user id of the creator; **null on legacy/manager rows**.
- `city TEXT` — where blood is needed; drives "nearby first".
- `fulfilled_by TEXT` — `stock` or `donor`; null until fulfilled.
- `needed_by TEXT` (date) — optional; drives lazy `Expired`.

**donors** (existing): add `auth_id TEXT` (currently **missing** — required to own and
link a peer profile) and `available_to_donate INTEGER NOT NULL DEFAULT 0`.

**offers** (new): `id`, `request_id`, `donor_id`, `status` (`Offered`/`Accepted`/`Declined`/
`Completed`), `donor_confirmed_at`, `requester_confirmed_at`, `created_at`. Unique on
`(request_id, donor_id)`; a partial unique index on `request_id WHERE status='Accepted'`
allows at most one accepted offer per request.

**contact_details** (new): `user_id TEXT PRIMARY KEY`, `phone TEXT`. Split from `donors`
because Row Level Security is row-level, not column-level — a phone on `donors` is either
visible to everyone who can read the row or hidden from the owner too.

**reports** (new): `id`, `request_id`, `reporter_id`, `reason`, `created_at`, `resolved_at`.

**What happens to existing rows:** manager-created requests keep `status='Pending'` and a
null `requester_id` → treated as manager-owned; only the manager sees their offers and they
are excluded from "browse requests". Existing `donors` get `available_to_donate=0`; the
migration copies `donors.phone` into `contact_details` keyed by `auth_id` (rows without an
`auth_id`, e.g. the seeded demo donors, get no contact row — they are manager data, not
peer accounts). `users` (the local account mirror) is untouched.

The matching Supabase SQL lives in `supabase_p2p_migration.sql` (additive, with a copy of
phones into `contact_details` and a rollback file `supabase_p2p_migration_rollback.sql`).

## 2. Allowed status transitions

Requests: `Pending` → `Matched` (an offer is accepted) → `Fulfilled` (both sides confirm);
`Pending` → `Cancelled` (requester or manager, any time before Fulfilled);
`Pending` → `Expired` (past `needed_by`, still Pending — evaluated lazily by the client).
`Fulfilled` / `Cancelled` / `Expired` are terminal.

Offers: `Offered` → `Accepted` or `Declined`; `Accepted` → `Completed` once both sides
confirm. One offer row per `(donor, request)`; declining it then re-offering sets it back
to `Offered`. `Declined` / `Completed` are terminal.

## 3. Screens (one line each)

- **Home (`MainActivity`)** — add "Browse requests" (donors) and "My requests" (requesters) entries.
- **CreateRequestActivity** (new) — any signed-in user, even with no donor profile, creates a request (group, city, units, urgency, needed_by); capped at 3 open.
- **RequestListActivity** — split into donor view (compatible + city-first) and "My requests" (own only, cancel own).
- **RequestDetailActivity** — donor sees "I can donate"; requester sees offers (first name, group, city) with Accept/Decline; Report button; safety notice; contact revealed only after accept.
- **Donation completion** (in RequestDetailActivity) — both sides tap "Donation completed"; records once.
- **ProfileActivity** — "I'm available to donate" switch, shown only when eligible; else shows next eligible date.
- **Manager moderation** (new screen) — see all offers and reports, cancel/remove any request.
- **SettingsActivity** — unchanged (tip toggle).

## 4. Server security rules (plain words)

- **requests**: any signed-in user can **read**. **Create / update / delete** only where
  `requester_id = auth.uid()`, plus the manager. Cancelling is an update to `status`.
- **offers**: the donor reads/edits their own; the requester reads offers on their own
  request; the manager reads all. Insert requires the donor to have a profile and
  `available_to_donate = true`.
- **contact_details**: readable only by the owner, the manager, or a user who is the other
  party on an `Accepted`/`Completed` offer. This is the **only** path that reveals a phone
  number, and it is enforced by the database, not the UI.
- **donors**: a donor updates only their own row, and only `available_to_donate`; the
  manager edits the rest.
- **reports**: anyone can insert; only the manager reads.
- **Manager role**: `auth.jwt() -> 'app_metadata' ->> 'role' = 'MANAGER'`. `supabase_p2p_policies.sql`
  replaces `supabase_role_policies.sql` (that file was never applied; this supersedes it).

## 5. What breaks for installed versions, and the safe order

**Breaks:** an older APK writes `status='Pending'` with **no `requester_id`**. The new
insert policy requires `requester_id = auth.uid()`, so the online insert is **denied** and
the app falls back to local SQLite (still works offline, just not shared). Older apps also
cannot see offers, and — because `donors.phone` is no longer the shared source — their
manager donor list may show a blank phone. The SQLite migration only runs for users who
install the new APK; it is non-destructive.

**Safe order:**
1. Backup the Supabase project.
2. Run `supabase_p2p_migration.sql` (additive tables/columns) — non-breaking.
3. Set the manager role claim on `manager@blood.local`.
4. Deploy the new APK (clients now send `requester_id` and read offers).
5. Apply `supabase_p2p_policies.sql` (the enforcing step; older clients then lose online
   request writes until they update — acceptable, they fall back to local).
6. Sign out and in so the JWT carries the role claim.

## 6. What cannot be done or verified

- The app **cannot** verify blood group, health, identity, or that a donation happened.
  Everything is self-reported — hence the safety notice on both request and donate screens.
- **No real-time.** Until Phase 3 (FCM), offers/acceptances arrive by the existing ~15-minute
  poll; delivery is approximate (README states this).
- RLS hides rows, not columns — hence the `contact_details` split; the client must not cache
  a phone before acceptance.
- A truly atomic "both confirmed" needs a server function; Phase 2 uses a `SECURITY DEFINER`
  function so the donation row, donor's last-donation date and request status change in one
  transaction. Without it, the worst case is a duplicate donation row (the `(donor_id, date)`
  unique index refuses the second).
- Timer-based expiry needs `pg_cron` (not assumed); expiry is lazy/client-driven.
- The hard 3-open-requests cap needs a `BEFORE INSERT` trigger plus a client check (RLS alone
  cannot count cheaply).
- The seeded `manager`/`manager123` login has **no Auth claim**, so it cannot moderate
  server-side — only `manager@blood.local` signed in through Auth can.
- Peer donations must **not** touch blood-bank stock (`fulfilled_by='donor'` records on the
  donor's history only).

## 7. Risks and the manual test plan

**Risks:** a miswritten policy can lock everyone out (test on a throwaway project first, keep
the rollback); concurrent both-confirm can duplicate a donation (mitigated by the unique
index + server function); two donors accepting is prevented by the partial unique index; the
anon key in every APK must never read `contact_details` (verify the policy denies anon);
offline-first SQLite can diverge from the server between syncs; the 3-request cap is
bypassable if the trigger is not applied.

**Manual test — three accounts (on a test Supabase project):**
- **Manager** `manager@blood.local` / `manager123`: sees every request, offer and report;
  cancels/removes any request; stock fulfilment sets `fulfilled_by='stock'`.
- **Requester** `requester@test.com` (register, no donor profile needed): creates an `A+`,
  city X, 1-unit, Urgent request; sees it under "My requests"; cancels own; a 4th open
  request is refused at 3; after a donor offers, sees the offer (first name, `A+`, city X);
  Accepts → contact appears; both confirm → request `Fulfilled`.
- **Donor** `donor@test.com` (register, eligible donor profile): sets "available to donate";
  browses open requests compatible with their group, city-first; taps "I can donate" → offer;
  on accept sees the requester contact; both confirm → donation recorded **once**, last-donation
  date updated, stock **unchanged**, request `Fulfilled` with `fulfilled_by='donor'`.
