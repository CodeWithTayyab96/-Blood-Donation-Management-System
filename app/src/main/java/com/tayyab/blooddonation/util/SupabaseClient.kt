package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Donation
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.model.Offer
import com.tayyab.blooddonation.model.Report
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
    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, String?> {
        val (code, text) = requestOnce(method, path, body)
        if (code == 401) {
            val refresh = Session.refreshToken
            if (!refresh.isNullOrBlank()) {
                val refreshed = SupabaseAuth.refreshSession(refresh)
                if (refreshed != null) {
                    Session.updateTokens(refreshed.accessToken, refreshed.refreshToken)
                    return requestOnce(method, path, body)
                }
                if (SupabaseAuth.lastError != "No internet connection") {
                    Session.clearTokens()
                }
            }
        }
        return code to text
    }

    /** A single HTTP attempt, with no retry. See [request]. */
    private fun requestOnce(method: String, path: String, body: JSONObject? = null): Pair<Int, String?> {
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
                    // inserts can report the server-assigned id.
                    connection.setRequestProperty("Prefer", "return=representation")
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
                    lastError = "Supabase $method $path -> HTTP $code"
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

    /** POST one row; returns the server-assigned id, or null on failure. */
    private fun insertRow(table: String, body: JSONObject): Long? {
        val (code, response) = request("POST", "/$table", body)
        if (code !in 200..299 || response.isNullOrBlank()) return null
        return try {
            val parsed = JSONArray(response)
            if (parsed.length() > 0) parsed.optJSONObject(0)?.optLong("id", -1)
                ?.takeIf { it > 0 } else null
        } catch (_: Exception) {
            null
        }
    }

    /** PATCH rows matched by [filter]; true only on a 2xx response. */
    private fun patchRows(table: String, filter: String, body: JSONObject): Boolean {
        val (code, _) = request("PATCH", "/$table?$filter", body)
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
            availableToDonate = obj.optInt("available_to_donate", 0) != 0
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
        val array = getArray("/donors?select=*&order=name.asc") ?: return null
        val out = ArrayList<Donor>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(donorFromJson(it)) }
        }
        return out
    }

    fun getDonor(id: Long): Donor? {
        val obj = firstObject("/donors?id=eq.$id&select=*") ?: return null
        return donorFromJson(obj)
    }

    /**
     * The donor profile linked to an Auth user id, or null when there
     * is none. Used at Auth sign-in to find a returning donor's
     * profile (e.g. after reinstalling, when no local row exists yet).
     */
    fun getDonorByAuthId(authId: String): Donor? {
        val obj = firstObject("/donors?auth_id=eq.$authId&select=*") ?: return null
        return donorFromJson(obj)
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
            put("available_to_donate", if (donor.availableToDonate) 1 else 0)
        }
        return insertRow("donors", body)
    }

    fun updateDonorLastDonation(id: Long, date: String): Boolean {
        val body = JSONObject().apply { put("last_donation_date", date) }
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
        val array = getArray("/requests?select=*&order=id.desc") ?: return null
        val out = ArrayList<BloodRequest>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(requestFromJson(it)) }
        }
        return out
    }

    fun getRequest(id: Long): BloodRequest? {
        val obj = firstObject("/requests?id=eq.$id&select=*") ?: return null
        return requestFromJson(obj)
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
}
