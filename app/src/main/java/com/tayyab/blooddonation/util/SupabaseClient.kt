package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.model.AppointmentBooking
import com.tayyab.blooddonation.model.AppointmentSlot
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Camp
import com.tayyab.blooddonation.model.CampAttendance
import com.tayyab.blooddonation.model.Donation
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.model.Movement
import com.tayyab.blooddonation.model.Offer
import com.tayyab.blooddonation.model.Report
import com.tayyab.blooddonation.model.StatusEvent
import com.tayyab.blooddonation.model.Verification
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Supabase PostgREST client, in the same style as [AppUpdater]:
 * plain [HttpURLConnection] + org.json, no new library dependency.
 *
 * IMPORTANT THREADING RULE: every function here does network I/O and
 * blocks until it returns, so callers MUST run it on a background thread
 * (see [SupabaseSync] and the activities) and post results back with
 * runOnUiThread. Nothing in this file ever touches the UI thread itself.
 *
 * IMPORTANT FAILURE RULE: no function here ever throws. A failed call
 * (no network, bad key, 401/403 from Row Level Security, bad response)
 * returns null / false and records a short note in [lastError]. Callers
 * treat that as "use the local SQLite copy" — the app must never crash
 * because Supabase is unreachable. See SUPABASE_SETUP.md for the RLS
 * note: if PostgREST answers 401/403, the policies in
 * supabase_policies_review.sql are the likely fix.
 *
 * Login/accounts are deliberately NOT here: they stay on local SQLite
 * exactly as in v1.2 (see Session / DbHelper). This client only moves
 * donors, donations, requests and stock.
 *
 * v1.4: the Bearer token is no longer always the anon key. When the
 * user is signed in through Supabase Auth (see SupabaseAuth), the
 * user's own access token is sent instead — the "authenticated" RLS
 * policies on the tables only accept those, and the anon key alone
 * gets nothing back. When no Auth session exists, the anon key is
 * still used as the fallback, exactly as in v1.3. If a call answers
 * 401 (access token expired) and a refresh token exists, the client
 * refreshes the session ONCE through SupabaseAuth, stores the new
 * tokens, and retries the call once; if that also fails, the failure
 * is returned and the caller falls back to local SQLite, as before.
 *
 * The anon key is never written in code: it arrives as the BuildConfig
 * field SUPABASE_ANON_KEY, which Gradle fills from the SUPABASE_ANON_KEY
 * project property (in CI, from a GitHub secret of the same name).
 * With an empty key the client reports [isConfigured] = false and every
 * call returns null / false immediately, so a build without the secret
 * still runs fully offline on SQLite.
 */
object SupabaseClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * The columns this app is allowed to read.
     *
     * `donors.phone` and `requests.contact` are deliberately absent: the
     * database does not grant SELECT on them (see supabase_role_policies.sql),
     * so `select=*` — or naming either column — fails the whole query with
     * 42501. The two numbers are fetched one at a time through [donorPhone]
     * and [requestContact], which the database answers only for the owner and
     * the manager.
     */
    private const val DONOR_COLUMNS =
        "id,name,age,gender,blood_group,city,weight,last_donation_date," +
            "available_to_donate,auth_id"
    private const val REQUEST_COLUMNS =
        "id,patient_name,hospital,blood_group,units_needed,urgency,status," +
            "created_at,requester_id,city,fulfilled_by,needed_by"

    /** True only when a project URL and a non-empty anon key were baked in. */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() &&
            BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** Short note about the most recent failure, for debugging only. */
    var lastError: String? = null
        private set

    private fun baseUrl(): String = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1"

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    /**
     * One raw PostgREST call. Returns (HTTP status, response body or null).
     * Status -1 means the request never completed (no network, timeout...).
     *
     * Bearer rule: the signed-in user's access token when Session holds
     * one, otherwise the anon key. On a 401 with a refresh token
     * available, the session is refreshed once (see SupabaseAuth) and
     * the call is retried exactly once; a second failure is returned
     * as-is. A refresh that fails for a non-network reason means the
     * session is dead, so the stored tokens are cleared and the user
     * will sign in again next time instead of retrying a dead token
     * on every call.
     */
    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        prefer: String? = null
    ): Pair<Int, String?> {
        val (code, text) = requestOnce(method, path, body, prefer)
        if (code == 401) {
            val refresh = Session.refreshToken
            if (!refresh.isNullOrBlank()) {
                val refreshed = SupabaseAuth.refreshSession(refresh)
                if (refreshed != null) {
                    Session.updateTokens(refreshed.accessToken, refreshed.refreshToken)
                    return requestOnce(method, path, body, prefer)
                }
                if (SupabaseAuth.lastError != "No internet connection") {
                    Session.clearTokens()
                }
            }
        }
        return code to text
    }

    /** A single HTTP attempt, with no retry. See [request]. */
    private fun requestOnce(
        method: String,
        path: String,
        body: JSONObject? = null,
        prefer: String? = null
    ): Pair<Int, String?> {
        if (!isConfigured) return -1 to null
        return try {
            val connection = URL(baseUrl() + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer ${Session.accessToken ?: BuildConfig.SUPABASE_ANON_KEY}"
                )
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    // Ask PostgREST to send the created/updated row back, so
                    // inserts can report the server-assigned id. A custom
                    // [prefer] value (e.g. an upsert's resolution=
                    // merge-duplicates) overrides this default.
                    connection.setRequestProperty(
                        "Prefer", prefer ?: "return=representation"
                    )
                    connection.outputStream.use { out ->
                        out.write(body.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                if (code in 200..299) {
                    lastError = null
                } else {
                    lastError = "Supabase $method $path -> HTTP $code" +
                        serverReason(text)?.let { ": $it" }.orEmpty()
                }
                code to text
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            lastError = "Supabase $method $path failed: ${e.javaClass.simpleName}"
            -1 to null
        }
    }

    /**
     * The server's own explanation, from a PostgREST error body.
     *
     * A bare "HTTP 403" is not enough to fix anything: the useful half is
     * PostgREST's `message` ("new row violates row-level security policy for
     * table \"donations\"") or its `details`/`hint`. This is what makes a
     * screenshot worth sending. Nothing here is secret — it is a message the
     * server chose to send to the client.
     */
    private fun serverReason(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val text = try {
            val obj = JSONObject(body)
            obj.optString("message", "").takeIf { it.isNotBlank() }
                ?: obj.optString("msg", "").takeIf { it.isNotBlank() }
                ?: obj.optString("error", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            body.take(160).takeIf { it.isNotBlank() }
        } ?: return null
        val hint = try {
            JSONObject(body).optString("hint", "").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val out = text.trim().replace('\n', ' ')
        return if (hint != null && out.length + hint.length < 200) "$out (hint: $hint)" else out
    }

    // ------------------------------------------------------- connection check

    /** One line of the Settings -> Connection check screen. */
    data class Probe(
        val ok: Boolean,
        val detail: String,
        /**
         * Optional small print, shown in a smaller size after the detail.
         * Used by check 2 to keep "HTTP 401" out of the headline: a status
         * code proves the server answered, it is not a failure.
         */
        val note: String? = null
    )

    fun probeConfig(): Probe =
        if (isConfigured) Probe(true, "project URL and anon key are in this build")
        else Probe(false, "this build has no Supabase URL/key")

    /**
     * Is the server answering at all?
     *
     * Deliberately not `GET /rest/v1/` any more: Supabase restricts that
     * PostgREST root to the service_role key, which this app must never ship.
     * `/auth/v1/health` is part of the public Auth API and accepts the anon
     * key. Any HTTP response — 200, 401, even 500 — proves the server is
     * reachable; only a request that never completes (no network, DNS,
     * timeout) is a failure.
     */
    fun probeServer(): Probe = probeReachable(probeHealth())

    /**
     * Pure mapping from an HTTP status to the check-2 result. Kept separate
     * from the network call so it can be unit-tested: -1 means the request
     * never completed, any positive code is an answer from the server.
     *
     * The headline is the plain word "reachable"; the status code travels in
     * [Probe.note] as small print. A 401 here is a *successful* reachability
     * check (the endpoint asked for different credentials), so it must never
     * read like an error on screen.
     */
    fun probeReachable(code: Int): Probe =
        if (code > 0) Probe(true, "reachable", "HTTP $code")
        else Probe(false, lastError ?: "no answer from the server")

    /** GET /auth/v1/health with the anon key only. Returns the status or -1. */
    private fun probeHealth(): Int {
        if (!isConfigured) return -1
        return try {
            val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/health"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                // The anon key is the only key this app holds. It is public
                // by design; the service_role key must never be used or
                // stored here, and the health endpoint does not need it.
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer ${BuildConfig.SUPABASE_ANON_KEY}"
                )
                connection.setRequestProperty("Accept", "application/json")
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            lastError = "Supabase GET /auth/v1/health failed: ${e.javaClass.simpleName}"
            -1
        }
    }

    /** A real table read, with whatever session the app currently holds. */
    fun probeRead(): Probe {
        val rows = getArray("/stock?select=blood_group,units&limit=1")
            ?: return Probe(false, lastError ?: "no response")
        return Probe(true, "read ${rows.length()} stock row(s)")
    }

    /** The two SECURITY DEFINER helpers the update screens rely on. */
    fun probeHelperFunctions(): Probe {
        val (code, _) = request(
            "POST", "/rpc/request_contact",
            JSONObject().apply { put("p_request_id", -1L) }
        )
        return if (code in 200..299) Probe(true, "request_contact answered HTTP $code")
        else Probe(false, lastError ?: "HTTP $code")
    }

    private fun getArray(path: String): JSONArray? {
        val (code, body) = request("GET", path)
        if (code !in 200..299 || body.isNullOrBlank()) return null
        return try {
            JSONArray(body)
        } catch (_: Exception) {
            lastError = "Supabase response was not a JSON array"
            null
        }
    }

    private fun firstObject(path: String): JSONObject? {
        val array = getArray(path) ?: return null
        return if (array.length() > 0) array.optJSONObject(0) else null
    }

    /**
     * POST one row; returns the server-assigned id, or null on failure.
     *
     * `?select=id` keeps PostgREST's RETURNING clause to one column. The
     * default `return=representation` would send the whole row back, and a
     * `RETURNING *` needs SELECT on every column — including the phone columns
     * `authenticated` no longer has.
     */
    private fun insertRow(table: String, body: JSONObject): Long? {
        val (code, response) = request("POST", "/$table?select=id", body)
        if (code !in 200..299 || response.isNullOrBlank()) return null
        return try {
            val parsed = JSONArray(response)
            if (parsed.length() > 0) parsed.optJSONObject(0)?.optLong("id", -1)
                ?.takeIf { it > 0 } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * PATCH rows matched by [filter]; true only on a 2xx response.
     *
     * `return=minimal` because nothing reads the updated row: the default
     * `return=representation` would make PostgREST run `RETURNING *`, which
     * fails on tables where some columns (the phones) are not readable.
     */
    private fun patchRows(table: String, filter: String, body: JSONObject): Boolean {
        val (code, _) = request("PATCH", "/$table?$filter", body, "return=minimal")
        return code in 200..299
    }

    // ------------------------------------------------------------- parsing

    private fun donorFromJson(obj: JSONObject): Donor {
        val last = if (obj.isNull("last_donation_date")) null
            else obj.optString("last_donation_date", "").takeIf { it.isNotBlank() }
        return Donor(
            id = obj.optLong("id", 0),
            name = obj.optString("name", ""),
            age = obj.optInt("age", 0),
            gender = obj.optString("gender", ""),
            bloodGroup = obj.optString("blood_group", ""),
            phone = obj.optString("phone", ""),
            city = obj.optString("city", ""),
            weightKg = obj.optDouble("weight", 0.0),
            lastDonationDate = last,
            authId = if (obj.isNull("auth_id")) null else obj.optString("auth_id", ""),
            availableToDonate = obj.optBoolean("available_to_donate", false)
        )
    }

    private fun requestFromJson(obj: JSONObject): BloodRequest {
        // Supabase stores created_at as a timestamp; the app's model keeps
        // it as a plain string, so it is carried through unchanged.
        val created = if (obj.isNull("created_at")) "" else obj.optString("created_at", "")
        val requester = if (obj.isNull("requester_id")) null else obj.optString("requester_id", "")
        val fulfilledBy = if (obj.isNull("fulfilled_by")) null else obj.optString("fulfilled_by", "")
        val neededBy = if (obj.isNull("needed_by")) null else obj.optString("needed_by", "")
        return BloodRequest(
            id = obj.optLong("id", 0),
            patientName = obj.optString("patient_name", ""),
            hospital = obj.optString("hospital", ""),
            bloodGroup = obj.optString("blood_group", ""),
            unitsNeeded = obj.optInt("units_needed", 0),
            urgency = obj.optString("urgency", ""),
            contact = obj.optString("contact", ""),
            status = obj.optString("status", "Pending"),
            createdAt = created,
            requesterId = requester,
            city = if (obj.isNull("city")) "" else obj.optString("city", ""),
            fulfilledBy = fulfilledBy,
            neededBy = neededBy
        )
    }

    private fun donationFromJson(obj: JSONObject): Donation {
        val donorObj = obj.optJSONObject("donors")
        return Donation(
            id = obj.optLong("id", 0),
            donorId = obj.optLong("donor_id", 0),
            donorName = donorObj?.optString("name", "") ?: "",
            bloodGroup = donorObj?.optString("blood_group", "") ?: "",
            date = obj.optString("date", ""),
            units = obj.optInt("units", 0)
        )
    }

    // ------------------------------------------------------------- donors

    fun getDonors(): List<Donor>? {
        val array = getArray("/donors?select=$DONOR_COLUMNS&order=name.asc") ?: return null
        val out = ArrayList<Donor>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(donorFromJson(it)) }
        }
        return out
    }

    fun getDonor(id: Long): Donor? {
        val obj = firstObject("/donors?id=eq.$id&select=$DONOR_COLUMNS") ?: return null
        val donor = donorFromJson(obj)
        // The phone is not a readable column; ask for it separately. The
        // database answers for a manager and for the donor themselves, and
        // stays silent for everyone else.
        //
        // Only overwrite the phone when the RPC actually returns a value.
        // donorPhone returns null for a caller who may not see it, and a
        // null here must not overwrite a previously cached number with an
        // empty string (that would hide a number the user already knew).
        val phone = donorPhone(id)
        return if (phone != null) donor.copy(phone = phone) else donor
    }

    /**
     * The donor profile linked to an Auth user id, or null when there
     * is none. Used at Auth sign-in to find a returning donor's
     * profile (e.g. after reinstalling, when no local row exists yet).
     */
    fun getDonorByAuthId(authId: String): Donor? {
        val obj = firstObject("/donors?auth_id=eq.$authId&select=$DONOR_COLUMNS") ?: return null
        val donor = donorFromJson(obj)
        // Same rule as getDonor: only fill the phone when the RPC returns
        // one. A null means this caller may not see it, and must not
        // clobber a number the profile already had.
        val phone = donorPhone(donor.id)
        return if (phone != null) donor.copy(phone = phone) else donor
    }

    /**
     * The donor's phone number, or null when this account may not see it.
     *
     * [donors.phone] has no SELECT grant for `authenticated`; the database
     * function decides per caller (the donor themselves, or a manager). A
     * null therefore means "not yours to see", not "no number".
     */
    fun donorPhone(donorId: Long): String? {
        val body = JSONObject().apply { put("p_donor_id", donorId) }
        return scalarText("/rpc/donor_phone", body)
    }

    /**
     * The contact number typed on a request, or null when this account may not
     * see it (only the requester and a manager may).
     */
    fun requestContact(requestId: Long): String? {
        val body = JSONObject().apply { put("p_request_id", requestId) }
        return scalarText("/rpc/request_contact", body)
    }

    /** POSTs to an RPC that returns a single text value (or null). */
    private fun scalarText(path: String, body: JSONObject): String? {
        val (code, text) = request("POST", path, body)
        if (code !in 200..299 || text.isNullOrBlank()) return null
        val value = text.trim().removeSurrounding("\"")
        return if (value.isEmpty() || value == "null") null else value
    }

    /**
     * Inserts a donor online; returns the server-assigned id, or null.
     * [authId] links the profile to its Supabase Auth user (v1.4
     * registration); it is omitted for manager-added donors, who have
     * no Auth account of their own.
     */
    fun insertDonor(donor: Donor, authId: String? = null): Long? {
        val body = JSONObject().apply {
            put("name", donor.name)
            put("age", donor.age)
            put("gender", donor.gender)
            put("blood_group", donor.bloodGroup)
            put("phone", donor.phone)
            put("city", donor.city)
            put("weight", donor.weightKg)
            if (donor.lastDonationDate != null) put("last_donation_date", donor.lastDonationDate)
            else put("last_donation_date", JSONObject.NULL)
            if (authId != null) put("auth_id", authId)
            put("available_to_donate", donor.availableToDonate)
        }
        return insertRow("donors", body)
    }

    fun updateDonorLastDonation(id: Long, date: String): Boolean {
        val body = JSONObject().apply { put("last_donation_date", date) }
        return patchRows("donors", "id=eq.$id", body)
    }

    /** P2P: flips a donor's "available to donate" flag online. Offering to
     *  help is refused by RLS unless this is true. */
    fun updateDonorAvailability(id: Long, available: Boolean): Boolean {
        val body = JSONObject().apply {
            put("available_to_donate", available)
        }
        return patchRows("donors", "id=eq.$id", body)
    }

    fun deleteDonor(id: Long): Boolean {
        val (code, _) = request("DELETE", "/donors?id=eq.$id")
        return code in 200..299
    }

    // ------------------------------------------------------------- donations

    fun getDonationsForDonor(donorId: Long): List<Donation>? {
        val array = getArray(
            "/donations?donor_id=eq.$donorId" +
                "&select=id,donor_id,date,units,donors(name,blood_group)" +
                "&order=date.desc"
        ) ?: return null
        val out = ArrayList<Donation>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(donationFromJson(it)) }
        }
        return out
    }

    /** Inserts a donation online; returns the server-assigned id, or null. */
    fun insertDonation(donorId: Long, date: String, units: Int): Long? {
        val body = JSONObject().apply {
            put("donor_id", donorId)
            put("date", date)
            put("units", units)
        }
        return insertRow("donations", body)
    }

    // ------------------------------------------------------------- stock

    fun getStock(): Map<String, Int>? {
        val array = getArray("/stock?select=blood_group,units") ?: return null
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out[obj.optString("blood_group", "")] = obj.optInt("units", 0)
        }
        return out
    }

    fun updateStock(bloodGroup: String, units: Int): Boolean {
        val body = JSONObject().apply { put("units", units) }
        return patchRows("stock", "blood_group=eq.${encode(bloodGroup)}", body)
    }

    /**
     * Adds [delta] to a blood group's stock in one server-side statement
     * (`adjust_stock` in fix_stock_rpc.sql). Replaces the old
     * read-then-write, which lost a unit whenever two devices recorded a
     * donation at the same moment. Returns false if the function is missing
     * or the call failed, in which case the caller falls back to local only.
     */
    fun adjustStock(bloodGroup: String, delta: Int): Boolean {
        val body = JSONObject().apply {
            put("p_group", bloodGroup)
            put("p_delta", delta)
        }
        val (code, _) = request("POST", "/rpc/adjust_stock", body)
        return code in 200..299
    }

    // ------------------------------------------------------------- requests

    fun getRequests(): List<BloodRequest>? {
        val array = getArray("/requests?select=$REQUEST_COLUMNS&order=id.desc") ?: return null
        val out = ArrayList<BloodRequest>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(requestFromJson(it)) }
        }
        return out
    }

    fun getRequest(id: Long): BloodRequest? {
        val obj = firstObject("/requests?id=eq.$id&select=$REQUEST_COLUMNS") ?: return null
        val req = requestFromJson(obj)
        // Same rule as the donor phone: the column is not readable, so the
        // number is asked for separately and only the requester or a manager
        // gets an answer.
        //
        // Only overwrite the contact when the RPC returns a value. requestContact
        // returns null for a caller who may not see it, and a null here must not
        // overwrite a previously cached contact with an empty string.
        val contact = requestContact(id)
        return if (contact != null) req.copy(contact = contact) else req
    }

    /** Inserts a request online (created_at defaults to now on the server);
     *  returns the server-assigned id, or null. */
    fun insertRequest(req: BloodRequest): Long? {
        val body = JSONObject().apply {
            put("patient_name", req.patientName)
            put("hospital", req.hospital)
            put("blood_group", req.bloodGroup)
            put("units_needed", req.unitsNeeded)
            put("urgency", req.urgency)
            put("contact", req.contact)
            put("status", req.status)
            if (req.requesterId != null) put("requester_id", req.requesterId) else put("requester_id", JSONObject.NULL)
            put("city", req.city)
            if (req.fulfilledBy != null) put("fulfilled_by", req.fulfilledBy) else put("fulfilled_by", JSONObject.NULL)
            if (req.neededBy != null) put("needed_by", req.neededBy) else put("needed_by", JSONObject.NULL)
        }
        return insertRow("requests", body)
    }

    fun updateRequestStatus(id: Long, status: String): Boolean {
        val body = JSONObject().apply { put("status", status) }
        return patchRows("requests", "id=eq.$id", body)
    }

    fun updateRequestFulfilledBy(id: Long, fulfilledBy: String): Boolean {
        val body = JSONObject().apply { put("fulfilled_by", fulfilledBy) }
        return patchRows("requests", "id=eq.$id", body)
    }

    // ------------------------------------------------- Phase 2: status history

    /**
     * Timeline rows for one request. The server writes these from a trigger,
     * so the same history is visible on every device; RLS limits reads to the
     * requester and the manager.
     */
    fun getStatusHistory(requestId: Long): List<StatusEvent>? {
        val array = getArray(
            "/request_status_history?request_id=eq.$requestId" +
                "&select=id,request_id,status,changed_at,actor&order=changed_at.asc,id.asc"
        ) ?: return null
        val out = ArrayList<StatusEvent>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out.add(
                StatusEvent(
                    id = obj.optLong("id", 0),
                    requestId = obj.optLong("request_id", 0),
                    status = obj.optString("status", ""),
                    changedAt = if (obj.isNull("changed_at")) "" else obj.optString("changed_at", ""),
                    actor = if (obj.isNull("actor")) null else obj.optString("actor", "")
                )
            )
        }
        return out
    }

    /** Best-effort write from the phone; the server trigger is authoritative. */
    fun insertStatusHistory(requestId: Long, status: String, actor: String?): Long? {
        val body = JSONObject().apply {
            put("request_id", requestId)
            put("status", status)
            if (actor != null) put("actor", actor) else put("actor", JSONObject.NULL)
        }
        return insertRow("request_status_history", body)
    }

    // ------------------------------------------------------------- P2P: offers

    private fun offerFromJson(obj: JSONObject): Offer {
        val donorConfirmed = if (obj.isNull("donor_confirmed_at")) null else obj.optString("donor_confirmed_at", "")
        val requesterConfirmed = if (obj.isNull("requester_confirmed_at")) null else obj.optString("requester_confirmed_at", "")
        return Offer(
            id = obj.optLong("id", 0),
            requestId = obj.optLong("request_id", 0),
            donorId = obj.optLong("donor_id", 0),
            donorAuthId = if (obj.isNull("donor_auth_id")) null else obj.optString("donor_auth_id", ""),
            donorName = obj.optString("donor_name", ""),
            donorGroup = obj.optString("donor_group", ""),
            donorCity = obj.optString("donor_city", ""),
            status = obj.optString("status", "Offered"),
            donorConfirmedAt = donorConfirmed,
            requesterConfirmedAt = requesterConfirmed,
            createdAt = if (obj.isNull("created_at")) "" else obj.optString("created_at", "")
        )
    }

    fun getOffersForRequest(requestId: Long): List<Offer>? {
        val array = getArray(
            "/offers?request_id=eq.$requestId&select=*&order=id.asc"
        ) ?: return null
        val out = ArrayList<Offer>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(offerFromJson(it)) }
        }
        return out
    }

    /** Every offer, newest first. Manager only — RLS denies non-managers. */
    fun getAllOffers(): List<Offer>? {
        val array = getArray("/offers?select=*&order=id.desc") ?: return null
        val out = ArrayList<Offer>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(offerFromJson(it)) }
        }
        return out
    }

    /** Inserts an offer online; returns the server-assigned id, or null. */
    fun insertOffer(o: Offer): Long? {
        val body = JSONObject().apply {
            put("request_id", o.requestId)
            put("donor_id", o.donorId)
            if (o.donorAuthId != null) put("donor_auth_id", o.donorAuthId) else put("donor_auth_id", JSONObject.NULL)
            put("donor_name", o.donorName)
            put("donor_group", o.donorGroup)
            put("donor_city", o.donorCity)
            put("status", o.status)
            put("created_at", o.createdAt)
        }
        return insertRow("offers", body)
    }

    fun updateOfferStatus(offerId: Long, status: String): Boolean {
        val body = JSONObject().apply { put("status", status) }
        return patchRows("offers", "id=eq.$offerId", body)
    }

    fun updateOfferConfirmed(offerId: Long, donorAt: String?, requesterAt: String?): Boolean {
        val body = JSONObject().apply {
            if (donorAt != null) put("donor_confirmed_at", donorAt) else put("donor_confirmed_at", JSONObject.NULL)
            if (requesterAt != null) put("requester_confirmed_at", requesterAt) else put("requester_confirmed_at", JSONObject.NULL)
        }
        return patchRows("offers", "id=eq.$offerId", body)
    }

    // ------------------------------------------------------------- P2P: contact_details

    /** The private phone for a user id, or null. The anon key must not read this
     *  table — only a signed-in user (owner / manager / accepted party) can. */
    fun getContactDetails(userId: String): String? {
        val obj = firstObject("/contact_details?user_id=eq.$userId&select=phone") ?: return null
        return if (obj.isNull("phone")) null else obj.optString("phone", "")
    }

    fun upsertContactDetails(userId: String, phone: String): Boolean {
        // PostgREST does not upsert on a natural primary key without an
        // ON CONFLICT clause; PATCH-then-POST keeps it simple and idempotent.
        // This table is keyed by user_id and has NO `id` column, so insertRow
        // (which reads the server-assigned "id" back) must not be used here —
        // it would report failure on a perfectly successful insert.
        val existing = getContactDetails(userId)
        val body = JSONObject().apply { put("user_id", userId); put("phone", phone) }
        return if (existing != null) {
            patchRows("contact_details", "user_id=eq.${encode(userId)}", body)
        } else {
            val (code, _) = request("POST", "/contact_details", body)
            code in 200..299
        }
    }

    // ------------------------------------------------------------- P2P: reports

    // ------------------------------------------------------------- P2P: push (device tokens)

    /**
     * Upserts THIS device's FCM token for [userId] (POST .../device_tokens
     * ?on_conflict=token with resolution=merge-duplicates). One device is one
     * row: re-uploading the same token only refreshes updated_at. The row's
     * presence is what lets the Edge Function find the user who should receive
     * a push; RLS only allows the user to write their own.
     */
    fun upsertDeviceToken(userId: String, token: String): Boolean {
        val body = JSONObject().apply {
            put("user_id", userId)
            put("token", token)
            put("platform", "android")
        }
        val (code, _) = request(
            "POST",
            "/device_tokens?on_conflict=token",
            body,
            prefer = "resolution=merge-duplicates,return=minimal"
        )
        return code in 200..299
    }

    /**
     * Deletes token rows. With [token] set (a sign-out), only that device's
     * row is removed; with it null (an account being removed), every row of
     * [userId] is. True on 2xx.
     */
    fun deleteDeviceToken(userId: String, token: String?): Boolean {
        val filter = if (token == null) {
            "user_id=eq.${encode(userId)}"
        } else {
            "user_id=eq.${encode(userId)}&token=eq.${encode(token)}"
        }
        val (code, _) = request("DELETE", "/device_tokens?$filter")
        return code in 200..299
    }

    /**
     * Runs `complete_peer_donation` (see supabase_p2p_migration.sql §7): the
     * donation row, the donor's last-donation date, the offer status and the
     * request status all change in ONE server transaction, and stock is
     * deliberately left alone. Returns false if the function is missing, the
     * two sides have not both confirmed, or the call failed.
     */
    fun completePeerDonation(offerId: Long): Boolean {
        val body = JSONObject().apply { put("p_offer_id", offerId) }
        val (code, _) = request("POST", "/rpc/complete_peer_donation", body)
        return code in 200..299
    }

    fun insertReport(requestId: Long, reporterId: String?, reason: String): Long? {
        val body = JSONObject().apply {
            put("request_id", requestId)
            if (reporterId != null) put("reporter_id", reporterId) else put("reporter_id", JSONObject.NULL)
            put("reason", reason)
        }
        return insertRow("reports", body)
    }

    fun getReports(): List<Report>? {
        val array = getArray("/reports?select=*&order=id.desc") ?: return null
        val out = ArrayList<Report>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(reportFromJson(it)) }
        }
        return out
    }

    private fun reportFromJson(obj: JSONObject): Report {
        val resolved = if (obj.isNull("resolved_at")) null else obj.optString("resolved_at", "")
        return Report(
            id = obj.optLong("id", 0),
            requestId = obj.optLong("request_id", 0),
            reporterId = if (obj.isNull("reporter_id")) null else obj.optString("reporter_id", ""),
            reason = obj.optString("reason", ""),
            createdAt = if (obj.isNull("created_at")) "" else obj.optString("created_at", ""),
            resolvedAt = resolved
        )
    }

    // ------------------------------------------------------------- Phase 3: camps, slots, verification, movements

    /**
     * Phase 3 column lists. Every list names the exact server columns,
     * including `created_at` — the local tables predate that column and
     * the local models do not carry it (except [AppointmentBooking]),
     * so the value is selected for shape-parity and dropped on parse,
     * except where the model stores it. Local ordering never depends on
     * it: camps order by camp_date, slots by slot_time, movements by
     * date, verifications by id.
     */
    private const val CAMP_COLUMNS =
        "id,name,location,city,camp_date,organizer,created_at"
    private const val ATTENDANCE_COLUMNS =
        "id,camp_id,donor_id,attended_at,created_at"
    private const val SLOT_COLUMNS =
        "id,slot_time,capacity,booked,note,created_at"
    private const val BOOKING_COLUMNS =
        "id,slot_id,donor_id,created_at"
    private const val VERIFICATION_COLUMNS =
        "id,donor_id,status,verified_by,verified_at,note,created_at"
    private const val MOVEMENT_COLUMNS =
        "id,blood_group,delta,reason,date,created_at"

    private fun campFromJson(obj: JSONObject): Camp = Camp(
        id = obj.optLong("id", 0),
        name = obj.optString("name", ""),
        location = obj.optString("location", ""),
        city = obj.optString("city", ""),
        campDate = if (obj.isNull("camp_date")) "" else obj.optString("camp_date", ""),
        organizer = obj.optString("organizer", "")
    )

    private fun attendanceFromJson(obj: JSONObject): CampAttendance = CampAttendance(
        id = obj.optLong("id", 0),
        campId = obj.optLong("camp_id", 0),
        donorId = obj.optLong("donor_id", 0),
        attendedAt = if (obj.isNull("attended_at")) "" else obj.optString("attended_at", "")
    )

    private fun slotFromJson(obj: JSONObject): AppointmentSlot = AppointmentSlot(
        id = obj.optLong("id", 0),
        slotTime = if (obj.isNull("slot_time")) "" else obj.optString("slot_time", ""),
        capacity = obj.optInt("capacity", 0),
        booked = obj.optInt("booked", 0),
        note = obj.optString("note", "")
    )

    private fun bookingFromJson(obj: JSONObject): AppointmentBooking = AppointmentBooking(
        id = obj.optLong("id", 0),
        slotId = obj.optLong("slot_id", 0),
        donorId = obj.optLong("donor_id", 0),
        createdAt = if (obj.isNull("created_at")) "" else obj.optString("created_at", "")
    )

    private fun verificationFromJson(obj: JSONObject): Verification = Verification(
        id = obj.optLong("id", 0),
        donorId = obj.optLong("donor_id", 0),
        status = obj.optString("status", ""),
        verifiedBy = if (obj.isNull("verified_by")) null else obj.optString("verified_by", ""),
        verifiedAt = if (obj.isNull("verified_at")) null else obj.optString("verified_at", ""),
        note = obj.optString("note", "")
    )

    private fun movementFromJson(obj: JSONObject): Movement = Movement(
        id = obj.optLong("id", 0),
        bloodGroup = obj.optString("blood_group", ""),
        delta = obj.optInt("delta", 0),
        reason = obj.optString("reason", ""),
        date = if (obj.isNull("date")) "" else obj.optString("date", "")
    )

    /** All camps, earliest first. Reads are open to every authenticated user. */
    fun getCamps(): List<Camp>? {
        val array = getArray("/camps?select=$CAMP_COLUMNS&order=camp_date.asc,id.asc") ?: return null
        val out = ArrayList<Camp>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(campFromJson(it)) }
        }
        return out
    }

    /** One camp's check-ins, earliest first. */
    fun getCampAttendance(campId: Long): List<CampAttendance>? {
        val array = getArray(
            "/camp_attendance?camp_id=eq.$campId&select=$ATTENDANCE_COLUMNS&order=attended_at.asc,id.asc"
        ) ?: return null
        val out = ArrayList<CampAttendance>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(attendanceFromJson(it)) }
        }
        return out
    }

    /** All appointment slots, earliest first. */
    fun getSlots(): List<AppointmentSlot>? {
        val array = getArray("/appointment_slots?select=$SLOT_COLUMNS&order=slot_time.asc,id.asc") ?: return null
        val out = ArrayList<AppointmentSlot>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(slotFromJson(it)) }
        }
        return out
    }

    /** One slot's bookings, earliest first. */
    fun getBookings(slotId: Long): List<AppointmentBooking>? {
        val array = getArray(
            "/appointment_bookings?slot_id=eq.$slotId&select=$BOOKING_COLUMNS&order=created_at.asc,id.asc"
        ) ?: return null
        val out = ArrayList<AppointmentBooking>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(bookingFromJson(it)) }
        }
        return out
    }

    /** One donor's verification records, oldest first. */
    fun getVerifications(donorId: Long): List<Verification>? {
        val array = getArray(
            "/donor_verifications?donor_id=eq.$donorId&select=$VERIFICATION_COLUMNS&order=id.asc"
        ) ?: return null
        val out = ArrayList<Verification>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(verificationFromJson(it)) }
        }
        return out
    }

    /** All stock movements, oldest first. */
    fun getMovements(): List<Movement>? {
        val array = getArray("/stock_movements?select=$MOVEMENT_COLUMNS&order=date.asc,id.asc") ?: return null
        val out = ArrayList<Movement>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(movementFromJson(it)) }
        }
        return out
    }

    /** Inserts a camp online (manager-only by RLS); returns the server id, or null. */
    fun insertCamp(c: Camp): Long? {
        val body = JSONObject().apply {
            put("name", c.name)
            put("location", c.location)
            put("city", c.city)
            put("camp_date", c.campDate)
            put("organizer", c.organizer)
        }
        return insertRow("camps", body)
    }

    /** Inserts a slot online (manager-only by RLS); returns the server id, or null. */
    fun insertSlot(s: AppointmentSlot): Long? {
        val body = JSONObject().apply {
            put("slot_time", s.slotTime)
            put("capacity", s.capacity)
            put("note", s.note)
        }
        return insertRow("appointment_slots", body)
    }

    /**
     * Inserts a booking online; returns the server id, or null.
     *
     * The server trigger answers the capacity question: a full slot is
     * refused with "slot is full" and a second booking by the same donor
     * with a duplicate-key error. Both surface in [lastError] for the
     * caller ([SupabaseSync.bookSlotRemote]) to tell apart — a null here
     * never means "the seat is free, book it locally".
     */
    fun insertBooking(slotId: Long, donorId: Long): Long? {
        val body = JSONObject().apply {
            put("slot_id", slotId)
            put("donor_id", donorId)
        }
        return insertRow("appointment_bookings", body)
    }

    /** Inserts a check-in online; returns the server id, or null. */
    fun insertAttendance(campId: Long, donorId: Long, attendedAt: String?): Long? {
        val body = JSONObject().apply {
            put("camp_id", campId)
            put("donor_id", donorId)
            if (!attendedAt.isNullOrBlank()) put("attended_at", attendedAt)
        }
        return insertRow("camp_attendance", body)
    }

    /** Inserts a verification online (manager-only by RLS); returns the server id, or null. */
    fun insertVerification(v: Verification): Long? {
        val body = JSONObject().apply {
            put("donor_id", v.donorId)
            put("status", v.status)
            if (v.verifiedBy != null) put("verified_by", v.verifiedBy) else put("verified_by", JSONObject.NULL)
            if (v.verifiedAt != null) put("verified_at", v.verifiedAt) else put("verified_at", JSONObject.NULL)
            put("note", v.note)
        }
        return insertRow("donor_verifications", body)
    }

    /** Inserts a stock movement online (manager-only by RLS); returns the server id, or null. */
    fun insertMovement(m: Movement): Long? {
        val body = JSONObject().apply {
            put("blood_group", m.bloodGroup)
            put("delta", m.delta)
            put("reason", m.reason)
            put("date", m.date)
        }
        return insertRow("stock_movements", body)
    }
}
