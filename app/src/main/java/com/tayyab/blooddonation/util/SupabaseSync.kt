package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Donor
import java.time.LocalDate

/**
 * Bridges the local SQLite copy ([DbHelper]) and Supabase ([SupabaseClient]).
 *
 * Every function here blocks on network I/O and must be called on a
 * background thread — activities do exactly that (Thread + runOnUiThread,
 * the same pattern MainActivity already uses for AppUpdater).
 *
 * Design, in one paragraph: the online copy is the source of truth
 * whenever it can be reached, and the local copy is the offline
 * fallback. Reads pull the online rows down and merge them into SQLite
 * (upsert by id, so local-only rows survive). Writes go online first
 * and then into SQLite with the server-assigned id, so both copies stay
 * in step; if the online write fails, the write still lands in SQLite
 * and the user is told it was saved on this phone only. Accounts are
 * no longer local-only: v1.4 signs users in through Supabase Auth
 * (see SupabaseAuth), while the local users table stays as the offline
 * mirror and the offline-fallback login.
 *
 * Known limits, stated honestly (also in SUPABASE_SETUP.md):
 * - A multi-step online write (donation = donation row + donor date +
 *   stock, fulfil = stock + status) is not one transaction: if the
 *   network drops halfway, part of it may be online and part not. The
 *   next pull merges what is online, and the local copy still has the
 *   full change, so nothing the user saw disappear — but the two copies
 *   can briefly disagree until the next successful write.
 * - A row created while offline gets a local-only id. If a different
 *   row later arrives online with that same id, the upsert will replace
 *   it. In normal online use this never happens, because online writes
 *   always take the server id first.
 */
object SupabaseSync {

    /** True when a project URL and anon key were baked into this build. */
    val isOnline: Boolean get() = SupabaseClient.isConfigured

    // ------------------------------------------------------------- pulls

    /** Downloads all donors and merges them into SQLite. False on failure. */
    fun pullDonors(db: DbHelper): Boolean {
        val donors = SupabaseClient.getDonors() ?: return false
        for (d in donors) db.upsertDonor(d)
        return true
    }

    /** Downloads all requests and merges them into SQLite. False on failure. */
    fun pullRequests(db: DbHelper): Boolean {
        val requests = SupabaseClient.getRequests() ?: return false
        for (r in requests) db.upsertRequest(r)
        return true
    }

    /** Downloads stock levels and writes them into SQLite. False on failure. */
    fun pullStock(db: DbHelper): Boolean {
        val stock = SupabaseClient.getStock() ?: return false
        for ((group, units) in stock) db.setStock(group, units)
        return true
    }

    /** Downloads one donor and their donation history. False if the donor
     *  itself could not be fetched (a history failure alone still counts
     *  as success, since the donor row is what the screen needs most). */
    fun pullDonorDetail(db: DbHelper, donorId: Long): Boolean {
        val donor = SupabaseClient.getDonor(donorId) ?: return false
        db.upsertDonor(donor)
        val history = SupabaseClient.getDonationsForDonor(donorId)
        if (history != null) {
            for (d in history) {
                // The donation rows embed their donor (name, group), but
                // if that embed ever comes back empty, fill it from the
                // donor row we already have — otherwise the history list
                // would show blank names.
                val complete = if (d.donorName.isBlank()) {
                    d.copy(donorName = donor.name, bloodGroup = donor.bloodGroup)
                } else {
                    d
                }
                db.upsertDonation(complete)
            }
        }
        return true
    }

    /** Downloads donors, requests and stock. True if at least one landed. */
    fun pullAll(db: DbHelper): Boolean {
        var ok = false
        if (pullDonors(db)) ok = true
        if (pullRequests(db)) ok = true
        if (pullStock(db)) ok = true
        return ok
    }

    /** Downloads one request, plus the stock and donors its screen needs. */
    fun pullRequestDetail(db: DbHelper, requestId: Long): Boolean {
        val req = SupabaseClient.getRequest(requestId) ?: return false
        db.upsertRequest(req)
        pullStock(db)
        pullDonors(db)
        return true
    }

    // ------------------------------------------------------------- pushes

    /**
     * Creates a donor online; returns the server id, or null on failure.
     * [authId] links the profile to its Supabase Auth user (v1.4
     * registration); manager-added donors pass nothing here.
     */
    fun addDonorRemote(donor: Donor, authId: String? = null): Long? =
        SupabaseClient.insertDonor(donor, authId)

    /** Deletes a donor online. False on failure (caller still deletes locally). */
    fun deleteDonorRemote(donorId: Long): Boolean = SupabaseClient.deleteDonor(donorId)

    /**
     * Records a donation online: donation row, donor's last-donation date, and
     * stock +units.
     *
     * Returns the server-assigned donation id when the donation row itself
     * reached Supabase, or null when it did not. The caller stores the same row
     * locally under that id ([DbHelper.recordDonationWithId]), so the history
     * list does not end up holding a local row AND a server row for the same
     * donation.
     *
     * The last-donation date and the stock increment are attempted after the
     * row is inserted and are best-effort: if either fails the id is still
     * returned, because the donation really does exist online and the next pull
     * reconciles the date and the stock count. Reporting failure here would make
     * the caller insert a second local row, which is the worse outcome.
     *
     * The stock increment is a single `adjust_stock` call, so two devices
     * recording at the same moment cannot lose a unit between them.
     */
    fun recordDonationRemote(
        donor: Donor,
        units: Int,
        date: LocalDate = LocalDate.now()
    ): Long? {
        val dateStr = date.toString()
        val donationId = SupabaseClient.insertDonation(donor.id, dateStr, units) ?: return null
        if (donationId <= 0) return null
        SupabaseClient.updateDonorLastDonation(donor.id, dateStr)
        SupabaseClient.adjustStock(donor.bloodGroup, units)
        return donationId
    }

    /** Creates a request online; returns the server id, or null on failure. */
    fun addRequestRemote(req: BloodRequest): Long? = SupabaseClient.insertRequest(req)

    sealed class FulfilResult {
        object Success : FulfilResult()
        object Insufficient : FulfilResult()
        object Error : FulfilResult()
    }

    /**
     * Fulfils a request online: checks stock, subtracts the units, and
     * marks the request Fulfilled. [Insufficient] means the online stock
     * really is short (caller must NOT fulfil locally either, or the two
     * copies would disagree). [Error] means the online state is unknown
     * (network/permission failure) — the caller falls back to local-only.
     *
     * The check is still a read followed by a write, so two devices fulfilling
     * the same group at the same moment can both pass it. The subtraction
     * itself is atomic and clamped at zero by `adjust_stock`, so stock can no
     * longer go negative — it just may under-count in that race.
     */
    fun fulfilRequestRemote(req: BloodRequest): FulfilResult {
        val stock = SupabaseClient.getStock() ?: return FulfilResult.Error
        val available = stock[req.bloodGroup] ?: 0
        if (available < req.unitsNeeded) return FulfilResult.Insufficient
        if (!SupabaseClient.adjustStock(req.bloodGroup, -req.unitsNeeded)) {
            return FulfilResult.Error
        }
        if (!SupabaseClient.updateRequestStatus(req.id, "Fulfilled")) {
            return FulfilResult.Error
        }
        return FulfilResult.Success
    }
}
