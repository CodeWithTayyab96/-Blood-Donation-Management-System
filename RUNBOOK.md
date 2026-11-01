# RUNBOOK — setting up this project on Supabase, end to end

Three stages, strictly in order. Each stage ends with a **STOP AND TEST ON
THE PHONE** checklist — do not start the next stage until every box on the
previous one is checked. The app keeps working between stages: whatever a
stage's server side is missing is handled silently (see
[Stage resilience](#stage-resilience-what-the-app-does-when-a-stage-is-missing)).

- **Stage A — finish the security model.** Peer-to-peer SQL and the manager
  claim are already applied on this project; what is missing is
  `supabase_role_policies.sql`, which is what actually enforces the
  manager/donor split and stops anyone reading other people's phone numbers.
  After A the app is fully usable by humans: requests, offers, moderation.
- **Stage B — FCM / Firebase push.** After B alerts arrive in seconds when
  the app is closed.
- **Stage C — the AI helper.** Optional nicety; leave it last.

Nothing here touches code: all changes are in the Supabase dashboard, in your
terminal (`supabase` CLI), in the Firebase console, and in GitHub. Every SQL
file below is replayed against a real Postgres in CI on every push (job **SQL
checks**, `.github/workflows/sql.yml`), and the security rules are asserted
there too — a red SQL CI run is a release blocker for the SQL files, never
something to debug on the live project.

---

## BEFORE YOU START — export every table to CSV

The one recovery tool you should never need but must always have. From the
Dashboard:

1. **Table Editor** → for EVERY table that exists right now, click the table
   name, open the ⋯ row-action menu at the top-left of the grid →
   **Export Data → Download as CSV**. Name the file `<date>_<table>.csv`.
   Tables to expect (everything may not exist yet — export whatever does):
   `donors`, `donations`, `requests`, `stock`; later stages add
   `offers`, `contact_details`, `reports`, `device_tokens`, `ai_coach_usage`.
   Note: the CSVs contain demo phones and usernames — store them where you
   store passwords, not in this repo, not in chat.
2. **auth.users is not a project table.** Export it from the SQL editor:
   ```sql
   select id, email, raw_user_meta_data, raw_app_meta_data,
          created_at, last_sign_in_at
     from auth.users;
   ```
   → ⬇ Download as CSV.
3. **Verify** the backup column counts up:
   ```sql
   select 'donors' as t, count(*) from donors
   union all select 'donations',      count(*) from donations
   union all select 'requests',       count(*) from requests
   union all select 'stock',          count(*) from stock;
   ```
   The numbers in the result are the minimum row count every CSV must
   re-import later.
4. Optional second copy: Dashboard → Database → **Backups** → create one.

If a CSV export fails (too large for the editor, etc.) use the CLI against a
`supabase db dump --data` — but between the CSVs above and rollback files
listed at the end of each stage the runbook never depends on it.

---

# Stage A — finish the security model

## A0. Where your project is right now

| File | State on your project | How it shows |
|---|---|---|
| `supabase_migration.sql` | applied | the four base tables exist |
| `supabase_auth_migration.sql` | applied | `donors.auth_id` exists |
| `fix_stock_rpc.sql` | applied (assumed) | the fulfil path needs `adjust_stock()` |
| `supabase_auth_policies.sql` | applied | `"Authenticated full access"` exists on the base tables |
| `supabase_p2p_migration.sql` | applied | `offers`, `contact_details`, `reports` exist |
| `supabase_p2p_policies.sql` | applied | the `contact_details` policies exist |
| `supabase_role_policies.sql` | **NOT applied** | `"Authenticated full access"` is still there |
| Stage B (push) | not applied | no `device_tokens` table |
| Stage C (AI) | not applied | no `ai_coach_usage` table |

Two consequences while the role file is missing, and both are invisible in the
app: every signed-in account can write every row, and every signed-in account
can read **every donor's phone number and every request's contact number**.
The screens look correct because the app is doing the right thing — the
database simply is not enforcing it.

## A1. Prerequisite — the manager claim

The role file drops the permissive policies. If no account carries the claim
when it runs, every account becomes read-only. Check first:

```sql
select email, raw_app_meta_data ->> 'role' as role
  from auth.users where email = 'tayyabuq1@gmail.com';
```

**Pass:** one row, `role = MANAGER`. **If NULL:** set it and re-check, do not
continue until this passes:

```sql
update auth.users
   set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb)
                           || jsonb_build_object('role', 'MANAGER')
 where email = 'tayyabuq1@gmail.com';
```

## A2. Install the new APK BEFORE running the SQL

Install an APK built **from this commit or later** on the phone you test
with. v1.6 — the pre-release already on GitHub — predates the column change:
it asks PostgREST for `select=*` on `donors` and `requests`, which the new
column privileges reject with 42501, and the app then quietly falls back to its
local copy. It looks like "sync stopped" rather than an error. Any build from
the commit that added `donor_phone()` onwards asks for explicit columns and is
unaffected.

## A3. Run `supabase_role_policies.sql` — the only file you are missing

Paste the whole file into the SQL editor and run it. It is re-runnable, and it
is order-independent with `supabase_p2p_policies.sql` (either order gives the
same final policy list). What it changes:

- creates `is_manager()` (reads `app_metadata.role` only);
- replaces the four `"Authenticated full access"` policies with the
  manager/donor set on `donors`, `donations`, `requests`, `stock`;
- adds `"donors insert own"`, so a new donor's registration can create its own
  profile row — without it registration's profile insert is rejected;
- removes `donors.phone` and `requests.contact` from the `authenticated`
  SELECT grant and adds `donor_phone()` / `request_contact()`, which answer
  only for the owner and the manager;
- leaves `"requests write manager"` out when the P2P policies are already
  installed (they own that table now), so the final list does not depend on
  the order the two files ran in.

## A4. Verify — `supabase_verify_policies.sql`

Paste the whole file into the SQL editor. It is read-only and safe to run any
number of times. **Expected output: exactly one row, `ALL POLICIES OK`.**

Tables that do not exist yet are skipped, so the missing Stage B/C tables are
not reported. If you see `PROBLEM` rows instead, they mean:

| PROBLEM row | What happened | Fix |
|---|---|---|
| `policy <name> on <table>: MISSING` | the file that creates it never ran (or died part-way) | run that file, re-verify |
| `policy Authenticated full access on <table>: UNEXPECTED` | `supabase_role_policies.sql` did not run, or failed before its DROP block | re-run it; the editor shows the failing line |
| `policy requests write manager on requests: UNEXPECTED` | `supabase_p2p_policies.sql` has not been applied since the role file | re-run `supabase_p2p_policies.sql` (it is re-runnable) |
| `authenticated can still SELECT donors.phone` / `requests.contact` | the column-privilege block at the end of the role file did not run | re-run the role file, re-verify |
| `row level security is OFF on <table>` | the migration's `enable row level security` was rolled back | run `alter table <table> enable row level security;` |
| `missing function public.donor_phone(bigint)` | same cause as the phone row | re-run the role file |

## A5. Sign out and back in on the phone

The access token issued before A3 carries no role, so the app treats that
session as a donor until it refreshes. Log out and back in, then re-run the A4
query if you want the database side confirmed again.

## Stage A — STOP AND TEST ON THE PHONE

1. **Manager** — sign in as `tayyabuq1@gmail.com`: the dashboard says Manager,
   the Moderation button is visible, and adding a donor succeeds. If the
   "Offline-only session" warning is showing, the sign-in did not carry a
   token — sign out and in again.
2. **Requester create / cap** — a non-manager account posts three requests; a
   fourth is refused with the "at most 3 open requests" message.
3. **Offers** — a donor account sees a request it can give to, taps *I can
   donate*; the requester sees first name / group / city and Accepts.
4. **Phone numbers stay hidden** — before accepting, the request shows
   `Contact: shared after you accept an offer` and the donor list shows no
   numbers at all. After accepting, both sides see exactly one number each.
   The manager sees numbers everywhere; a normal donor sees only their own.
5. **Registration** — register a brand-new donor. It must NOT say "the donor
   profile did not sync"; the row must appear in `donors` with an `auth_id`.
   (This is what `"donors insert own"` is for.)
6. **Contact self-service** — that new donor saves their own phone on *My
   Profile* once.
7. **Moderation** — the manager can cancel a reported request; a normal donor
   cannot open that screen.
8. **Availability** — a donor switches availability off; offering is refused;
   switch it back on.
9. **No data hole:** if anything except push/AI is broken, stop. Do not start
   Stage B.

**Rollback (Stage A only):** `supabase_p2p_migration_rollback.sql` drops the
P2P tables and their policies; the column privileges and the two helper
functions are undone with the four lines at the end of
`supabase_role_policies.sql`. Re-run `supabase_role_policies.sql` after a P2P
rollback if you keep the role setup, and re-run `supabase_p2p_migration.sql` +
`supabase_p2p_policies.sql` when you go forward again.

---

# Stage B — FCM / Firebase push

## B1. Firebase project + the four identifiers

1. console.firebase.google.com → use/create the project for the APK.
2. Project settings → General → **Your apps** → add an **Android app**:
   package name `com.tayyab.blooddonation`, SHA optional.
   - **App ID** (`1:…:android:…`) → `FIREBASE_APP_ID`
   - **Project ID**   → `FIREBASE_PROJECT_ID`
3. Same JSON config view (⬇ `.getAppConfig` link):
   - **api_key (Current key)** → `FIREBASE_API_KEY`
   - **project_number** → `FIREBASE_SENDER_ID`
4. Do NOT download google-services.json into the repo — this setup never
   uses it. These four identifiers also live inside every APK by design
   (same as the Supabase URL/anon key); the REAL secrets are B3's.

**Verify:** all four values written down once, from the same Google project.

## B2. GitHub secrets + a build with Firebase baked in

Repo → Settings → Secrets and variables → Actions → add
`FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_SENDER_ID`
(just names + values; the workflows already pass them to Gradle).

Then run the **Build Android APK** workflow manually (Actions tab →
Build Android APK → Run workflow) and install THAT APK on the phone
(replaces the Stage A one — all local data carries over; the SQLite upgrade
runs in place).

**Verify:** on the phone nothing visibly changed yet — same account, and
notifications still arrive only while the app is open. That is correct: the
poll fallback is the only delivery mode until B6 runs; the mechanics live in
`PUSH_SETUP.md`.

## B3. The two REAL secrets (only on the server)

```bash
# Generate the webhook secret ONCE and keep it in your password manager —
# B6 needs the exact same string later:
openssl rand -hex 32

supabase secrets set PUSH_WEBHOOK_SECRET=<the value you just generated>
supabase secrets set FCM_SERVICE_ACCOUNT_JSON="$(cat keyfile.json)"
rm keyfile.json          # or move it into your password manager; never commit it
```

Get the service-account JSON: Firebase console → Project settings →
**Service accounts** → **Generate new private key** → downloaded file.
Take the whole JSON; never commit it, never log it.

**Verify:** `supabase secrets list` shows both names (values never print).

## B4. Deploy push-notify — WITHOUT JWT verification

```bash
supabase functions deploy push-notify --no-verify-jwt
```

`--no-verify-jwt` is deliberately REQUIRED here: callers are database
triggers, not user tokens. The webhook secret inside the code is the actual
gate.

**Verify:** the deploy output prints the function URL —
`https://<project-ref>.supabase.co/functions/v1/push-notify`. Note it; B6
needs it.

## B5. `device_tokens` (SQL, in this order)

Run `supabase_fcm_migration.sql`, then `supabase_fcm_policies.sql`.

**Verify**
```sql
select tablename, rowsecurity from pg_tables where tablename = 'device_tokens';
                                                           -- t
select count(*) from pg_policies where tablename = 'device_tokens';
                                                           -- 2
```

## B6. `supabase_fcm_triggers.sql` — two placeholders first

Edit the file in a text editor BEFORE running it:
1. `__PUSH_FUNCTION_URL__`  →  the URL printed by B4.
2. `__PUSH_WEBHOOK_SECRET__` →  the same value you set as the
   `PUSH_WEBHOOK_SECRET` secret in B3 (same string, no quotes).

Run the edited file. **If it fails:** enable pg_net first (Dashboard →
Database → Extensions → pg_net) and re-run.

**Verify**
```sql
select public.push_url();
-- your B4 URL, no __ left
select public.push_secret() <> '__PUSH_WEBHOOK_SECRET__';
-- t
select count(*) from pg_trigger where tgname like 'push_after_%';
-- 3 (push_after_offer_insert, push_after_offer_accepted, push_after_urgent_request)
```

## Stage B — STOP AND TEST ON THE PHONE

Two phones both signed in (donor A and requester B). Then:

1. **Offer push** — B posts an Urgent-compatible request; A taps
   *I can donate*; B gets "New offer on your request" within seconds while
   the app is CLOSED; tapping it opens that request.
2. **Accept push** — B accepts; A gets "Your offer was accepted" closed-app.
3. **Urgent fan-out** — sign into a third donor C (with availability ON and
   history >90 days, if not smooth); B posts an urgent request; C's phone
   sees the alert. A pending wait: if C gets nothing, re-check C's
   availability and the 90-day gap; then Supabase → Logs → Edge → `push-notify`
   before touching app config.
4. **No double announce** — the same event must NEVER appear twice
   (poll + push share one remembered-id list per device).
5. **Sign-out stops it** — sign out the phone; the token row disappears
   (`select * from device_tokens` shrinks by one), and that phone gets no
   more pushes until sign-in.
6. **No crashes** — kill the app, put the phone in airplane mode for 10
   minutes, turn wifi back on: the poll still announces; nothing hangs.

**Rollback:** B6 → B5 → drop the function
(`supabase functions delete push-notify`) → remove the two GitHub Firebase
secrets → the app quietly returns to poll-only.

---

# Stage C — AI helper (OpenRouter)

## C1. `supabase_ai_migration.sql`

The `ai_coach_usage` rate-limit table. RLS ON with NO policies on purpose —
a user must not read or reset their own counter; only the Edge Function's
service role can.

**Verify**
```sql
select tablename, rowsecurity from pg_tables where tablename = 'ai_coach_usage';
                                                            -- t
select count(*) from pg_policies where tablename = 'ai_coach_usage';
                                                            -- 0  ← expected!
```

## C2. Supabase secrets

```bash
supabase secrets set OPENROUTER_API_KEY=sk-or-v1-...
supabase secrets set AI_MODEL=google/gemini-2.0-flash-001   # optional; that default is fine
```

**Verify:** `supabase secrets list` shows both (values never print).

## C3. Deploy ai-coach — JWT verification stays ON

```bash
supabase functions deploy ai-coach
```

Do NOT pass `--no-verify-jwt` (the opposite of B4: this one only ever serves
signed-in users, and re-checks their token with `auth.getUser` inside).

**Verify (a signed-in token is REQUIRED to proceed):**
```bash
curl -i -X POST "https://<project-ref>.supabase.co/functions/v1/ai-coach" \
  -H "content-type: application/json" \
  -d '{"kind":"motivation","facts":{"name":"x","donations":1,"blood_group":"B+"}}'
# 401 {"error":"a signed-in user is required"}  ← correct
```
403 instead → you accidentally deployed with `--no-verify-jwt`; redeploy
without the flag.

## Stage C — STOP AND TEST ON THE PHONE

1. **Daily card** — sign in as a donor with a profile: after a moment the
   "Did you know?" card becomes a short, personalised sentence. Reopen the
   app: the same text stays for the rest of the day.
2. **Helper** — 🩸 Can I donate? — ask something general ("Can I donate with
   a high BMI?"); a short plain-text answer appears WITH the disclaimer
   visible above it, and the safety note at the bottom never moves.
3. **Refuse medical advice** — ask "I take tablets for X, can I donate?"
   The answer must point to a licensed blood bank, not to you.
4. **Local-only stays local** — the demo manager (`manager` / `manager123`)
   can open the helper and always gets the fallback text: no Auth session,
   no call, no error.
5. **No candidate data** — nothing typed into the helper appears anywhere
   (`select * from ai_coach_usage` only ever shows the counter columns, and
   the answer text is nowhere).
6. **Fallback stays graceful** — internet off → helper screen shows its
   "could not reach the helper" text on Ask; the dashboard falls back to the
   Phase-1 fact bank. Nothing crashes.

**Rollback:** `supabase_ai_migration_rollback.sql` +
`supabase functions delete ai-coach`; the dashboard card reverts to the
local fact bank automatically.

---

## If you get locked out

Two lock-outs are possible, and neither one loses data.

### Locked out #1 — "everything is read-only" (missing manager claim)

Symptom: after A3 the manager can still browse but cannot add donors,
record donations, fulfil or cancel anything.

1. Causes: the claim was never set (A6 skipped), the role string is a
   wrong-case variant ('manager' instead of 'MANAGER'), or the phone's token
   predates the claim.
2. Fix the Account data:
   ```sql
   select raw_app_meta_data ->> 'role' as role, updated_at
     from auth.users where email = 'tayyabuq1@gmail.com';
   ```
   If NULL or wrong, re-run the A1 update until this returns `MANAGER`.
3. Then **sign out and sign in again on the phone** — the new session picks
   the claim up. Do NOT touch runbook files here; the data behind this lock-out
   is a session token, not the database.

### Locked out #2 — "even the manager gets 401s" (a bad policy run)

If a policy file went wrong — you must restore access before anything else.

The nuclear option that cannot lose data: drop every policy, re-create the
permissive baseline. Paste into the SQL editor:

```sql
-- 1. every policy on every public table is gone (policies are config, not data):
do $rollback$
declare r record;
begin
  for r in select policyname, tablename from pg_policies
            where schemaname = 'public' loop
    execute format('drop policy if exists %I on public.%I',
                   r.policyname, r.tablename);
  end loop;
end;
$rollback$;

-- 2. the v1.4 blanket policy again (from supabase_auth_policies.sql):
create policy "Authenticated full access" on donors
   for all to authenticated using (true) with check (true);
create policy "Authenticated full access" on donations
   for all to authenticated using (true) with check (true);
create policy "Authenticated full access" on requests
   for all to authenticated using (true) with check (true);
create policy "Authenticated full access" on stock
   for all to authenticated using (true) with check (true);

-- 3. device_tokens keeps RLS ON but now has no policies — nobody (except
--    the service role used by the Edge Function) can touch it until B5's
--    policy file is re-run. The app falls back to the poll meanwhile.
```

Every row is intact (verify with the CSV row counts from Before you start) —
you gave up only the role-split enforcement, temporarily. Then walk the
Runbook from A1 again: A1 (the claim), A3 (`supabase_role_policies.sql`),
then A4 (`supabase_verify_policies.sql`) must say `ALL POLICIES OK` — every
file in this runbook is safe to re-run from the top.

### Locked out #3 — "I want the old app back"

`supabase_p2p_migration_rollback.sql` (then `supabase_role_policies.sql`, if
you had completed A3) puts the schema back to pre-P2P and the old APK keeps
working; later stages can be rolled back in reverse — each rollback file is
named next to the step that created its objects.

---

## Stage resilience - what the app does when a stage is missing

The app never requires all three stages to be applied; each missing one is a
fallback, not an error. This is established in code and unit tests:

- **Push parts (Stage B not applied):** `FcmPush` gates everything behind
  `BuildConfig.FIREBASE_*` (`app/src/main/java/com/tayyab/blooddonation/util/FcmPush.kt`,
  `isConfigured`, `initIfNeeded`, `refreshToken`). A CI/SDK build without
  those secrets keeps the WorkManager ~15-min poll — unit-tested by
  `RequestAlertsTest`; the push vs poll dedup is unit-tested in
  `PushDataTest`.
- **`device_tokens` missing (halfway Stage B):** the token upload is POST →
  404 → `SupabaseClient.upsertDeviceToken` simply returns false (its rule:
  *no function here ever throws* — `SupabaseClient.kt`); the next poll cycle
  retries silently. The UI is never involved.
- **AI parts (Stage C not applied):** `AiCoach.request`
  (`app/src/main/java/com/tayyab/blooddonation/util/AiCoach.kt`) returns
  `null` when (a) no signed-in Auth session, (b) no SUPABASE_URL, or (c) any
  HTTP/connection/parse failure — the URL `/functions/v1/ai-coach` with no
  deployed function is ordinary 404. Callers already handle `null`:
  the dashboard falls back to the Phase-1 fact bank
  (`MainActivity.refreshAiCardIfNeeded` marks the day as failed — the
  once-a-day cadence is unit-tested in `AiRulesTest`), and the helper screen
  shows its licensed-blood-bank fallback (`AskDonateActivity.show`); wire
  payload privacy is pinned by `AiCoachPayloadTest`.
- **Push triggers on the DB side (B6 half-done):** they merely fail to fire;
  nothing client-side queries them, and pg_net errors are logged server-side
  only (push is fire-and-forget by design).
