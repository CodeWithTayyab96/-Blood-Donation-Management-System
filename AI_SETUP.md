# AI helper setup

**This file is a pointer.** The AI helper's setup now lives in **RUNBOOK.md
Stage C**, together with the SQL, the secrets, the deploy command, the
rollback and the phone checklist.

Two things changed since this file was written, and both matter:

1. **No personal data is sent any more.** The payload used to carry four
   "neutral facts" — first name, donation count, days since the last donation,
   blood group. It carries **none of them** now: only the request kind and, for
   a question, the question itself.
2. **The motivational line is shared.** Free OpenRouter models allow roughly 50
   requests a day for the whole account, so one call per user would run out
   after fifty users. Instead the first request of a day generates about 30
   quotes **in a single call**, validates them, and stores them; every other
   user that day is served from that bank for free. The per-user limit is now
   **5 questions a day**, not 30 calls.

The short version:

```bash
# 1. SQL (in this order, in the SQL editor)
#    supabase_ai_migration.sql
#    supabase_ai_quotebank.sql

# 2. Secrets
supabase secrets set OPENROUTER_API_KEY=sk-or-v1-YOUR-KEY-HERE
supabase secrets set AI_MODEL=openrouter/free        # optional; this is the default

# 3. Deploy (JWT verification stays ON)
supabase functions deploy ai-coach
```

The key never leaves Supabase: it is read inside the Edge Function from
`OPENROUTER_API_KEY`, and no client ever sees it.

**Verify:** `supabase_verify_policies.sql` prints `ALL POLICIES OK`.
