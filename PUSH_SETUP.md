# Push notifications (Phase 3) — Firebase + Supabase setup

Replaces nothing and breaks nothing: while any step below is missing, the app
falls back to the 15-minute WorkManager poll. When everything below is done,
alerts (offers, acceptances, urgent requests) go out in seconds. The README
still says delivery is "approximate", because it is: FCM throttles, devices
can be offline, and dedup can only be per device.

## What is already in the repo (nothing to do)

- `app/build.gradle.kts` — reads the four Firebase identifiers as Gradle
  properties into BuildConfig, and adds `firebase-messaging:23.4.1` **without**
  the google-services Gradle plugin, so CI keeps building with no Firebase
  present at all.
- `app/src/main/java/com/tayyab/blooddonation/util/FcmPush.kt` — explicit
  `FirebaseApp` initialisation from BuildConfig values; token upload/upsert;
  cleanup on sign-out.
- `supabase_fcm_migration.sql` + `supabase_fcm_migration_rollback.sql` — the
  `device_tokens` table (additive; run `supabase_fcm_migration_rollback.sql`
  to undo).
- `supabase_fcm_policies.sql` + `supabase_fcm_policies_rollback.sql` — users
  may write/read only their own token rows; the manager may read all.
- `supabase_fcm_triggers.sql` + `supabase_fcm_triggers_rollback.sql` — three
  pg_net triggers (offer created / offer accepted / urgent request created)
  that call the Edge Function.
- `supabase/functions/push-notify/index.ts` — the Edge Function. It verifies
  a shared webhook secret, finds recipients (with the SAME eligibility and
  compatibility rules as the app), and sends data-only FCM v1 messages using
  a service-account JWT it signs itself.

## SQL run order (and rollback, in reverse)

Run in the Supabase SQL editor, **after** the Phase 2 files:

1. `supabase_fcm_migration.sql` — creates `device_tokens` (additive, safe).
2. `supabase_fcm_policies.sql` — locks the table down. Make sure
   `public.is_manager()` exists (it does if `supabase_role_policies.sql`
   was applied in Phase 2); the file has a copy-paste fallback function in a
   comment.
3. `supabase_fcm_triggers.sql` — EDIT THE TWO PLACEHOLDERS FIRST (see below).
   Creates the triggers that ask the Edge Function to send pushes.

Roll everything back in reverse: `supabase_fcm_triggers_rollback.sql`,
`supabase_fcm_policies_rollback.sql`, `supabase_fcm_migration_rollback.sql`.

## The two placeholders (never secrets in the repo)

`supabase_fcm_triggers.sql` contains `__PUSH_FUNCTION_URL__` and
`__PUSH_WEBHOOK_SECRET__`:

1. `__PUSH_FUNCTION_URL__` — set after step "Deploy the Edge Function":
   `https://<project-ref>.functions.supabase.co/push-notify`
2. `__PUSH_WEBHOOK_SECRET__` — a long random string YOU choose once, e.g.
   `openssl rand -hex 32` locally. The same value is then stored as a
   Supabase secret (next section). It is not connected to Google at all; it
   only proves that a push request came from your database triggers.

Because the file needs the URL before it exists, the practical order is:
deploy the function (step 1 below), set secrets, THEN edit and run the
triggers file.

## Exact steps: Firebase

1. console.firebase.google.com → create/state the project used by the APK
   (the docs assume one project; the app id + sender id below come from it).
2. Project settings → General → **Your apps** → add an **Android app**:
   package name `com.tayyab.blooddonation`, SHA optional. On success you get
   two of the four values:
   - **App ID** (starts with `1:…:android:…`) → `FIREBASE_APP_ID`
   - **Project ID**   → `FIREBASE_PROJECT_ID`
3. From the same JSON config view (`.getAppConfig` download link next to it):
   - **api_key** (Current key) → `FIREBASE_API_KEY`
   - **project_number** → `FIREBASE_SENDER_ID`
   - Do NOT download google-services.json into the repo — this setup does not
     use it. These four identifiers are also inside every APK by design, but
     keep them as CI secrets. The REAL secrets are the service account and
     the webhook secret, and those NEVER go in the repo or the app.
4. Cloud Messaging API (legacy not needed): nothing to enable — HTTP v1 works
   once the project exists.

## Exact steps: Supabase

1. **Service account (the only server secret):** console.cloud.google.com →
   IAM & Admin → Service Accounts → create → grant it Firebase Cloud
   Messaging API Sender? (Roles → *Firebase Cloud Messaging* nothing is
   required in new projects; the key only needs to be valid) → Keys → Add key
   → JSON. Save it NOWHERE except the Supabase secret below — not in the
   repo, not in a local file you keep:
   ```
   supabase secrets set FCM_SERVICE_ACCOUNT_JSON="$(cat keyfile.json)"
   rm keyfile.json  # or move to a password manager
   ```
2. **Webhook secret:**
   ```
   supabase secrets set PUSH_WEBHOOK_SECRET=<the same value as the SQL file>
   ```
3. **Deploy the function:**
   ```
   supabase functions deploy push-notify --no-verify-jwt
   ```
   (`--no-verify-jwt` is REQUIRED: callers are database triggers, not user
   tokens. The 403-by-default webhook secret check is inside the code.)
4. **Triggers:** edit the two placeholders in `supabase_fcm_triggers.sql`,
   run it. Done.

## Repository secrets (only the four identifiers)

GitHub: Settings → Secrets and variables → Actions, add
`FIREBASE_PROJECT_ID`, `FIREBASE_APP_ID`, `FIREBASE_API_KEY`,
`FIREBASE_SENDER_ID`. The workflows in `.github/` already pass these
properties to Gradle when present; with none of them set, builds stay green
and the app keeps polling as before.

## What breaks / does not arrive, and for whom

- **Older APKs** already have the poll; they keep it and will never see
  pushes. Nothing server-side in this phase invalidates them: the triggers
  are additive and devices of old clients keep their `device_tokens` row
  empty — actually they have no row at all, since the endpoint is new.
- **Local-only manager session** (`manager` / `manager123`): no Auth user id,
  so no token row, so no pushes. Expected; the dashboard already warns that
  these sessions cannot sync.
- **Donor without "available to donate" ON, ineligible (age/weight/90-day),
  or an unavailable group** — filtered out server-side for urgent requests;
  they still receive offer/acceptance pushes addressed to them personally.
- **A phone after sign-out** gets no pushes (its token row is deleted), even
  though the app was installed. Sign in again to re-register.
- **Wrong email-confirmed users** (no Auth id) — same: no token row.

## Concrete verification (5 minutes)

1. Install a build WITH the Firebase values on two devices and sign in as a
   donor (device A) and as a requester (device B).
2. The requester posts an Urgent request matching the donor's group.
3. Donor's device (A) should get the urgent push within seconds — push is
   data-only, so the notification comes from the app's own code path.
4. The donor offers; the requester (B) gets "New offer on your request".
5. The requester accepts; the donor (A) gets "Your offer was accepted".
6. With push configured the 15-minute poll keeps running — verify dedup by
   watching that a request already announced by push is never announced
   again by the poll (both paths share the same remembered-id file).

## Known limits (stated plainly)

- FCM requires Google Play services on the device; devices without them are
  silently poll-only (FcmPush stays uninitialised).
- The Edge Function fans out only to the first ~300 donors per urgent request
  (fresh tokens first) — beyond that, the poll is the safety net.
- pg_net delivery is async and fire-and-forget; failures are only in the
  Supabase function logs. The app ALSO still works when Supabase is down —
  the poll never depends on the push path.
