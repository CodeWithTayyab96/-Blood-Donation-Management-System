package com.tayyab.blooddonation.util

import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.AppointmentBooking
import com.tayyab.blooddonation.model.AppointmentSlot
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Camp
import com.tayyab.blooddonation.model.CampAttendance
import com.tayyab.blooddonation.model.ContactDetail
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.model.Movement
import com.tayyab.blooddonation.model.Offer
import com.tayyab.blooddonation.model.Report
import com.tayyab.blooddonation.model.Verification
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
        for (d in donors) {
            // The list query carries no phone: that column is not readable.
            // Keep the number this phone already knows only when it is the
            // viewer's own row or the viewer is the manager — for everyone
            // else the cached copy is cleared, so a number learned under the
            // old permissive policies does not linger.
            val local = db.getDonor(d.id)
            val keep = local != null && local.phone.isNotBlank() &&
                (Session.isManager || (local.authId != null && local.authId == Session.authUserId))
            db.upsertDonor(if (keep) d.copy(phone = local!!.phone) else d)
        }
        return true
    }

    /** Downloads all requests and merges them into SQLite. False on failure. */
    // FRAGILE: this depends on the pulled request having an empty `contact`, because
    // `SupabaseClient.REQUEST_COLUMNS` does not select the contact column (it is not
    // readable by `authenticated`). If a future change adds `contact` to that list,
    // the `keep` rule below would silently stop clearing cached numbers a caller may
    // not be allowed to see — re-check this logic whenever `REQUEST_COLUMNS` changes.
    fun pullRequests(db: DbHelper): Boolean {
        val requests = SupabaseClient.getRequests() ?: return false
        for (r in requests) {
            // Same rule as the donor phone: keep a cached contact number only
            // for your own request, or when you are the manager.
            val local = db.getRequest(r.id)
            val keep = local != null && local.contact.isNotBlank() &&
                (Session.isManager || (local.requesterId != null && local.requesterId == Session.authUserId))
            db.upsertRequest(if (keep) r.copy(contact = local!!.contact) else r)
        }
        return true
    }

    /** Phase 2: downloads one request's timeline rows. False on failure. */
    fun pullStatusHistory(db: DbHelper, requestId: Long): Boolean {
        val events = SupabaseClient.getStatusHistory(requestId) ?: return false
        for (e in events) db.upsertStatusEvent(e)
        return true
    }

    // ------------------------------------------------------------- Phase 3: camps, slots, verification, movements

    /** Downloads all camps and merges them into SQLite. False on failure. */
    fun pullCamps(db: DbHelper): Boolean {
        val camps = SupabaseClient.getCamps() ?: return false
        for (c in camps) db.upsertCamp(c)
        return true
    }

    /** Downloads one camp's check-ins. False on failure. */
    fun pullCampAttendance(db: DbHelper, campId: Long): Boolean {
        val rows = SupabaseClient.getCampAttendance(campId) ?: return false
        for (a in rows) db.upsertCampAttendance(a)
        return true
    }

    /**
     * Downloads all slots and, for each one, its bookings. False when the
     * slot list itself could not be fetched; one slot's bookings failing
     * does not fail the whole pull, since the slot rows are what the
     * screen needs most.
     */
    fun pullSlots(db: DbHelper): Boolean {
        val slots = SupabaseClient.getSlots() ?: return false
        for (s in slots) {
            db.upsertSlot(s)
            val bookings = SupabaseClient.getBookings(s.id) ?: continue
            for (b in bookings) db.upsertBooking(b)
        }
        return true
    }

    /** Downloads one donor's verification records. False on failure. */
    fun pullVerifications(db: DbHelper, donorId: Long): Boolean {
        val records = SupabaseClient.getVerifications(donorId) ?: return false
        for (v in records) db.upsertVerification(v)
        return true
    }

    /** Downloads all stock movements. False on failure. */
    fun pullMovements(db: DbHelper): Boolean {
        val movements = SupabaseClient.getMovements() ?: return false
        for (m in movements) db.upsertMovement(m)
        return true
    }

    /**
     * Downloads everything Phase 3: camps, slots with their bookings,
     * stock movements, and — per camp and per locally known donor — the
     * check-ins and verification records. True if at least one landed.
     * Attendance and verifications are pulled per parent row because the
     * client reads them that way; a parent failing does not fail the
     * rest of the pull.
     */
    fun pullPhase3(db: DbHelper): Boolean {
        var ok = false
        if (pullCamps(db)) ok = true
        if (pullSlots(db)) ok = true
        if (pullMovements(db)) ok = true
        for (camp in db.getAllCamps()) {
            if (pullCampAttendance(db, camp.id)) ok = true
        }
        for (donor in db.getAllDonors()) {
            if (pullVerifications(db, donor.id)) ok = true
        }
        return ok
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

    /** Downloads donors, requests, stock and everything Phase 3. True if at least one landed. */
    fun pullAll(db: DbHelper): Boolean {
        var ok = false
        if (pullDonors(db)) ok = true
        if (pullRequests(db)) ok = true
        if (pullStock(db)) ok = true
        if (pullPhase3(db)) ok = true
        return ok
    }

    /** Downloads one request, plus the stock and donors its screen needs. */
    fun pullRequestDetail(db: DbHelper, requestId: Long): Boolean {
        val req = SupabaseClient.getRequest(requestId) ?: return false
        db.upsertRequest(req)
        pullStock(db)
        pullDonors(db)
        pullOffersForRequest(db, requestId)
        return true
    }

    // ------------------------------------------------------------- P2P: offers

    /** Downloads the offers on a request and merges them into SQLite. */
    fun pullOffersForRequest(db: DbHelper, requestId: Long): Boolean {
        val offers = SupabaseClient.getOffersForRequest(requestId) ?: return false
        for (o in offers) db.upsertOffer(o)
        return true
    }

    /** Creates an offer online; returns the server id, or null on failure. */
    fun addOfferRemote(o: Offer): Long? = SupabaseClient.insertOffer(o)

    fun updateOfferStatusRemote(offerId: Long, status: String): Boolean =
        SupabaseClient.updateOfferStatus(offerId, status)

    fun updateOfferConfirmedRemote(offerId: Long, donorAt: String?, requesterAt: String?): Boolean =
        SupabaseClient.updateOfferConfirmed(offerId, donorAt, requesterAt)

    // ------------------------------------------------------------- P2P: contact_details

    /** Downloads one user's private phone (only call when allowed by RLS). */
    fun pullContactDetails(db: DbHelper, userId: String): Boolean {
        val phone = SupabaseClient.getContactDetails(userId) ?: return false
        db.upsertContactDetails(ContactDetail(userId, phone))
        return true
    }

    fun upsertContactDetailsRemote(userId: String, phone: String): Boolean =
        SupabaseClient.upsertContactDetails(userId, phone)

    /** Fetches one user's phone on demand (returns null if RLS denies it). */
    fun getContactDetailsRemote(userId: String): String? =
        SupabaseClient.getContactDetails(userId)

    // ------------------------------------------------------------- P2P: reports

    fun insertReportRemote(requestId: Long, reporterId: String?, reason: String): Long? =
        SupabaseClient.insertReport(requestId, reporterId, reason)

    /** P2P: runs the atomic both-confirmed completion on the server. */
    fun completePeerDonationRemote(offerId: Long): Boolean =
        SupabaseClient.completePeerDonation(offerId)

    /** Downloads every offer (manager only; RLS denies non-managers). */
    fun pullAllOffers(db: DbHelper): Boolean {
        val offers = SupabaseClient.getAllOffers() ?: return false
        for (o in offers) db.upsertOffer(o)
        return true
    }

    /** Downloads all reports (manager only; RLS denies non-managers). */
    fun pullReports(db: DbHelper): Boolean {
        val reports = SupabaseClient.getReports() ?: return false
        for (r in reports) db.upsertReport(r)
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

    /** P2P: flips a donor's "available to donate" flag. The server refuses an
     *  offer from a donor who has not switched this on. */
    fun setAvailabilityRemote(donorId: Long, available: Boolean): Boolean =
        SupabaseClient.updateDonorAvailability(donorId, available)

    /** Deletes a donor online. False on failure (caller still deletes locally). */
    fun deleteDonorRemote(donorId: Long): Boolean = SupabaseClient.deleteDonor(donorId)

    // ------------------------------------------------------------- Phase 3: pushes

    /** Creates a camp online (manager-only by RLS); returns the server id, or null. */
    fun addCampRemote(camp: Camp): Long? = SupabaseClient.insertCamp(camp)

    /** Creates a slot online (manager-only by RLS); returns the server id, or null. */
    fun addSlotRemote(slot: AppointmentSlot): Long? = SupabaseClient.insertSlot(slot)

    /**
     * Whether this device may write a row that names [donorId]. The RLS
     * rule is the same on both tables: the donor row's `auth_id` must be
     * the caller's, unless the caller is a manager. Kept pure (session
     * passed in) so the rule itself is unit-testable.
     */
    fun mayWriteForDonor(donorId: Long, ownDonorId: Long?, isManager: Boolean): Boolean =
        isManager || ownDonorId == donorId

    /** What an online booking attempt came back with. */
    sealed class BookingOutcome {
        /** The booking reached the server; [bookingId] is its server id. */
        data class Booked(val bookingId: Long) : BookingOutcome()

        /**
         * The server refused the booking: the slot is full. The caller
         * must NOT fall back to a local-only booking — that would claim
         * a seat the server denied.
         */
        object Full : BookingOutcome()

        /** The server already holds a booking by this donor on this slot. */
        object Duplicate : BookingOutcome()

        /** The server could not be asked, or refused for another reason.
         *  [detail] carries the server's own words. The caller may fall
         *  back to a local-only booking here, but must say so. */
        data class Failed(val detail: String) : BookingOutcome()
    }

    /**
     * Turns a failed booking insert into an outcome, from the server's
     * own error text ([SupabaseClient.lastError]). Pure, so the mapping
     * is unit-testable without a network call.
     */
    fun mapBookingFailure(detail: String?): BookingOutcome {
        val text = detail.orEmpty()
        return when {
            text.contains("slot is full", ignoreCase = true) -> BookingOutcome.Full
            text.contains("duplicate key", ignoreCase = true) -> BookingOutcome.Duplicate
            else -> BookingOutcome.Failed(
                detail?.takeIf { it.isNotBlank() } ?: "the server did not accept the booking"
            )
        }
    }

    /**
     * Books a slot online. [Booked] carries the server id for the caller
     * to store locally; [Full] and [Duplicate] are distinct refusals the
     * caller must not paper over with a local-only booking.
     */
    fun bookSlotRemote(slotId: Long, donorId: Long): BookingOutcome {
        if (!mayWriteForDonor(donorId, Session.donorId, Session.isManager)) {
            return BookingOutcome.Failed("this booking is for another donor")
        }
        val id = SupabaseClient.insertBooking(slotId, donorId)
        if (id != null && id > 0) return BookingOutcome.Booked(id)
        return mapBookingFailure(SupabaseClient.lastError)
    }

    /**
     * Records a check-in online; returns the server id, or null when the
     * write did not land (unreachable, refused, or for another donor —
     * the guard above). The caller treats null as "local only" and says
     * so; from the check-in screen the guard cannot trip, since the
     * button only exists for the signed-in donor's own id.
     */
    fun recordAttendanceRemote(campId: Long, donorId: Long, attendedAt: String): Long? {
        if (!mayWriteForDonor(donorId, Session.donorId, Session.isManager)) return null
        return SupabaseClient.insertAttendance(campId, donorId, attendedAt)
    }

    /** Inserts a verification online (manager-only by RLS); returns the server id, or null. */
    fun insertVerificationRemote(v: Verification): Long? = SupabaseClient.insertVerification(v)

    /** Inserts a stock movement online (manager-only by RLS); returns the server id, or null. */
    fun insertMovementRemote(m: Movement): Long? = SupabaseClient.insertMovement(m)

    /**
     * Outcome of an online donation write.
     *
     * [stockSynced] is separate from [donationId] on purpose: the donation row
     * and the stock increment are two server calls, and the second one can fail
     * on its own. Reporting that as a plain success is what let the local and
     * online stock counts drift apart without anyone noticing.
     */
    data class DonationWrite(
        val donationId: Long,
        val stockSynced: Boolean
    )

    /**
     * Why a donation did not reach the server.
     *
     * "Saved on this phone (not synced)" used to be the only failure message,
     * which is useless when the real cause is a policy refusing the insert —
     * exactly the kind of thing that has to be visible in a screenshot. So the
     * failing step and the server's own words travel with the failure.
     */
    data class DonationFailure(
        /** Which step failed — "the donation row", "the stock count". */
        val stage: String,
        /** Status plus the server's reason, ready to show or to screenshot. */
        val detail: String
    )

    /**
     * Records a donation online: donation row, donor's last-donation date, and
     * stock +units.
     *
     * This is the **manager** path: only a manager may move stock, so only a
     * manager-recorded donation calls `adjust_stock` and only a manager may
     * record a donation into the blood bank's stock at all. A donor's own peer
     * donation is recorded through [completePeerDonationRemote] (`complete_peer_donation`),
     * which writes the donation row and the donor's last-donation date but
     * deliberately leaves stock alone — a peer donation never entered the bank.
     *
     * Returns null only when the donation row itself did not reach Supabase, in
     * which case the caller records it locally under a fresh id. When the row
     * did land, the id is always returned — even if the stock update failed —
     * so the caller stores it under that id and never inserts a second row.
     * [DonationWrite.stockSynced] tells the caller whether the stock half
     * actually succeeded.
     */
    sealed class DonationOutcome {
        /**
         * The donation row reached the server. [stockSynced] false means the
         * stock half failed on its own; [stockDetail] then holds why, so the
         * message can say more than "the stock did not update".
         */
        data class Recorded(
            val donationId: Long,
            val stockSynced: Boolean,
            val stockDetail: String? = null,
            /**
             * The server id of the stock-movement row this donation wrote
             * (Phase 3), or null when that insert did not land. The caller
             * stores the local movement under this id, so a later pull
             * updates it instead of adding a second one; null means the
             * local movement keeps its own id and stays phone-only.
             */
            val movementId: Long? = null
        ) : DonationOutcome()

        /** Nothing was recorded online. [failure] says which step and why. */
        data class Rejected(val failure: DonationFailure) : DonationOutcome()
    }

    /**
     * Manager-only donor recording: inserts the donation row, updates the donor's
     * last-donation date, and increments stock. The stock increment is a separate
     * server call and can fail on its own, which is why the outcome reports it
     * explicitly rather than swallowing it as a plain success.
     *
     * Hardening note: `adjustStock` stays manager-only by intent — a donor-recorded
     * (peer) donation must not touch stock, and the caller (`DonationOutcome` / the
     * manager recording path) is the only place that calls it for a stock change. The
     * peer path is `completePeerDonationRemote`, which never increments stock.
     */
    fun recordDonationRemote(
        donor: Donor,
        units: Int,
        date: LocalDate = LocalDate.now()
    ): DonationOutcome {
        val dateStr = date.toString()
        val donationId = SupabaseClient.insertDonation(donor.id, dateStr, units)
        if (donationId == null || donationId <= 0) {
            return DonationOutcome.Rejected(
                DonationFailure(
                    stage = "the donation row",
                    detail = SupabaseClient.lastError
                        ?: "the server did not accept the donation (no reason given)"
                )
            )
        }
        SupabaseClient.updateDonorLastDonation(donor.id, dateStr)
        // A single server-side statement, so concurrent donations cannot lose a
        // unit — but it can still fail, and that must not be swallowed.
        val stockSynced = SupabaseClient.adjustStock(donor.bloodGroup, units)
        // Phase 3: the same donation as a dated stock movement, so the
        // forecast's burn rate is fed by every device. Best-effort: when
        // it does not land, the local movement keeps its own id and stays
        // phone-only, so a later pull can never double-count it.
        val movementId = insertMovementRemote(
            Movement(bloodGroup = donor.bloodGroup, delta = units, reason = "donation", date = dateStr)
        )
        return DonationOutcome.Recorded(
            donationId = donationId,
            stockSynced = stockSynced,
            stockDetail = if (stockSynced) null
            else SupabaseClient.lastError ?: "the server refused the stock change",
            movementId = movementId
        )
    }

    /** Creates a request online; returns the server id, or null on failure. */
    fun addRequestRemote(req: BloodRequest): Long? = SupabaseClient.insertRequest(req)

    /** P2P: cancels a request online. Cancelling is an UPDATE to status, not a
     *  delete — the row stays so the manager can still audit it. */
    fun cancelRequestRemote(id: Long): Boolean =
        SupabaseClient.updateRequestStatus(id, "Cancelled")

    sealed class FulfilResult {
        /**
         * The request was fulfilled from stock online. [movementId] is the
         * server id of the stock-movement row this fulfilment wrote
         * (Phase 3), or null when that insert did not land — the caller
         * then stores the local movement under its own id, phone-only.
         */
        data class Success(val movementId: Long? = null) : FulfilResult()

        /**
         * The online stock was read and really is short. [available] is what
         * the server holds right now, so the caller can say so instead of
         * showing a bare "not enough" next to a different local number.
         */
        data class Insufficient(val available: Int) : FulfilResult()

        /**
         * The online stock could not be read at all — offline, or the request
         * was rejected. Nothing is known about the server's count, so the
         * caller must not report it as zero or as a shortage.
         */
        object StockUnknown : FulfilResult()

        /**
         * The stock was readable but a later step failed (the decrement or the
         * status update). Different again: the count was known, the write was
         * not accepted.
         */
        object Error : FulfilResult()
    }

    /**
     * Fulfils a request online: checks stock, subtracts the units, and marks the
     * request Fulfilled.
     *
     * The three failure modes are deliberately distinct. [Insufficient] means
     * the server really is short, and the caller must NOT fulfil locally either,
     * or the two copies would disagree. [StockUnknown] means the server could
     * not be asked — the caller falls back to the local copy and says so rather
     * than claiming a shortage. [Error] means the count was known but the write
     * was rejected.
     *
     * The check is still a read followed by a write, so two devices fulfilling
     * the same group at the same moment can both pass it. The subtraction itself
     * is atomic and clamped at zero by `adjust_stock`, so stock can no longer go
     * negative — it just may under-count in that race.
     */
    fun fulfilRequestRemote(req: BloodRequest): FulfilResult {
        val stock = SupabaseClient.getStock()
        // A missing key counts as unknown, not as zero: the stock table is
        // seeded with every group, so an absent row means the read told us
        // nothing rather than that the group is empty.
        when (val decision = StockCheck.decide(stock?.get(req.bloodGroup), req.unitsNeeded)) {
            StockCheck.Decision.Unknown -> return FulfilResult.StockUnknown
            is StockCheck.Decision.Short -> return FulfilResult.Insufficient(decision.available)
            StockCheck.Decision.Sufficient -> Unit
        }
        if (!SupabaseClient.adjustStock(req.bloodGroup, -req.unitsNeeded)) {
            return FulfilResult.Error
        }
        if (!SupabaseClient.updateRequestStatus(req.id, "Fulfilled")) {
            return FulfilResult.Error
        }
        // Phase 3: the same fulfilment as a dated stock movement, so the
        // forecast sees it on every device. Best-effort, as in
        // recordDonationRemote: a missed insert leaves the local
        // movement local-only, never duplicated.
        val movementId = insertMovementRemote(
            Movement(
                bloodGroup = req.bloodGroup, delta = -req.unitsNeeded,
                reason = "fulfilment", date = LocalDate.now().toString()
            )
        )
        return FulfilResult.Success(movementId)
    }
}
