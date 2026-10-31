// Blood Donation — Phase 4: the ai-coach Edge Function.
//
// Two kinds of request, both for a SIGNED-IN user only (verify_jwt stays ON,
// the default — the opposite of push-notify):
//
//   kind = "motivation"  one personalised home-card message per day, built
//                        from ONLY: first name, donation count, days since
//                        the last donation, blood group (never more data;
//                        the app builds this payload from its own local data)
//   kind = "question"    the "Can I donate?" helper: a short question plus
//                        the same four neutral facts
//
// Secrets (supabase secrets set KEY=VALUE — never committed, never logged):
//   OPENROUTER_API_KEY  required; requests without it answer 503 and the app
//                       falls back to its local fact bank
//   AI_MODEL            optional; default google/gemini-2.0-flash-001
//
// Rules baked into the prompts (and the max_tokens):
//   * short friendly replies only — no long essays to be read on a card
//   * never claims that donation cures, prevents or treats anything, never
//     weight loss, never a "detox" story
//   * no individual medical advice — a licensed blood bank / hospital
//     screening decides; the model must say so when health is involved
//   * never asks the user for more personal information
//
// Rate limit: DAILY_LIMIT calls per signed-in user per 24h, tracked in
// ai_coach_usage (service role only — the user cannot touch their own
// counter). Personal data is never included in the provider request beyond
// the four whitelisted facts, and this function never writes user content
// anywhere.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const DAILY_LIMIT = 30;
const MAX_QUESTION_CHARS = 1000;
const MAX_REPLY_CHARS = 600;

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

// The four whitelisted neutral facts, read defensively. Everything else in
// the request body is ignored on purpose — helper, not data collection.
const MOTIVATION_SYSTEM =
  "You encourage blood donors in ONE short message of at most 25 words. " +
  "Friendly, warm, plain text. Never claim that donating blood cures, " +
  "prevents or treats any disease, and never promise weight loss, detox or " +
  "other health benefits. You may reference the facts you were given. " +
  "No emojis, no lists, no medical advice.";

const HELPER_SYSTEM =
  "You are a brief, friendly blood donation helper answering general " +
  "questions in plain text, at most 60 words. You may explain standard, " +
  "widely accepted screening rules (age 18 to 65, weight at least 50 kg, " +
  "90 days between whole-blood donations). Never give individual medical " +
  "advice: if the person's health or medication is involved, say that a " +
  "licensed blood bank or a doctor must decide after their screening. " +
  "Never ask for personal information. No emojis, no lists.";

function factsOf(body: any): string {
  const name = String(body?.facts?.name ?? "friend").split(" ")[0].slice(0, 40);
  const group = String(body?.facts?.blood_group ?? "").slice(0, 3);
  const donations = Math.max(0, Number(body?.facts?.donations ?? 0) | 0);
  const days = body?.facts?.days_since_last_donation;
  const daysText =
    days == null ? "no donation on record" : `last donation ${Math.max(0, Number(days) | 0)} days ago`;
  return `Name: ${name}. Blood group: ${group}. Prior donations: ${donations}. ${daysText}.`;
}

/** Consumes one call in the current 24-hour window; false at the daily limit. */
async function consume_quota(user_id: string): Promise<boolean> {
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
  if (fresh && calls > DAILY_LIMIT) return false;

  const { error: writeError } = await supabase.from("ai_coach_usage").upsert({
    user_id,
    window_started_at: fresh && data ? (data.window_started_at as string) : now.toISOString(),
    calls,
  });
  return !writeError;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return reply(405, { error: "POST only" });

  // Authentication: the app calls with the USER's access token, so this
  // resolves to exactly one Auth user. Local-only demo sessions have no
  // token and never get here (token validation by the platform's verify_jwt
  // plus this getUser double-check).
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

  try {
    if (!(await consume_quota(user.id))) {
      return reply(429, { error: "daily limit reached — try again tomorrow" });
    }
  } catch (e) {
    return reply(500, { error: String(e?.message ?? e) });
  }

  const kind = body?.kind === "question" ? "question" : "motivation";

  // Default behaviour: falling back to local facts is always fine.
  const question = kind === "question"
    ? String(body?.question ?? "").trim().slice(0, MAX_QUESTION_CHARS)
    : "";
  if (kind === "question" && question.length === 0) {
    return reply(400, { error: "an empty question cannot be answered" });
  }

  const system = kind === "question" ? HELPER_SYSTEM : MOTIVATION_SYSTEM;
  const userMessage = kind === "question"
    ? `${factsOf(body)}\nQuestion: ${question}`
    : `Write today's encouragement.\n${factsOf(body)}`;

  let text = "";
  try {
    const res = await fetch(OPENROUTER_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: Deno.env.get("AI_MODEL") ?? "google/gemini-2.0-flash-001",
        messages: [
          { role: "system", content: system },
          { role: "user", content: userMessage },
        ],
        max_tokens: kind === "question" ? 140 : 70,
        temperature: 0.8,
      }),
    });
    if (!res.ok) {
      const detail = await res.text();
      console.error(`OpenRouter HTTP ${res.status}: ${detail.slice(0, 400)}`);
      return reply(502, { error: "the AI provider refused the request" });
    }
    const data = await res.json();
    text = String(data?.choices?.[0]?.message?.content ?? "");
  } catch (e) {
    console.error("OpenRouter call failed:", e);
    return reply(502, { error: "the AI provider could not be reached" });
  }

  text = text.trim().slice(0, MAX_REPLY_CHARS);
  if (!text) return reply(502, { error: "the AI provider returned nothing useful" });
  return reply(200, { reply: text });
});
