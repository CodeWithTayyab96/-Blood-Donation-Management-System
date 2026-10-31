// Blood Donation — Phase 3 push notifications (Firebase Cloud Messaging).
//
// Called exclusively by the SQL triggers in supabase_fcm_triggers.sql via
// pg_net. Deployed as a Supabase Edge Function WITHOUT JWT verification
// (the callers are database triggers, not users with tokens):
//
//   supabase functions deploy push-notify --no-verify-jwt
//
// Secrets it reads, set once with `supabase secrets set KEY=VALUE` — none of
// them is ever stored in or committed to this repository:
//   FCM_SERVICE_ACCOUNT_JSON  the FULL service-account JSON (the only place
//                             it exists; the app never sees it)
//   PUSH_WEBHOOK_SECRET       same string the SQL triggers send as their
//                             Bearer token; anything else is rejected
//
// Three event types arrive, decided by the triggers:
//   offer           → to the REQUESTER's devices
//   accepted        → to the DONOR's devices
//   urgent_request  → to every compatible, available, eligible donor's
//                     devices (Normal requests are not pushed)
//
// Messages are sent data-only: the app builds its own notification, so the
// same dedup rules apply whether an alert arrives by push or by the 15-minute
// poll fallback. Delivery remains "best effort": FCM may throttle, a device
// may have no token registered (never signed in, tokens cleared, 45 days
// stale). That is why the README says delivery is approximate in every mode.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const MAX_URGENT_RECIPIENTS = 300;

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

const supabase = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

function badRequest(status: number, message: string): Response {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function ok(body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
  });
}

// ---------------------------------------------------------------------------
// FCM HTTP v1: sign a service-account JWT for an access token, then POST the
// message. The access token is cached for ~50 minutes.
// ---------------------------------------------------------------------------

let cachedAccessToken: { token: string; until: number } | null = null;

function base64UrlFromBytes(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64Url(text: string): string {
  return base64UrlFromBytes(new TextEncoder().encode(text));
}

function importPrivateKey(pem: string): Promise<CryptoKey> {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");
  const raw = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    raw,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
}

async function getAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedAccessToken.until > now + 60) {
    return cachedAccessToken.token;
  }
  const claims = JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/cloud-platform",
    aud: sa.token_uri ?? "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  });
  const unsigned = `${base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.` +
    base64Url(claims);
  const key = await importPrivateKey(sa.private_key);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  const jwt = `${unsigned}.${base64UrlFromBytes(new Uint8Array(signature))}`;

  const res = await fetch(sa.token_uri ?? "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) throw new Error(`OAuth token request failed: HTTP ${res.status}`);
  const data = await res.json();
  if (!data.access_token) throw new Error("OAuth response had no access_token");
  cachedAccessToken = { token: data.access_token, until: now + 3000 };
  return data.access_token;
}

/** Sends one data-only message. Each value must be a string for FCM v1. */
async function sendToToken(
  sa: ServiceAccount,
  accessToken: string,
  token: string,
  data: Record<string, string>,
): Promise<"ok" | "gone" | "failed"> {
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ message: { token, data } }),
    },
  );
  if (res.ok) return "ok";
  if (res.status === 404 || res.status === 410) return "gone"; // stale token
  // FCM errors (invalid field, throttling…) are logged but not fatal.
  console.error(`FCM send failed for a token: HTTP ${res.status} ${await res.text()}`);
  return "failed";
}

// ---------------------------------------------------------------------------
// The three events.
// ---------------------------------------------------------------------------

/** Tokens registered for the given user ids, fresh ones first. */
async function tokensFor(
  userIds: string[],
  limit: number,
): Promise<{ userId: string; token: string }[]> {
  if (userIds.length === 0) return [];
  const cutoff = new Date(Date.now() - 45 * 86400 * 1000).toISOString();
  const { data, error } = await supabase
    .from("device_tokens")
    .select("user_id, token, updated_at")
    .in("user_id", userIds)
    .gte("updated_at", cutoff)
    .order("updated_at", { ascending: false })
    .limit(limit);
  if (error) throw new Error(`device_tokens read failed: ${error.message}`);
  return (data ?? []).map((r: { user_id: string; token: string }) => ({
    userId: r.user_id,
    token: r.token,
  }));
}

async function sendEach(
  sa: ServiceAccount,
  recipients: { userId: string; token: string }[],
  data: Record<string, string>,
  returnedStats: { sent: number; stale: number; failed: number },
): Promise<void> {
  const accessToken = await getAccessToken(sa);
  for (const r of recipients) {
    const result = await sendToToken(sa, accessToken, r.token, data);
    if (result === "ok") returnedStats.sent++;
    else if (result === "gone") returnedStats.stale++;
    else returnedStats.failed++;
  }
}

// Transfusion compatibility, donor → patient group. Same table as
// BloodCompatibility.kt; duplicated here on purpose so the server can fan out
// without trusting the client.
const CAN_DONATE_TO: Record<string, string[]> = {
  "O-": ["O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+"],
  "O+": ["O+", "A+", "B+", "AB+"],
  "A-": ["A-", "A+", "AB-", "AB+"],
  "A+": ["A+", "AB+"],
  "B-": ["B-", "B+", "AB-", "AB+"],
  "B+": ["B+", "AB+"],
  "AB-": ["AB-", "AB+"],
  "AB+": ["AB+"],
};

async function firstDonationOlderThan90Days(last: string | null): Promise<boolean> {
  if (!last) return true;
  const t = Date.parse(last);
  if (Number.isNaN(t)) return true; // unusable value: treat as never donated
  return Date.now() - t >= 90 * 86400 * 1000;
}

async function handleUrgentRequest(body: any): Promise<Record<string, unknown>> {
  const sa = await getServiceAccount();
  const group = String(body.blood_group ?? "");
  const requestId = String(body.request_id ?? "");
  if (!group || !requestId) return { ignored: "incomplete payload" };

  // Available donors only; eligibility (age, weight, 90-day gap) is applied
  // here the same way the poll applies it on the phone.
  const { data, error } = await supabase
    .from("donors")
    .select("auth_id, blood_group, weight, age, last_donation_date")
    .eq("available_to_donate", true)
    .limit(1000);
  if (error) throw new Error(`donors read failed: ${error.message}`);

  const recipientIds = new Set<string>();
  for (const d of data ?? []) {
    if (!d.auth_id) continue;
    if (d.auth_id === (body.requester_id ?? null)) continue;
    if (!(CAN_DONATE_TO[d.blood_group] ?? []).includes(group)) continue;
    const age = Number(d.age ?? 0);
    const weight = Number(d.weight ?? 0);
    if (age < 18 || age > 65 || weight < 50) continue;
    if (!(await firstDonationOlderThan90Days(d.last_donation_date))) continue;
    recipientIds.add(d.auth_id);
  }

  const recipients = await tokensFor(
    [...recipientIds],
    body.urgency === "Critical" ? MAX_URGENT_RECIPIENTS * 2 : MAX_URGENT_RECIPIENTS,
  );
  const stats = { sent: 0, stale: 0, failed: 0 };
  await sendEach(sa, recipients, {
    type: "urgent_request",
    request_id: requestId,
    blood_group: group,
    urgency: String(body.urgency ?? "Urgent"),
    units_needed: String(body.units_needed ?? "1"),
    hospital: String(body.hospital ?? ""),
    contact: String(body.contact ?? ""),
    city: String(body.city ?? ""),
  }, stats);
  return { recipients: recipients.length, ...stats };
}

async function handleOffer(body: any): Promise<Record<string, unknown>> {
  const sa = await getServiceAccount();
  if (!body.requester_id || !body.offer_id) return { ignored: "incomplete payload" };
  const recipients = await tokensFor([body.requester_id], 20);
  const stats = { sent: 0, stale: 0, failed: 0 };
  const first = String(body.donor_name ?? "Someone").split(" ")[0];
  await sendEach(
    sa,
    recipients,
    {
      type: "offer",
      offer_id: String(body.offer_id),
      request_id: String(body.request_id),
      title: "New offer on your request",
      body:
        `${first} (${String(body.donor_group ?? "?")}) offered to donate. ` +
        "Open the app to accept or decline.",
    },
    stats,
  );
  return { recipients: recipients.length, ...stats };
}

async function handleAccepted(body: any): Promise<Record<string, unknown>> {
  const sa = await getServiceAccount();
  if (!body.donor_auth_id || !body.offer_id) return { ignored: "incomplete payload" };
  const donors = await tokensFor([body.donor_auth_id], 20);
  const stats = { sent: 0, stale: 0, failed: 0 };
  await sendEach(
    sa,
    donors,
    {
      type: "accepted",
      offer_id: String(body.offer_id),
      request_id: String(body.request_id ?? ""),
      title: "Your offer was accepted",
      body:
        "Contact details are in the app. Donation must happen at a licensed " +
        "blood bank or hospital.",
    },
    stats,
  );
  return { recipients: donors.length, ...stats };
}

// ---------------------------------------------------------------------------
// Entry point.
// ---------------------------------------------------------------------------

/** Reads the service account from secrets, once per isolate. */
let serviceAccount: Promise<ServiceAccount>;
function getServiceAccount(): Promise<ServiceAccount> {
  serviceAccount ??= (async () => {
    const raw = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
    if (!raw) throw new Error("FCM_SERVICE_ACCOUNT_JSON secret is not set");
    const parsed = JSON.parse(raw);
    if (!parsed.project_id || !parsed.client_email || !parsed.private_key) {
      throw new Error("FCM_SERVICE_ACCOUNT_JSON is not a complete service account");
    }
    return parsed as ServiceAccount;
  })();
  return serviceAccount;
}

Deno.serve(async (req) => {
  // Only these database triggers may talk to us — Bearer PUSH_WEBHOOK_SECRET.
  const presented = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  const expected = Deno.env.get("PUSH_WEBHOOK_SECRET") ?? "";
  if (expected === "" || presented !== expected) {
    return badRequest(403, "forbidden");
  }

  let body: any;
  try {
    body = await req.json();
  } catch {
    return badRequest(400, "body must be JSON");
  }

  try {
    switch (body?.type) {
      case "offer":
        return ok(await handleOffer(body));
      case "accepted":
        return ok(await handleAccepted(body));
      case "urgent_request":
        return ok(await handleUrgentRequest(body));
      default:
        return ok({ ignored: "unknown type" });
    }
  } catch (e) {
    console.error("push-notify failed:", e);
    return badRequest(500, String(e?.message ?? e));
  }
});
