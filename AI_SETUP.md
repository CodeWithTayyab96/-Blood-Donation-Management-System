# AI helper (Phase 4) — OpenRouter + Supabase setup

The "Can I donate?" helper and the dashboard's once-a-day personalised
motivation line are backed by the `ai-coach` Edge Function, which calls
OpenRouter's OpenAI-compatible API. **Nothing below is required for the app to
work**: until the function is deployed and the key is set, the dashboard keeps
showing its local fact of the day (Phase 1) and the helper screen shows its
"ask a licensed blood bank" fallback text.

## What is already in the repo (nothing to do)

- `supabase/functions/ai-coach/index.ts` — the Edge Function. Signed-in users
  only (verify_jwt stays ON, the default), two request kinds (`motivation`,
  `question`), system prompts that forbid medical claims and medical advice,
  reply clamped to 600 chars, per-user daily limit of 30 calls.
- `supabase_ai_migration.sql` + `supabase_ai_migration_rollback.sql` — the
  `ai_coach_usage` rate-limit table. **RLS is ON with no policies**, so
  nobody — not even a signed-in user — can read or write their own call
  counter with their own token; only the Edge Function (service role) can.
  That is deliberate: the caller must not be able to reset its own limit.
- `app/src/main/java/com/tayyab/blooddonation/util/AiCoach.kt` — the client
  call (plain HttpURLConnection, keeps the app dependency-free).
- `app/src/main/java/com/tayyab/blooddonation/util/AiRules.kt` — pure rules:
  question ≤ 500 chars, motivation ≤ 280, reply ≤ 600, and the
  at-most-once-per-day cadence (a failed day is not retried until tomorrow,
  so an unconfigured server is not pinged on every dashboard open).
- Unit tests: `AiRulesTest`, `AiCoachPayloadTest` (the latter pins the exact
  wire payload — the ONLY personal data that ever reaches the provider).

## Privacy: what is sent, and what is not

Exactly four neutral facts travel to the AI provider, nothing else:

| Sent (always)                        | Never sent                      |
|--------------------------------------|---------------------------------|
| first name (first word of profile)   | phone number                    |
| total donation count                 | city / address                  |
| days since last donation (or null)   | age, gender, weight             |
| blood group                          | Auth user id, email, tokens     |

The payload is built by hand in `AiCoach.payloadJson` and pinned byte-for-byte
by `AiCoachPayloadTest`. The server function ignores everything outside
`kind` / `facts` / `question` by design. Nothing the user sends (or the model
replies) is stored anywhere server-side. A local-only session (demo manager
`manager` / `manager123`, or an offline fallback login) has no Supabase token
and never reaches the provider at all.

## SQL run order

Run **once** in the Supabase SQL editor, after the Phase 2 and Phase 3 files:

1. `supabase_ai_migration.sql` — creates `ai_coach_usage` (additive, safe).

Rollback: `supabase_ai_migration_rollback.sql` (drops the table; a future
fresh table also resets everyone's call counters).

## Exact steps: Supabase

1. **Rate-limit table:** run `supabase_ai_migration.sql` (above).
2. **API key.** Get an OpenRouter key (openrouter.ai → Keys) and store it
   ONLY as a Supabase secret — never in the repo, never in the app:
   ```
   supabase secrets set OPENROUTER_API_KEY=sk-or-v1-...
   ```
   Optional: a specific model, default is `google/gemini-2.0-flash-001`:
   ```
   supabase secrets set AI_MODEL=google/gemini-2.0-flash-001
   ```
3. **Deploy the function:**
   ```
   supabase functions deploy ai-coach
   ```
   Do NOT pass `--no-verify-jwt` — the opposite of `push-notify`. This
   function must only ever be called with a signed-in user's access token,
   and it additionally re-checks the token with
   `supabase.auth.getUser(token)` inside.

## What happens without, and at the limits

- **No key set / function not deployed** → the app shows the local fact of the
  day and the helper's fallback text. No error, no crash, no retry storm.
- **Daily limit (30 calls per signed-in user per 24 h)** → HTTP 429; the
  helper screen shows its fallback text, and tomorrow the user gets 30 more.
- **Local-only accounts** → no token, so no provider call, no quota use.
- **Every call is blocking network I/O on a background thread** and every
  failure is swallowed into a `null` reply — the UI handles null, never
  crashes.

## Concrete verification (3 minutes)

1. Deploy, set the key, install the APK, sign in as a donor with a profile.
2. Dashboard: after a few seconds the "Did you know?" card should show a
   short sentence that mentions your name or count — different from the local
   fact. Re-open the app: the same text stays all day (cached per calendar
   day).
3. Tap "🩸 Can I donate? (AI helper)", type a general question
   (e.g. "Can I donate after a flu shot?") and press Ask: a short plain-text
   answer should appear — with no medical advice relating specifically to
   you, and the disclaimer visible above it.
4. Type a question with personal details in it: the answer must not ask for
   more information (the system prompt forbids it) — the app itself does not
   send anything beyond the four facts anyway.
5. Sign out / use the demo manager (`manager` / `manager123`): the helper
   still opens, but the reply is the fallback text — it can never hit the
      provider without an Auth session.

## Known limits (stated plainly)

- The helper is information, never permission: only a licensed blood bank's
  screening decides who donates. This is said on the screen, in the system
  prompt, and in the fallback text.
- One language model, no conversation: each question stands alone, replies
  are clamped to 600 characters, questions to 500.
- The daily limit is per user, but a returned quota read may briefly allow a
  couple of extra calls under a race — it is a rate limit, not a firewall.
- OpenRouter bills you for successful calls; the function gives up on its
  first provider error (502) and the app falls back rather than retrying.
