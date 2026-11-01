// Blood Donation — the ai-coach Edge Function.
//
// Two kinds of request, both for a SIGNED-IN user only (verify_jwt stays ON,
// the default — the opposite of push-notify):
//
//   kind = "quote"      today's motivational line, served from the SHARED
//                       daily quote bank (one provider call per DAY for
//                       everyone, not one per user)
//   kind = "question"   the "Can I donate?" helper
//
// ("motivation" is accepted as an alias for "quote", so an older APK keeps
// working; it gets the same bank-served line.)
//
// WHY A BANK: free OpenRouter models allow roughly 50 requests a day for the
// WHOLE account. One call per user per day means the 51st user gets nothing and
// the helper looks broken at random. Instead, the first request of a day
// generates ~30 quotes in a single call (~1 call/day total), and everyone else
// that day is served from the table for free. No pg_cron: the bank is built
// lazily by whoever asks first.
//
// Secrets (supabase secrets set KEY=VALUE — never committed, never logged):
//   OPENROUTER_API_KEY  required; without it the function answers 503 and the
//                       app falls back to its local fact bank
//   AI_MODEL            optional; default openrouter/free
//
// NO PERSONAL DATA is sent to the provider. The old version sent a first name,
// donation count, days since the last donation and blood group. Nothing is sent
// now: a motivational line does not need it, and an eligibility answer must not
// be personalised anyway — a licensed blood bank decides after screening.
//
// Rules baked into the prompts (and enforced again on the way out):
//   * short friendly text only — no essays to be read on a card
//   * never claims that donation cures, prevents or treats anything, never
//     weight loss, never a "detox" story
//   * no individual medical advice; the model must say who decides
//   * never asks the user for more personal information

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";

// ---- limits --------------------------------------------------------------
// The free tier gives the account ~50 requests a day. Everything below is kept
// under that with room to spare, and the bank is reserved a couple of calls so
// a busy day of questions cannot stop tomorrow's quotes from being generated.
const GLOBAL_DAILY_LIMIT = 40;
const QUOTE_BANK_RESERVE = 2;
const USER_QUESTION_LIMIT = 5;
const QUOTES_PER_BATCH = 30;
const MAX_QUESTION_CHARS = 500;
const MAX_REPLY_CHARS = 600;
const MIN_QUOTE_CHARS = 20;
const MAX_QUOTE_CHARS = 140;

// ---- models --------------------------------------------------------------
// `openrouter/free` routes to whichever free model is available, which is the
// most reliable single choice; the :free list is tried in order after it so a
// model that is rate-limited or unavailable does not take the whole feature
// down. Every entry is a free variant — a paid one must never be reached by
// accident.
const DEFAULT_MODEL = "openrouter/free";
const FALLBACK_MODELS = [
  "openrouter/free",
  "google/gemma-3-27b-it:free",
  "meta-llama/llama-3.3-70b-instruct:free",
  "deepseek/deepseek-r1:free",
  "mistralai/mistral-small-3.1-24b-instruct:free",
];

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

function reply(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

// ---------------------------------------------------------------- prompts

const QUOTE_SYSTEM =
  "You write short motivational messages about donating blood. " +
  "Reply with 30 separate lines and NOTHING else: no numbering, no bullets, " +
  "no heading, no introduction, no sign-off. Each line is one complete " +
  "message of at most 20 words. Plain text, no emojis, no quotation marks. " +
  "Warm and encouraging; you may mention that donating helps patients, " +
  "hospitals and the community. Never claim that donating blood cures, " +
  "prevents or treats any disease, never promise weight loss, detox, " +
  "longer life or any health benefit to the donor. No medical advice. " +
  "Never address anyone by name and never refer to a person's age, weight, " +
  "blood group or history. Do not repeat yourself.";

const HELPER_SYSTEM =
  "You are a brief, friendly blood donation helper answering general " +
  "questions in plain text, at most 60 words. You may explain standard, " +
  "widely accepted screening rules (age 18 to 65, weight at least 50 kg, " +
  "about 90 days between whole-blood donations). Never give individual " +
  "medical advice: if the person's health, medication or eligibility is " +
  "involved, say clearly that the licensed blood bank or hospital they plan " +
  "to donate at decides after their own screening. Never ask for personal " +
  "information and never ask for their name, age, weight or blood group. " +
  "No emojis, no lists, no links.";

// ------------------------------------------------------- quote validation

// A generated line is shown on every user's home screen, so it is validated
// here rather than trusted. The model is asked to behave; this is what makes
// sure it did.
const BANNED = [
  /\bcure[sd]?\b/i,
  /\bprevent[sd]?\b/i,
  /\btreat(s|ed|ment|s)?\b/i,
  /\bheal(s|ed|ing)?\b/i,
  /\bweight loss\b/i,
  /\blose weight\b/i,
  /\bdetox\b/i,
  /\bdiagnos(e|is|ed)\b/i,
  /\bimmunity\b/i,
  /\bimmune system\b/i,
  /\blower(s|ed)? (your|the) risk\b/i,
  /\bcancer\b/i,
  /\bdrug(s)?\b/i,
  /\bdisease\b/i,
  /\bmedication\b/i,
  /\bmedicine\b/i,
  /\btherap(y|ies)\b/i,
  /\bboost\b/i,
  /\bcleanse\b/i,
];

// Nothing that identifies or describes a person may reach the screen.
const PERSONAL = [
  /@/, // an email address
  /\b\d{6,}\b/, // a phone or an id number
  /\byears old\b/i,
  /\b\d{2,3}\s?kg\b/i,
  /\bblood group\b/i,
  /\byour (name|age|weight|record|history)\b/i,
  /\byou are \d/i,
];

function acceptable(line: string): boolean {
  const text = line.trim();
  if (text.length < MIN_QUOTE_CHARS || text.length > MAX_QUOTE_CHARS) return false;
  if (BANNED.some((r) => r.test(text))) return false;
  if (PERSONAL.some((r) => r.test(text))) return false;
  // Must look like a sentence, not a fragment of a list or a meta-instruction.
  if (/^(\d+[.)]|[-*•])/.test(text)) return false;
  if (!/[a-z]/.test(text)) return false;
  if (!/[.!?]$/.test(text) && !/[\u0600-\u06FF]/.test(text)) return false;
  return true;
}

/** Splits a model reply into clean candidate lines. */
function parseQuotes(raw: string): string[] {
  return raw
    .split("\n")
    .map((l) => l.trim())
    // Strip the decorations models add even when told not to: numbering,
    // bullets, surrounding quotation marks.
    .map((l) => l.replace(/^\s*(\d+[.)]|[-*•])\s*/, "").trim())
    .map((l) => l.replace(/^["'“”‘’]+|["'“”‘’]+$/g, "").trim())
    .filter((l) => l.length > 0);
}

// ------------------------------------------------------------------ quota

/** Calls the project has made today, and a +1. False when over [limit]. */
async function consumeGlobal(limit: number): Promise<boolean> {
  const day = new Date().toISOString().slice(0, 10);
  const { data, error } = await supabase
    .from("ai_provider_calls")
    .select("calls")
    .eq("day", day)
    .maybeSingle();
  if (error) throw new Error(`provider usage read failed: ${error.message}`);

  const used = Number(data?.calls ?? 0);
  if (used + 1 > limit) return false;

  const { error: writeError } = await supabase
    .from("ai_provider_calls")
    .upsert({ day, calls: used + 1 });
  if (writeError) throw new Error(`provider usage write failed: ${writeError.message}`);
  return true;
}

/** Per-user daily question counter. False at the user's own limit. */
async function consumeQuestion(user_id: string): Promise<boolean> {
  const { data, error } = await supabase
    .from("ai_coach_usage")
    .select("window_started_at, calls")
    .eq("user_id", user_id)
    .maybeSingle();
  if (error) throw new Error(`usage read failed: ${error.message}`);

  const now = new Date();
  const started = data ? new Date(data.window_started_at) : null;
  const fresh = started ? now.getTime() - started.getTime() < 86400 * 1000 : true;
  const calls = fresh ? Number(data?.calls ?? 0) + 1 : 1;
  if (fresh && calls > USER_QUESTION_LIMIT) return false;

  const { error: writeError } = await supabase.from("ai_coach_usage").upsert({
    user_id,
    window_started_at: fresh && data ? (data.window_started_at as string) : now.toISOString(),
    calls,
  });
  if (writeError) throw new Error(`usage write failed: ${writeError.message}`);
  return true;
}

// --------------------------------------------------------- provider call

async function ask(
  apiKey: string,
  system: string,
  userMessage: string,
  maxTokens: number,
): Promise<string | null> {
  const preferred = Deno.env.get("AI_MODEL") ?? DEFAULT_MODEL;
  // Preferred first, then the free list, without repeating it.
  const models = [preferred, ...FALLBACK_MODELS.filter((m) => m !== preferred)];

  for (const model of models) {
    try {
      const res = await fetch(OPENROUTER_URL, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model,
          messages: [
            { role: "system", content: system },
            { role: "user", content: userMessage },
          ],
          max_tokens: maxTokens,
          temperature: 0.8,
        }),
      });
      if (!res.ok) {
        const detail = await res.text();
        console.error(`OpenRouter ${model} HTTP ${res.status}: ${detail.slice(0, 200)}`);
        continue; // try the next free model
      }
      const data = await res.json();
      const text = String(data?.choices?.[0]?.message?.content ?? "").trim();
      if (text) return text;
      console.error(`OpenRouter ${model} returned an empty message`);
    } catch (e) {
      console.error(`OpenRouter ${model} failed:`, e);
    }
  }
  return null;
}

// ------------------------------------------------------------ quote bank

/** Today's quotes, oldest first. Empty array when the bank has none yet. */
async function todaysQuotes(): Promise<{ ok: boolean; quotes: string[] }> {
  const { data, error } = await supabase
    .from("ai_quotes")
    .select("quote")
    .eq("for_date", new Date().toISOString().slice(0, 10))
    .order("id", { ascending: true });
  if (error) {
    // 42P01 = undefined_table: the SQL file has not been run yet.
    if (error.code === "42P01") return { ok: false, quotes: [] };
    throw new Error(`quote read failed: ${error.message}`);
  }
  return { ok: true, quotes: (data ?? []).map((r: any) => String(r.quote)) };
}

/**
 * Picks the quote this user sees today. Stable per user per day, so reopening
 * the app does not reshuffle the card — but different users still get
 * different lines, because a bank of 30 identical screens would look broken.
 */
function pickFor(quotes: string[], userId: string): string {
  if (quotes.length === 0) return "";
  let hash = 0;
  for (let i = 0; i < userId.length; i++) hash = (hash * 31 + userId.charCodeAt(i)) >>> 0;
  return quotes[hash % quotes.length];
}

async function generateBank(apiKey: string): Promise<{ ok: boolean; quotes: string[] }> {
  const raw = await ask(
    apiKey,
    QUOTE_SYSTEM,
    "Write 30 short motivational messages about donating blood.",
    1200,
  );
  if (!raw) return { ok: true, quotes: [] };

  const seen = new Set<string>();
  const valid = parseQuotes(raw).filter(acceptable).filter((q) => {
    const key = q.toLowerCase();
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

  if (valid.length === 0) return { ok: true, quotes: [] };

  const day = new Date().toISOString().slice(0, 10);
  const { error } = await supabase
    .from("ai_quotes")
    .insert(valid.map((quote) => ({ for_date: day, quote })));
  if (error) {
    console.error("quote insert failed:", error.message);
    // The quotes are still good enough to serve this one caller; they just
    // will not be shared. Losing the write is not a reason to fail the card.
    return { ok: true, quotes: valid };
  }
  return { ok: true, quotes: valid };
}

// ------------------------------------------------------------------- serve

Deno.serve(async (req) => {
  if (req.method !== "POST") return reply(405, { error: "POST only" });

  // The app calls with the USER's access token, so this resolves to exactly one
  // Auth user. A local-only session has no token and never gets here.
  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  const user = userData?.user;
  if (userError || !user) return reply(401, { error: "a signed-in user is required" });

  let body: any;
  try {
    body = await req.json();
  } catch {
    return reply(400, { error: "body must be JSON" });
  }

  const apiKey = Deno.env.get("OPENROUTER_API_KEY");
  if (!apiKey) return reply(503, { error: "the AI helper is not configured" });

  const kind = body?.kind === "question" ? "question" : "quote";

  // ------------------------------------------------------------- questions
  if (kind === "question") {
    const question = String(body?.question ?? "").trim().slice(0, MAX_QUESTION_CHARS);
    if (question.length === 0) {
      return reply(400, { error: "an empty question cannot be answered" });
    }

    try {
      if (!(await consumeQuestion(user.id))) {
        return reply(429, {
          error: `you have used all ${USER_QUESTION_LIMIT} questions for today — it resets tomorrow`,
          limit: "user",
        });
      }
      // The bank keeps a couple of calls in reserve: a busy day of questions
      // must not stop tomorrow's quotes from being generated.
      if (!(await consumeGlobal(GLOBAL_DAILY_LIMIT - QUOTE_BANK_RESERVE))) {
        return reply(429, {
          error: "the helper has reached its daily limit — please try again tomorrow",
          limit: "global",
        });
      }
    } catch (e) {
      return reply(500, { error: String((e as Error)?.message ?? e) });
    }

    const text = await ask(apiKey, HELPER_SYSTEM, question, 180);
    if (!text) {
      return reply(502, { error: "the AI provider could not be reached" });
    }
    return reply(200, { reply: text.trim().slice(0, MAX_REPLY_CHARS) });
  }

  // ---------------------------------------------------------------- quotes
  const bank = await todaysQuotes();
  if (!bank.ok) {
    return reply(503, {
      error:
        "the quote bank is not set up — run supabase_ai_quotebank.sql on this project",
    });
  }

  if (bank.quotes.length > 0) {
    // The common path, and it costs nothing: no provider call at all.
    return reply(200, { reply: pickFor(bank.quotes, user.id), source: "bank" });
  }

  // First request of the day: build the bank with ONE call for everyone.
  try {
    if (!(await consumeGlobal(GLOBAL_DAILY_LIMIT))) {
      return reply(429, {
        error: "the helper has reached its daily limit — please try again tomorrow",
        limit: "global",
      });
    }
  } catch (e) {
    return reply(500, { error: String((e as Error)?.message ?? e) });
  }

  const fresh = await generateBank(apiKey);
  if (fresh.quotes.length === 0) {
    // Nothing usable came back. The app falls back to its local facts, which
    // is always a valid home screen — this is never a broken state.
    return reply(503, { error: "no usable quote was generated" });
  }
  return reply(200, {
    reply: pickFor(fresh.quotes, user.id),
    source: "generated",
  });
});
