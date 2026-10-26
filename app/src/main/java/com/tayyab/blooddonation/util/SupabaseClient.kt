package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.BuildConfig
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Donation
import com.tayyab.blooddonation.model.Donor
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
 * donors, donations, requests and stock. No Supabase Auth is used.
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
     */
    private fun request(method: String, path: String, body: JSONObject? = null): Pair<Int, String?> {
        if (!isConfigured) return -1 to null
        return try {
            val connection = URL(baseUrl() + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                connection.setRequestProperty(
                    "Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}"
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
                if (code !in 200..299) {
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
            lastDonationDate = last
        )
    }

    private fun requestFromJson(obj: JSONObject): BloodRequest {
        // Supabase stores created_at as a timestamp; the app's model keeps
        // it as a plain string, so it is carried through unchanged.
        val created = if (obj.isNull("created_at")) "" else obj.optString("created_at", "")
        return BloodRequest(
            id = obj.optLong("id", 0),
            patientName = obj.optString("patient_name", ""),
            hospital = obj.optString("hospital", ""),
            bloodGroup = obj.optString("blood_group", ""),
            unitsNeeded = obj.optInt("units_needed", 0),
            urgency = obj.optString("urgency", ""),
            contact = obj.optString("contact", ""),
            status = obj.optString("status", "Pending"),
            createdAt = created
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

    /** Inserts a donor online; returns the server-assigned id, or null. */
    fun insertDonor(donor: Donor): Long? {
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
        }
        return insertRow("requests", body)
    }

    fun updateRequestStatus(id: Long, status: String): Boolean {
        val body = JSONObject().apply { put("status", status) }
        return patchRows("requests", "id=eq.$id", body)
    }
}
