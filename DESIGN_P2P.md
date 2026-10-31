# Peer-to-peer design (P2P)

Companion to `SUPABASE_SETUP.md`. Describes the model for letting any signed-in
user request blood and offer to donate, with the manager keeping full access and
moderation. Two pages, on purpose.

## Tables

`requests` — existing table, additive columns only:

| column | type | notes |
|---|---|---|
| `requester_id` | uuid | the Auth user who created it; null on legacy/manager rows |
| `city` | text | where the blood is needed; drives "nearby first" |
| `status` | text | `Pending` `Matched` `Fulfilled` `Cancelled` `Expired` |
| `fulfilled_by` | text | `stock` or `donor`; null until fulfilled |
| `needed_by` | date | optional; drives `Expired` |

Existing rows keep working: `status = 'Pending'` and a null `requester_id` means
"manager-created", which only the manager can see the offers on.

`donors` — existing table, additive: `available_to_donate boolean not null default false`.

`offers` — new:

| column | notes |
|---|---|
| `id`, `request_id`, `donor_id`, `created_at` | |
| `status` | `Offered` `Accepted` `Declined` `Completed` |
| `donor_confirmed_at`, `requester_confirmed_at` | the two halves of "both confirm" |

Unique on `(request_id, donor_id)`. A partial unique index on `request_id where
status = 'Accepted'` allows at most one accepted offer per request.

`contact_details` — new: `user_id uuid primary key`, `phone text`. Separated from
`donors` because Row Level Security is row-level, not column-level.

`reports` — new: `id`, `request_id`, `reporter_id`, `reason`, `created_at`, `resolved_at`.

## Statuses

Request: `Pending` → `Matched` (an offer is accepted) → `Fulfilled` (both sides
confirm) · `Cancelled` (requester or manager, any time before Fulfilled) ·
`Expired` (past `needed_by`, still Pending).

Offer: `Offered` → `Accepted` or `Declined`. `Accepted` → `Completed` once both
sides confirm. Declining one offer leaves the request `Pending` so other donors
can still offer.

## Screens

- **Home** — daily motivation card, badges, streak, "people you may have helped".
- **Requests** — open requests the donor's group can serve, own city first.
- **My requests** — created by me; cancel my own; capped at 3 open.
- **Request detail** — "I can donate" for donors, offer list for the requester
  (first name, group, city), Report button, safety notice.
- **Settings** — tips toggle (default off), notification permission state.
- **Profile** — "I'm available to donate" switch, gated on eligibility.
- **Manager** — everything above plus moderation: cancel/remove any request, see
  all offers and reports.

## Security rules

`supabase_p2p_policies.sql` **replaces** `supabase_role_policies.sql` (that file
was never applied; this one supersedes it and should be the only policy file used).

- `requests`: readable by any authenticated user. Insert/update/delete only where
  `requester_id = auth.uid()`, plus manager. Cancelling is an update to `status`.
- `offers`: the donor sees and changes their own; the requester sees offers on
  their own request; the manager sees all. Insert requires the donor to have a
  donor profile and `available_to_donate`.
- `contact_details`: readable by the owner, the manager, or a user who is the
  other party on an `Accepted`/`Completed` offer. That is the only thing that
  reveals a phone number, and it is enforced by the database, not the UI.
- `donors`: a donor may update only their own row, and only `available_to_donate`
  (the rest is manager-editable).
- Manager role comes from `auth.jwt() -> 'app_metadata' ->> 'role' = 'MANAGER'`.

## What is not possible, and why

1. **Hiding one column of a row.** RLS filters rows, not columns, so a phone
   number on `donors` is either visible to everyone who can read the row or
   invisible to the owner too. Hence `contact_details`.
2. **Showing a phone only after acceptance, without moving it.** Not expressible
   as a column rule. Even with the split table, the rule is "readable if an
   Accepted offer exists", which is what the policy above does — the client must
   still not cache it before then.
3. **A truly atomic "both confirmed".** Two devices cannot compare-and-set
   without a server-side function. Phase 2 will do the completion in a
   `security definer` function so the donation row, the donor's last-donation
   date and the request status change in one transaction. Without that, the
   worst case is a duplicate donation row, which the `(donor_id, date)` unique
   index will refuse.
4. **Expiring requests on a timer.** Postgres has no scheduler unless `pg_cron`
   is enabled. Plan: expire lazily — the client treats a past-`needed_by`
   `Pending` request as `Expired`, and the manager can force it.
5. **Real-time delivery.** Until Phase 3 (FCM), offers and acceptances arrive
   by the existing ~15-minute poll. Delivery is approximate; the README says so.
6. **Verifying anything.** The app cannot check blood group, identity, health or
   that a donation happened. Every value is self-reported, which is why the
   safety notice appears on both request and donate screens.
7. **Offline.** Phase 1 works offline. Requests and offers do not: they are
   multi-user state and need the server.
8. **A hard 3-open-requests limit in RLS alone.** Counting a user's rows inside
   a policy is possible but expensive and racy. Plan: a `BEFORE INSERT` trigger
   raises when the user already has 3 open requests, plus the client check.
9. **Moderation without an Auth session.** The seeded `manager` / `manager123`
   login has no `app_metadata` claim, so it is not a manager to the database and
   cannot moderate. Only `manager@blood.local` signed in through Auth can.
10. **Peer donations touching blood bank stock.** They must not. `fulfilled_by =
    'donor'` records the donation on the donor's history only; the stock table is
    for the blood bank's own units.

## Rollout order

1. Run `supabase_p2p_migration.sql` (additive columns, new tables, indexes).
2. Set the manager's role claim (statement at the top of the policy file).
3. Run `supabase_p2p_policies.sql`.
4. Sign out and in again, so the JWT carries the role claim.
5. Install the new APK.

Older app versions keep working during rollout but degrade: they write
`status = 'Pending'` and never set `requester_id`, so their requests are
manager-only, and they cannot see offers. They also lose the phone number shown
on the donor list, because the migration copies it into `contact_details` and
then drops `donors.phone` — keeping it would leave every number readable by
any signed-in user, which is the thing the split exists to prevent.
