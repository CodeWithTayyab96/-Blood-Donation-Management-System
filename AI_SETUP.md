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

The short version — **and note that these are three different places**:

**1. SQL Editor:** paste `supabase_ai_migration.sql`, then
`supabase_ai_quotebank.sql`, and run each.

**2. Secrets — NOT the SQL Editor.** Either use the dashboard
(`https://supabase.com/dashboard/project/<your-project-ref>/functions/secrets`,
Key `OPENROUTER_API_KEY` = your key, then Key `AI_MODEL` = `openrouter/free`),
or run in a **terminal**:

```bash
supabase secrets set OPENROUTER_API_KEY=sk-or-v1-YOUR-KEY-HERE
supabase secrets set AI_MODEL=openrouter/free        # optional; this is the default
```

**3. Deploy — also a terminal command:**

```bash
supabase functions deploy ai-coach                  # JWT verification stays ON
```

If you paste 2 or 3 into the SQL Editor you get
`ERROR: 42601: syntax error at or near "supabase"` — they are not SQL.

⚠️ A key typed into the SQL Editor is saved in your query history. If that
happens, delete the key at openrouter.ai/keys and make a new one first.

The key never leaves Supabase: it is read inside the Edge Function from
`OPENROUTER_API_KEY`, and no client ever sees it. Functions read a new secret
immediately, so there is no redeploy after step 2.

**Verify:** `supabase_verify_policies.sql` prints `ALL POLICIES OK`.
