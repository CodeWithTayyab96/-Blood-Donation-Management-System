# RUNBOOK — setting up this project on Supabase, end to end

One page, one order. Do the steps **in the exact sequence written** — several
of them depend on the previous one being finished (the policies need the
schema, the triggers need the deployed function's URL, the manager role needs
signing back in). After EVERY step there is a verification check and, if it
fails, what to do about it.

Nothing here touches code: all changes are in the Supabase dashboard, in your
terminal (`supabase` CLI), in the Firebase console, and in GitHub.
SQL syntax errors are impossible to catch by eye — every file in this
runbook is replayed against a real Postgres in CI (job **SQL checks**,
`.github/workflows/sql.yml`), and the security rules are asserted there too.

**Before you start:**

- Use a throwaway project first if you have never done this. Every SQL file
  below is additive except `supabase_p2p_policies.sql` (it starts rejecting
  older APKs) and the rollbacks (they destroy P2P data).
- Dashboard → Database → **Backups** → create one. The verification after
  step 1 depends on being able to restore.
- Keep secrets OUT of this repo, out of screenshots, out of chat.
- If any SQL below fails and you already re-ran it once: stop, check whether
  the failure is "already exists" (the file is already applied — skip) or
  something else. `supabase_migration.sql`, the P2P migration, the FCM
  migration, and the AI migration are safe to re-run; each policy file is
  re-runnable too now.

---

## Part A — the database (SQL editor, in this order)

### Step 1. `supabase_migration.sql`

Tables `donors`, `donations`, `requests`, `stock` + the 8 stock groups and 5
demo donors.

**Verify**
```sql
select count(*) from stock;    -- expect 8
select count(*) from donors;   -- expect 5
```
**If it fails:** "already exists" → skip to step 2. Anything else → check the
SQL editor's full error; every statement here is plain DDL, so the error
names the exact line.

### Step 2. `supabase_auth_migration.sql`

Adds `donors.auth_id` + its unique index.

**Verify**
```sql
select column_name from information_schema.columns
 where table_name = 'donors' and column_name = 'auth_id';   -- expect 1 row
```
**If it fails:** step 1 was skipped — go back.

### Step 3. `fix_stock_rpc.sql`

The atomic `adjust_stock()` function.

**Verify**
```sql
select public.adjust_stock('O+', 0);   -- expect a number, no error
```
**If it fails:** "function already exists"? It is `create or replace` — a
failure means a different object with that name; check with
`\df+ adjust_stock` (SQL editor: `select * from pg_proc where proname='adjust_stock';`).

### Step 4. `supabase_auth_policies.sql`

RLS on the four tables + wallets open to any signed-in session (this is the
interim policy set; step 6 tightens it).

**Verify** (in the SQL editor you run as postgres, so check policies directly)
```sql
select tablename, rowsecurity from pg_tables
 where schemaname='public';            -- donors/donations/requests/stock: t
```
**If it fails:** "policy already exists" → it is already applied, continue.

### Step 5. `supabase_role_policies.sql` — step 1 of the FILE first

The file has TWO steps. Run ONLY the `update auth.users ...` block first
(it marks `manager@blood.local` as MANAGER in its `app_metadata`).

**Verify**
```sql
select email, raw_app_meta_data -> 'role' as role
  from auth.users where email = 'manager@blood.local';   -- role: "MANAGER"
```
**If NO row:** no user `manager@blood.local` exists yet — the account is
created on first sign-up below (Part B step 2). Register it through the app
first, come back, THEN run this update, then the policies in the rest of the
file. Do not apply the policies while the query above says NULL: without the
claim, EVERY account becomes read-only and the app cannot add donors,
record donations or fulfil anything.

### Step 6. `supabase_role_policies.sql` — the rest of the file (STEP 2)

Per-role policies replace the blanket ones from step 4.

**Verify**
```sql
select count(*) from pg_policies
 where policyname = 'donors update own';    -- expect 1
```
**If it fails:** the step-5 check above must show the claim first — that is
by design, not a bug.

### Step 7. `supabase_p2p_migration.sql`

Requests/donors gain P2P columns; `offers`, `contact_details`, `reports`
tables; the 3-open-request cap; `complete_peer_donation()`. Additive — older
APKs keep working AFTER this file (they break only after step 8).

**Verify**
```sql
select count(*) from pg_tables
 where tablename in ('offers','contact_details','reports');   -- expect 3
```
**If it fails:** duplicate donor/date data can skip the unique index —
the file tells you (a NOTICE) instead of failing; clean duplicates and
re-run the file.

### Step 8. `supabase_p2p_policies.sql` — deploy the NEW APK first

**This file is the enforcing step.** If you apply it while only the OLD APK is
installed, inputs like fulfilment and donor inserts start failing on the
phones. Google Play/internal-test-install the new APK, or accept the break,
then run it.

**Verify** — in the SQL editor, try to read as anon (the first check matters
because it leaks nothing if it fails):
```sql
select count(*) from pg_policies where policyname = 'contact_details read own manager or accepted party';  -- 1
```
**If it fails after the APK is deployed, but the DATABASE is wrong:** every
p2p policy belongs to a table created in step 7 — restore the backup you made
before step 1, or roll back per the end of this runbook.

### Step 9. `supabase_fcm_migration.sql`

`device_tokens` table (RLS ON, no policies yet — deliberately denies
everything until step 10).

**Verify**
```sql
select tablename, rowsecurity from pg_tables
 where tablename = 'device_tokens';        -- device_tokens | t
```

### Step 10. `supabase_fcm_policies.sql`

Users own their token rows; the manager may read all.

**Verify**
```sql
select count(*) from pg_policies
 where tablename = 'device_tokens';        -- expect 2
```

### Step 11. `supabase_fcm_triggers.sql` — EDIT THE TWO PLACEHOLDERS FIRST

The file will not run yet: `__PUSH_FUNCTION_URL__` depends on the function
deployed in Part C, so the real order is **C-2 and C-3 first, THEN this step**
(do C-1's token setup whenever). When Part C steps 2–3 are done:

1. open the file, replace `__PUSH_FUNCTION_URL__` with
   `https://<project-ref>.supabase.co/functions/v1/push-notify`
   (check the exact Edge Function URL printed by `supabase functions deploy`
   in Part C), and `__PUSH_WEBHOOK_SECRET__` with the value you set as
   `PUSH_WEBHOOK_SECRET` in Part C step 1 — they must be THE SAME string.
2. run the edited file in the SQL editor.

**Verify** (paste into the SQL editor)
```sql
select public.push_url();     -- your finished URL, no  __  left in it
select public.push_secret();  -- same string as the Supabase secret (compare by count: length matching)
```
**If it fails:** `create extension pg_net` blocked → the extension ships on
Supabase; enable it: Dashboard → Database → Extensions → pg_net, then re-run.

### Step 12. `supabase_ai_migration.sql`

The `ai_coach_usage` rate-limit table. RLS ON **with no policies** — a user
must not touch their counter; only the Edge Function's service role can.

**Verify**
```sql
select tablename, rowsecurity from pg_tables
 where tablename = 'ai_coach_usage';       -- ai_coach_usage | t
```

### Rollback order (only if you must go back)

`supabase_ai_migration_rollback.sql` → `supabase_fcm_triggers_rollback.sql` →
`supabase_fcm_policies_rollback.sql` → `supabase_fcm_migration_rollback.sql`
→ `supabase_p2p_migration_rollback.sql` (that last one also drops the P2P
request policies — re-run `supabase_role_policies.sql` afterwards so manager
write access survives). Older APKs keep working after this; the P2P-facing
new APK falls back to local SQLite for offers.

---

## Part B — Supabase project + the app's keys

### Step B-1. Dashboard → Authentication → Providers

Email provider ON, "Confirm email" OFF (so sign-up returns a session right
away; with it ON the app says "Email confirmation is required" instead of
logging you in).

**Verify project URL / anon key ready:** Dashboard → Settings → API → note
`Project URL` and the `anon public` key (NEVER the `service_role` key).

### Step B-2. GitHub secrets for the APK builds

Repo → Settings → Secrets and variables → Actions → add:

- `SUPABASE_URL` — the Project URL
- `SUPABASE_ANON_KEY` — the anon public key
- (parts C-4/Phase 3) the four `FIREBASE_*` values from Part D

**Verify:** re-run the **Build Android APK** workflow manually; the run log
must say which of the properties were filled (never print the values).

### Step B-3. Sign up inside the app, once

Install the APK, register `manager@blood.local` (any password YOU choose).

**If step 5 of Part A produced no row yet:** now it can — rerun that single
`update auth.users` block from Part A step 5.

**Verify:** the dashboard no longer says sessions are local-only; the admin
moderation button appears (manager APK flags by username).

---

## Part C — Edge Functions (terminal, requires the supabase CLI + project link)

### Step C-1. Secrets BEFORE deploys (functions read them at runtime on start)

```bash
supabase secrets set OPENROUTER_API_KEY=sk-or-v1-...          # from openrouter.ai → Keys
supabase secrets set AI_MODEL=google/gemini-2.0-flash-001     # optional
supabase secrets set PUSH_WEBHOOK_SECRET=$(openssl rand -hex 32)   # REMEMBER the printed value: Part A step 11 needs THE SAME string
supabase secrets set FCM_SERVICE_ACCOUNT_JSON="$(cat keyfile.json)"

rm keyfile.json   # or move to your password manager; never commit it
```

**Verify** `supabase secrets list` → all four names appear (values never
print — by design).

**If it fails:** wrong project link — `supabase link --project-ref <ref>`
first.

### Step C-2. Deploy push-notify — WITHOUT JWT verification

```bash
supabase functions deploy push-notify --no-verify-jwt
```

`--no-verify-jwt` is REQUIRED (anonymous role? No: callers are database
triggers, not user tokens). The webhook secret inside the code is the real
gate.

**Verify:** `supabase functions list` shows `push-notify` active. The URL is
shown in the deploy output — it goes into Part A step 11.

### Step C-3. NOW run Part A step 11 (trigger file with placeholders filled)

### Step C-4. Deploy ai-coach — JWT verification ON (default: do NOT pass --no-verify-jwt)

```bash
supabase functions deploy ai-coach
```

**Verify:**
```bash
curl -s -X POST "https://<ref>.supabase.co/functions/v1/ai-coach" \
  -H "content-type: application/json" -d '{"kind":"motivation","facts":{}}'
# expect 401 {"error":"a signed-in user is required"}  — NOT 200, NOT 403, NOT deploy error
```
401 = correct: the function takes every caller's token seriously. A 403 here
means you deployed WITH `--no-verify-jwt` by mistake; redeploy without the flag.

### Step C-5. End-to-end check (normal push + AI)

Two devices signed in (one donor, one requester). Requester posts a request →
accept flow; donor's device gets the offer push; donor accepts → requester's
device gets the acceptance push. Any missing push: check Supabase → Logs →
Edge (`push-notify`) BEFORE touching app config.

The AI card: open the donor's dashboard → after a few seconds the fact card
is a personal sentence; helper screen answers simple health questions. If it
says only the fallback text: `supabase functions logs ai-coach` will show
`OPENROUTER_API_KEY` missing.

---

## Part D — Firebase (only for Phase 3 push)

1. console.firebase.google.com → use/create the APK's project.
2. Project settings → **Add app** → Android → package
   `com.tayyab.blooddonation`; SHA-1 optional (push does not need it). The
   latest APK build must already be reachable on the Play/internal testing
   track for FCM let you deliver.
3. Project settings → Service accounts → **Generate new private key** → the
   JSON is the ONLY copy; goes into `supabase secrets set
   FCM_SERVICE_ACCOUNT_JSON=...`; then delete the file.
4. Copy the App ID (`1:...:android:...`), Project ID, api_key,
   project_number to GitHub — not here.

**Verify push from FCM directly (optional):** the repo's
`PUSH_SETUP.md §Concrete verification` walks the four-push test (offer,
acceptance, urgent request, no double-notify).

---

## What is already verified FOR you (CI, on every push to main)

- Every SQL file in Part A (and Part C's function code syntax) is a
  *tested* path: job **SQL checks** runs the forward pass, the security rules
  (contact details until acceptance, request/offer safety, availability
  ownership, the 3-request cap, manager rights, anon read-nothing, the quota
  table), then a full rollback, then a re-apply — against real Postgres 16.
  Failures there are release blockers for the SQL, never issues to debug on
  the live project.
- `deno check` runs on both Edge Functions on every push.

## If everything went green but the app still misbehaves

- Old sessions: sign out and back in to carry the new role claim.
- The anon key in the APK is public by design; nothing sensitive is
  reachable with it (CI proves it). If data is still "mysteriously missing",
  the answer is nearly always "the policies say no" — recheck Part A step 8's
  verification, not the app.
- The push or AI not working after good logs: the WorkManager fallback keeps
  the app functional; file the failure, ship the rest.
