# Changelog

Notable changes to the Blood Donation Management System. Versions follow the
`versionName` in `app/build.gradle.kts`.

## 1.27 — 2026-11-11

versionCode 28. A performance pass: 10 full-table scans replaced with targeted SQL queries plus 3 new indexes (DB version 12), the three sync paths (pullAll, pullPhase3, pullRequestDetail) now run in parallel, and the N+1 per-row lookups in donor and request sync are batched into single queries. The background pool grows from 1 thread to 3, and the remaining database reads on the main thread (bottom-nav badges, Stock screen, request detail) moved to background. Lists reuse their adapters and layout managers instead of recreating them on every render, and six RecyclerViews skip redundant layout passes. Two small features: the Stock screen now warns when a blood group falls below the low threshold, and the dashboard donor count shows how many donors are currently eligible.

## 1.26 — 2026-11-09

versionCode 27. A full code audit (4 High, 12 Medium, 13 Low findings) and every one of them fixed. The Emergency screen no longer takes a typed blood group: it is a Spinner of the 8 standard groups, so an invalid group can never be queued, and the offline queue now respects the 3-open-requests cap. The Verify Donor screen and the camp/slot screens check the manager role before they draw anything. Resolving a moderation report now syncs to the server instead of being overwritten on the next pull. The AI helper's daily quota is enforced atomically on the server (a new `increment_provider_calls` function, applied as a migration), so two calls at once can no longer both slip under the cap. Release builds now use R8 with ProGuard rules, and six database indexes (DB version 11) speed up the donor, offer, history, attendance and booking lookups. The urgent-request trigger no longer sends the requester's phone number to the notification pipeline (migration file only, applied separately). Push notifications send in batches of 500 instead of one by one. All lists use DiffUtil, and database reads moved off the main thread. The "Can I donate?" note now says honestly that only the question is sent to the AI.

## 1.25 — 2026-11-08

versionCode 26. The Emergency screen finally works as promised. Two fixes: (1) it no longer calls itself "Emergency (Offline)" no matter what — it now checks whether the server actually answers and shows the live state ("You're online. New requests send right away." or "You're offline. Requests are saved on this phone and sent when you're back online."), with the compose form and button following it (Post request online, Queue request offline). The old label was fixed text; the screen never checked the network, and `SupabaseSync.isOnline` only says the build holds a Supabase URL and key, so the check now uses the real server probe (`GET /auth/v1/health`). (2) The offline queue now actually flushes. A request queued while offline used to sit on the phone forever — `OfflineQueue` had no flush and nothing ever sent it. New `EmergencyFlush` sends queued requests when the server answers, both from the Emergency screen and from the background alert worker, so a queued request goes out even if the user never reopens the screen. A sent request is stored locally under its server id and removed from the queue; one the server refuses, one over the 3-open-requests cap, or one with no signed-in owner stays queued for a later run. A queued request is posted as an "Emergency patient" request from the signed-in user, with their phone as the contact. The cached-requests card is now honestly titled "Recent urgent requests" instead of "Queued requests".

## 1.24 — 2026-11-08

versionCode 25. Everyone sees every blood request now. The Browse list used to show a donor only the requests their own blood group could give to; it now shows every open request from other people, with the ones their group can give to first, then nearest city, most urgent, newest. On the detail screen a donor whose group cannot give no longer gets a failing Offer button: they see a note saying their group cannot give to that patient, but they can share the request with someone who can. Urgent and Critical request alerts go to every eligible donor too, for the same reason: a donor who cannot give may know a friend or relative off the app who can. Offering to donate is still limited to matching, eligible donors, and the server already lets every signed-in user read every request, so no database change was needed.

## 1.23 — 2026-11-07

versionCode 24. Themes: Light, Dark and System, picked in Settings. The dark palette is the same Warm Clinical Sanguine brand on deep warm surfaces (not pure black), with brighter status colours so Available / Low / Out of Stock stay readable. The choice is stored in ThemePrefs and applied by a new BloodApp Application class before any screen draws, so the first screen already opens in the right mode; picking a theme applies at once. Default is Light, so nothing changes for anyone until they pick something.

## 1.22 — 2026-11-07

versionCode 23. WhatsApp-style bottom navigation, designed in Stitch: Home, Donors, Stock and Requests are tabs on the four main screens, with live badges (Stock shows the groups at or below the low threshold, Requests shows the pending count). The "Can I donate?" helper is a floating crimson chat button above the bar on every tab and on the donor home, and opens as a chat view with a greeting, suggestion chips and message bubbles. The manager dashboard is slimmed to six quick actions (Camps, Appointments, Forecast, Manager reports, My Profile, Check for Updates); Camps, Appointments, Forecast, Manager reports, Moderation, Emergency, Settings and Log out live in the three-dot menu in the app bar. Add donor and New request are full-width buttons now, so the chat button has the bottom-right corner to itself. Home in the bar is role-aware: managers land on the dashboard, donors on their home.

## 1.21 — 2026-11-07

versionCode 22. Warm Clinical Sanguine redesign of the whole app GUI, designed in Stitch: light-first warm palette (#FFF8F5 canvas, #C8102E primary), 48dp buttons with 16dp radius, 20dp cards, status pills for donors, stock and requests. All 37 layouts restyled, all 279 pre-existing view ids preserved. Dashboard gains an urgent banner with the live pending-request count; stock is a two-column grid with progress bars and Available/Low/Out of Stock pills. One deliberate behaviour change: the app now stays light in system dark mode (night colours removed).

## 1.20 — 2026-11-07

versionCode 21. Phase 3 sync: camps, slots, bookings, check-ins, verifications
and stock movements now sync with Supabase instead of living on one phone only.

- `SupabaseClient` gains get/insert calls for all six Phase 3 tables, with
  column lists matching the migration exactly; `DbHelper` gains matching
  upserts (by server id, local-only rows survive), and `SupabaseSync` gains
  `pullPhase3` (wired into `pullAll`) plus online-first pushes.
- Camp, slot, check-in, booking and verification screens write online first
  and keep the server id locally; when the server cannot be reached they
  save on the phone only and say so. A Full refusal from the server is never
  papered over with a local-only booking.
- Donation and fulfilment flows also write their stock movement online and
  store the local movement under the server id, so the forecast sees every
  device's data without double-counting. A missed movement insert leaves
  the local movement phone-only, never duplicated.
- 6 new unit tests for the booking-failure mapping and the caller-ownership
  guard. The SQLite upsert behaviours still need an on-device run to verify
  (no SQLite on the JVM test classpath).

## 1.19 — 2026-11-07

versionCode 20. Polish fixes for the known issues from the Phase 1-3 review:

- Timeline no longer shows the same status change twice: `StatusTimeline.ordered`
  collapses consecutive repeats of one status, keeping the later (server) row.
  A status the request genuinely returns to later still shows twice.
- Camp check-ins and slot bookings are now bound to the caller: the insert
  policies require the donor row's `auth_id` to be the signed-in user, or the
  caller to be a manager. Any signed-in user could previously check in or book
  as any donor.
- The Fulfilled push no longer says "donors fulfilled" when the manager
  fulfils from stock: the trigger now sends `fulfilled_by`, and `push-notify`
  words the stock case separately. (Still dormant live until B3/B4/B6.)
- The Create Account button stays disabled while a sign-up is in flight, so a
  double tap cannot fire two sign-ups and trip the Auth rate limit (429).
- Phase 3 tables hardened: `donor_id` now references `donors(id)` on
  camp attendance, bookings and verifications; `stock_movements.blood_group`
  has the 8-group CHECK; all six tables now carry `created_at`. The migration
  file carries the matching ALTERs, so a live table is upgraded in place.
- `EmergencyLogicTest` limit fixture now uses real dates.

## 1.18 — 2026-11-07

versionCode 19. Fixes from the Phase 1-3 review (see review 2026-11-07):

- `DbHelper.bookSlot`: the slot-not-found path ended the transaction twice
  (`endTransaction()` in `also` plus the `finally`), which crashed at runtime.
  The early return now leaves the transaction to the `finally` alone.
- `supabase_phase3_migration.sql`: `prevent_overbooking()` now locks the slot
  row (`FOR UPDATE`) before counting, so two donors racing the last seat
  really cannot both book; the trigger also keeps `appointment_slots.booked`
  in step, with a delete trigger to decrement it on cancellation.
- `CampRules.sortByDate`: unparseable dates now sort last, as the comment
  always said.
- CI now replays the Phase 2/3 migrations: both files added to `sql.yml`
  (forward passes + rollback pass), and `supabase_verify_policies.sql`
  expects their 16 policies and 7 tables (44 policies, 16 tables total).
- Removed the committed `.kotlin/errors/` daemon log and git-ignored `.kotlin/`.

## 1.17 — 2026-11-07

versionCode 18. Phase 3: blood camps, appointment slots, donor verification,
stock forecasting and offline emergency mode. Local `DB_VERSION` 9 → 10 (six new
tables, additive); new Supabase migration `supabase_phase3_migration.sql` with
matching rollback. Build and 184 unit tests pass.

### Blood camps

- `CampListActivity` splits camps into Upcoming / Past (`CampRules`). Donors check
  in (`recordAttendance`, de-duped by `UNIQUE(camp_id, donor_id)`); everyone sees
  the attendance count and names. Managers create camps.

### Appointment slots

- `SlotListActivity` books through `DbHelper.bookSlot`, which re-checks capacity
  inside a transaction and returns `Ok` / `Full` / `Duplicate`; the row button
  mirrors that result. The Supabase migration adds a `prevent_overbooking` trigger
  as the server-side guard. Managers create slots.

### Donor verification

- Managers set pending / verified / rejected in `VerifyDonorActivity`;
  `VerificationRules.isVerified` treats the latest record as authoritative.
  `DonorDetailActivity` shows the `✓ Verified donor` badge.

### Stock forecast

- `ForecastActivity` runs `StockForecast.forecast` over the new `stock_movements`
  history (donations write an inflow, fulfilments an outflow) to show per-group
  burn rate, days of stock left, and a SURPLUS / OK / LOW / CRITICAL status.

### Offline emergency mode

- `EmergencyActivity` opens to a cached list of the most urgent recent requests and
  queues a composed request locally (`OfflineQueue`) for flushing when back online.

## 1.14 — 2026-11-02

versionCode 15. Five verified bug fixes in the donor alert and offer flows,
plus a visual refresh of the main screens.

### Donor alerts and push actually work now

- The urgent-request poll, the notification permission prompt and the FCM
  token upload moved from `MainActivity` (manager-only, unreachable for
  donors) into `UserHomeActivity`, the screen donors actually open. The v1.6
  poll feature is live for donors for the first time.
- Donor sign-out now stops the poll and deletes the device's push token row,
  matching the manager logout.
- Added the missing `BloodMessagingService` (declared in the manifest but the
  class did not exist), so FCM pushes are delivered instead of silently
  dropped, and token rotations re-upload.

### Availability toggle fixed

- `available_to_donate` is now written and read as a real boolean, matching
  the database column. The switch no longer flips itself off after a sync,
  and the P2P offer flow's server-side availability check can pass.

### Requester phone number no longer leaks in pushes

- The urgent-request push payload no longer carries the requester's contact
  number (it went to every compatible donor). The parser hardcodes it empty,
  and the notification only shows a Contact line when a number truly exists.
  Contact stays hidden until an offer is accepted, as designed.

### Visual refresh

- Buttons are rounded (14dp) across the whole app via the theme.
- Login, donor home and manager dashboard share a red gradient header band
  with rounded bottom corners.

## 1.13 — 2026-11-01

versionCode 14. Check 2 says "reachable" in plain words, and Edge Functions can
be deployed from GitHub Actions without a local Supabase CLI.

### Connection check reads calmly

- "2. Server" now shows **reachable** when the server answers, with the status
  code in smaller parenthetical text — e.g. `reachable (HTTP 401)`. A 401 is
  proof the server answered, so it no longer sits in the headline looking like
  a failure.
- Only a request that never completes is a failure, and it says why (offline,
  DNS, timeout).
- `SupabaseProbeTest` pins both: any HTTP code is reachable, the headline is
  always "reachable", and the code stays in the small note.

### Deploying functions without a laptop CLI

- A new manual workflow, **Deploy Edge Functions**, deploys `delete-account`
  and `ai-coach` with JWT verification ON, and `push-notify` with
  `--no-verify-jwt` only when you tick the box.
- It reads the project back and refuses to pass unless `verify_jwt` is right,
  then calls every function without a token and requires a refusal (401, or
  401/403 for `push-notify`). A 404 fails the run.
- It authenticates with the repository secret `SUPABASE_ACCESS_TOKEN`.
  Function secrets (`OPENROUTER_API_KEY`, `AI_MODEL`,
  `FCM_SERVICE_ACCOUNT_JSON`, `PUSH_WEBHOOK_SECRET`) stay in the Supabase
  dashboard under Edge Functions → Secrets — never in the repo or the SQL
  editor. RUNBOOK.md section E explains the token, the secret and the run.

## 1.12 — 2026-11-01

versionCode 13. The connection check no longer reports a healthy server as
unreachable.

### Check 2 now uses a public endpoint

- "Server reachable" called `GET /rest/v1/`, which Supabase now restricts to
  the service_role key, so a working project showed HTTP 401 "Invalid API key
  (Only the service_role API key can be used for this endpoint)".
- It now calls the public Auth health endpoint (`/auth/v1/health`) with the
  anon key. Any HTTP answer — 200, 401, even 500 — counts as reachable and the
  status code is shown; only a request that never completes fails.
- The app still uses only the anon key. No service_role key is used or stored.

## 1.11 — 2026-11-01

versionCode 12. The AI helper now costs almost nothing, sends nothing personal,
and a donation that fails to record says why.

### The AI helper sends no personal data at all

- The request used to carry your first name, donation count, days since your
  last donation and blood group. It carries **none of them now** — only the
  question you typed. A motivational line does not need a name, and an
  eligibility answer should never be personalised: a licensed blood bank decides
  after screening, not a model.

### One call a day for everyone, instead of one per user

- Free models allow roughly **50 requests a day for your whole account**, so one
  call per user would leave the 51st user with nothing and the helper would look
  broken at random. Instead the first request of a day generates about **30
  short quotes in a single call** and stores the ones that pass validation; every
  other user that day is served from that bank for free. No scheduled job — the
  bank is built by whoever asks first.
- Every generated quote is checked before it is stored: length, **no medical
  claims** (no cures, prevention, treatment, weight loss or detox), and **no
  personal data** (no names, numbers, ages or blood groups).
- The model is `openrouter/free` by default (set `AI_MODEL` to override), with a
  fallback list of other `:free` models tried in order, so one unavailable model
  does not take the feature down.
- **"Can I donate?" is limited to 5 questions per user per day**, plus a project
  cap of 40 calls a day — under the free limit, with the quote bank's calls
  reserved. When you hit either, the app says so and says when it resets.

### When the helper is not there, the app says so

- Not deployed, or deployed without an API key → **"The AI helper is not set up
  yet. Everything else in the app works without it."** The home card keeps its
  local fact of the day, so a home screen is never blank and never shows an
  error. Every answer, including the failures, ends by pointing at the licensed
  blood bank, which is where the real decision is made.

### Recording a donation now reports the real reason

- "Saved on this phone (not synced)" hid the cause. Now a failure names the step
  that failed and quotes the server, e.g. *"the donation row: HTTP 403: new row
  violates row-level security policy for table \"donations\""* — so a screenshot
  is enough to diagnose it. Recording a donation is manager-only in the database,
  so this is what a non-manager sees.
- If the donation row lands but the stock count does not, the message says that
  and includes the server's reason for the stock call.

## 1.10 — 2026-11-01

versionCode 11. You can now recover a forgotten password, and delete your own
account, from inside the app.

### Forgot password

- **Forgot password?** on the sign-in screen starts a code flow: the app asks
  Supabase to email a 6-digit code, you type the code and a new password, and
  the app exchanges the code for a session — because only a signed-in session
  is allowed to set a password.
- Every failure says which one it was: the address is not registered (Supabase
  answers the same either way, on purpose), too many attempts, **rate limited**,
  the code expired, the password too weak, or no internet. "Send the code again"
  issues a fresh one.
- This needs the **Reset password** email template to show `{{ .Token }}` —
  otherwise the email has a link and no code. The exact dashboard steps are in
  RUNBOOK.md.

### Delete my account

- Settings → **Delete my account**: a screen that says exactly what goes and
  what stays, and asks you to type DELETE before it will do anything.
- Deleted: your profile, contact number, device token, offers and open
  requests, and the account itself. **Kept: your donation history**, detached
  from your name — the blood bank has to keep its own record of donations, and
  that history underpins the stock counts. Requests that were already fulfilled
  are kept without your name or number.
- The account itself is deleted by a new Edge Function (`delete-account`),
  because removing an Auth user needs the service role key and that key must
  never be on a phone. It takes the account to delete **from your token**, never
  from the request body.
- **Partial failure is handled:** if your data is deleted but the login somehow
  survives, you are told so, nothing is wiped from the phone, and pressing the
  button again safely finishes the job. If nothing was deleted, nothing is
  wiped.
- Manager accounts are refused — deleting the only account that can administer
  the project would leave no one who could re-create it.

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

## 1.15 — 2026-11-04

versionCode 16. Shared in-screen loading indicator across every screen that
does real network work, plus small fixes from the code review.

### A consistent "in-flight" indicator

- Added a shared `UISync` helper the app uses instead of one-off spinners: a
  small `ProgressBar` + label that appears while a background pull or write is
  running, and disappears when it ends (even on failure), plus a short-lived
  full-screen dimmed overlay (`pauseUntil`) for brief waits such as cold starts
  and one-shot actions. Normal syncs stay non-blocking — Toasts stay the primary
  feedback.
- The indicator now appears on: the manager dashboard, donor list, request list,
  stock, profile, connection check, donor detail, request detail, create request,
  add donor, add request, and the "Can I donate?" AI helper.
- Write paths that post to Supabase now show what they are doing ("Posting
  request…", "Saving donor…", "Saving request…", "Asking…") instead of
  looking idle, and the Save / Ask button is disabled while the call is in flight.
- `UISync.withSync` runs work on the shared background executor and hides the
  indicator in a `finally`; callers still post UI updates through
  `activity.runOnUiThread { ... }`.
- `UISync.pauseUntil` gained an `acceptFinishing` flag (off by default) so the
  overlay is removed if the owning Activity leaves the screen, rather than
  sticking around over the next screen.

### Review fixes

- `SupabaseClient.getRequest`, `getDonor` and `getDonorByAuthId`: only overwrite
  the phone/contact fields when the RPC actually returns a value. A missing value
  no longer overwrites a cached number with an empty string.
- `supabase_p2p_policies.sql` (in `sql/applied/`): removed `delete` from the
  `grant ... on offers to authenticated` line. Offers have no delete policy by
  design — the comment now says so explicitly, so the grant is not mistaken for
  one.
- Added clarifying comments in `SupabaseSync`: `recordDonationRemote` documents
  that it is the **manager** path and that `completePeerDonationRemote` is the
  peer path (peer donations never touch stock); `pullRequests` is marked
  FRAGILE because it depends on the pulled row having an empty contact (the
  `REQUEST_COLUMNS` list omits it).

### Loading UI philosophy

- Show the cached screen first; run network work on a background thread; report
  the result with a Toast. Add the small `UISync` indicator when a background
  pull or write is running and the user might otherwise think the app had frozen.
- Use `pauseUntil` only for short, unavoidable waits where *nothing* can be shown
  yet (cold start, one-shot waits), never for a normal sync.
- Do not block the whole screen for normal data pulls or writes.

## 1.16 — 2026-11-06

versionCode 17. Email confirmation is off, and signing up with an email that
is already registered now says so in plain words.

- Email verification is skipped for now (Supabase "Confirm email" off): a new
  account signs in straight away, no confirmation link needed.
- Registering with an email that already exists used to show a confusing
  "Supabase Auth returned no session" message. It now says "This email is
  already registered. Please sign in instead." — no Supabase jargon, because
  the person holding the phone does not know what Supabase is.
