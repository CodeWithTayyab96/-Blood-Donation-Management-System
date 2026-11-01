// Blood Donation — "Delete my account" Edge Function.
//
// verify_jwt stays ON (the default): the platform rejects a request without a
// valid Supabase token before this code runs. This function then resolves the
// caller from that token and deletes THEIR account — never an id supplied by
// the client.
//
// Order matters, and so does what happens when the second step fails:
//
//   1. delete/anonymise the personal data via public.delete_account_data(uuid)
//      (SECURITY DEFINER; it keeps the donation history and anonymises the
//      donor row, see supabase_delete_account.sql)
//   2. auth.admin.deleteUser(caller) with the service role key Supabase injects
//
// If (1) fails nothing is deleted and the user is told so. If (1) succeeds and
// (2) fails, the response says `dataDeleted: true`: their personal data is
// gone but the login still exists. The whole function is idempotent, so
// pressing the button again simply finishes the job — it never half-deletes a
// different account.
//
// Managers are refused: the blood bank's own account cannot delete itself from
// the app (it would take the only account that can administer the project with
// it, and there would be no one left to re-create it).
//
// No secret is read from the client and nothing is logged beyond an error
// message. SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are injected by the
// platform.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return json({ error: "POST only" }, 405);
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) {
    return json({ error: "a signed-in user is required" }, 401);
  }

  const url = Deno.env.get("SUPABASE_URL");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceKey) {
    return json({ error: "the function is not configured" }, 503);
  }

  const admin = createClient(url, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  // Who is calling? The token is verified against GoTrue, not trusted as-is.
  const { data: userData, error: userError } = await admin.auth.getUser(token);
  const user = userData?.user;
  if (userError || !user) {
    return json({ error: "invalid or expired session" }, 401);
  }

  const role = (user.app_metadata as Record<string, unknown> | null)?.role;
  if (role === "MANAGER") {
    return json(
      { error: "manager accounts cannot delete themselves from the app" },
      403,
    );
  }

  // 1. Personal data.
  const { error: dataError } = await admin.rpc("delete_account_data", {
    p_user_id: user.id,
  });
  if (dataError) {
    return json(
      { error: "data deletion failed", detail: dataError.message },
      500,
    );
  }

  // 2. The login itself.
  const { error: authError } = await admin.auth.admin.deleteUser(user.id);
  if (authError) {
    return json(
      {
        error: "your data was deleted but the login still exists",
        detail: authError.message,
        dataDeleted: true,
      },
      500,
    );
  }

  return json({ ok: true }, 200);
});
