package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.ContactDetail
import com.tayyab.blooddonation.model.Offer
import com.tayyab.blooddonation.model.Report
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.ContactActions
import com.tayyab.blooddonation.util.GeoQuery
import com.tayyab.blooddonation.util.P2pRules
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.ShareText
import com.tayyab.blooddonation.util.SupabaseClient
import com.tayyab.blooddonation.util.SupabaseSync
import com.tayyab.blooddonation.ui.UISync
import java.time.LocalDate

/**
 * P2P: one request, three points of view.
 *
 * - **Donor** — "I can donate" creates an offer. Once the requester accepts,
 *   the requester's phone appears here (and only then).
 * - **Requester** — sees the offers (first name, group, city), accepts one or
 *   declines them, and sees the donor's phone after accepting.
 * - **Manager** — keeps "Fulfill from Stock" and sees everything else.
 *
 * A request with a null `requester_id` (the manager's own, and every row
 * created before P2P) has no peer side at all, so those sections stay hidden.
 */
class RequestDetailActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private var requestId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_detail)
        title = "Request Detail"
        db = DbHelper(this)
        requestId = intent.getLongExtra("requestId", -1)
        Session.load(this)
        // Fulfilling a request moves stock, which is a manager action. Hiding
        // the button is a UI guard only — supabase_role_policies.sql is what
        // actually enforces it on the server.
        if (!Session.isManager) {
            findViewById<Button>(R.id.btnFulfill).visibility = View.GONE
        }

        val btnFulfill = findViewById<Button>(R.id.btnFulfill)
        btnFulfill.isEnabled = false
        btnFulfill.setOnClickListener {
            val req = db.getRequest(requestId) ?: return@setOnClickListener
            if (req.status == "Fulfilled") {
                Toast.makeText(this, "Already fulfilled.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                if (SupabaseSync.isOnline) {
                // Online stock is the source of truth when reachable:
                // a short online stock means "not enough", full stop.
                // An online error falls back to the old local behaviour.
                sync("Fulfilling request…") {
                    when (val result = SupabaseSync.fulfilRequestRemote(req)) {
                        is SupabaseSync.FulfilResult.Success -> {
                            db.fulfillRequest(req, result.movementId)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(this, "Request fulfilled from stock.", Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        }
                        is SupabaseSync.FulfilResult.Insufficient -> runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            // Name the server's number: the count on screen is
                            // the local copy, and the two can disagree.
                            Toast.makeText(
                                this,
                                "Not enough ${req.bloodGroup} units — the server has " +
                                    "${result.available}. Refreshing from the server.",
                                Toast.LENGTH_LONG
                            ).show()
                            refresh()
                        }
                        is SupabaseSync.FulfilResult.StockUnknown -> {
                            // The server's count is unknown. Fall back to the
                            // local copy, but never claim the server is short.
                            val ok = db.fulfillRequest(req)
                            val local = db.stockLevels()[req.bloodGroup] ?: 0
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(
                                    this,
                                    if (ok) {
                                        "Request fulfilled on this phone (not synced to Supabase)."
                                    } else {
                                        "Could not check stock on the server. This phone has " +
                                            "$local unit(s) of ${req.bloodGroup}."
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                                refresh()
                            }
                        }
                        is SupabaseSync.FulfilResult.Error -> {
                            // The count was readable but the write was rejected —
                            // a different situation again, and worth saying so.
                            val ok = db.fulfillRequest(req)
                            val local = db.stockLevels()[req.bloodGroup] ?: 0
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                Toast.makeText(
                                    this,
                                    if (ok) {
                                        "Request fulfilled on this phone (not synced to Supabase)."
                                    } else {
                                        "The server rejected the stock update. This phone has " +
                                            "$local unit(s) of ${req.bloodGroup}."
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                                refresh()
                            }
                        }
                    }
                }
            } else {
                val ok = db.fulfillRequest(req)
                Toast.makeText(
                    this,
                    if (ok) "Request fulfilled from stock." else "Not enough ${req.bloodGroup} units in stock.",
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        } finally {
            btnFulfill.isEnabled = true
        }
    }

        findViewById<Button>(R.id.btnReport).setOnClickListener { showReportDialog() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareRequest() }
        findViewById<Button>(R.id.btnNavigate).setOnClickListener { navigateToHospital() }
        findViewById<Button>(R.id.btnTimeline).setOnClickListener {
            startActivity(
                Intent(this, RequestTimelineActivity::class.java)
                    .putExtra("requestId", requestId)
            )
        }
        findViewById<Button>(R.id.btnFulfill).isEnabled = true
    }

    /** geo: intent — no Maps SDK, no API key, no location permission. */
    private fun navigateToHospital() {
        val req = db.getRequest(requestId) ?: return
        val uri = GeoQuery.uri(req.hospital, req.city)
        if (uri == null) {
            Toast.makeText(this, R.string.msg_no_maps_query, Toast.LENGTH_SHORT).show()
            return
        }
        ContactActions.openMap(this, uri)
    }

    /**
     * ACTION_SEND with the facts a helper needs to decide whether they can
     * donate. Every label is a string resource; [ShareText] only decides the
     * order and drops the lines this account may not see.
     */
    private fun shareRequest() {
        val req = db.getRequest(requestId) ?: return
        val cityLine = req.city.takeIf { it.isNotBlank() }?.let {
            getString(R.string.share_request_city, it)
        }
        // A blank contact means RLS did not reveal it to this account. Never
        // put "Contact: " or a placeholder into a message that leaves the phone.
        val contactLine = req.contact.takeIf { it.isNotBlank() }?.let {
            getString(R.string.share_request_contact, it)
        }
        ContactActions.share(
            this,
            ShareText.requestText(
                heading = getString(R.string.share_request_heading),
                group = getString(R.string.share_request_group, req.bloodGroup),
                units = getString(R.string.share_request_units, req.unitsNeeded),
                hospital = getString(R.string.share_request_hospital, req.hospital),
                city = cityLine,
                contact = contactLine,
                footer = getString(R.string.share_request_footer)
            )
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            try {
                sync("Updating request…") {
                    SupabaseSync.pullRequestDetail(db, requestId)
                    UISync.safePost(this) { renderFromLocal() }
                }
            } finally {
                findViewById<Button>(R.id.btnFulfill).isEnabled = true
            }
        }
    }

    /**
     * A request created on this phone stores `createdAt` as a plain date
     * ("2026-10-31"); the same request pulled from Supabase comes back as a
     * full timestamptz. Showing only the date part keeps the screen identical
     * before and after a sync.
     */
    private fun displayDate(raw: String): String =
        if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') raw.substring(0, 10) else raw

    private fun renderFromLocal() {
        val req = db.getRequest(requestId) ?: run { finish(); return }
        val city = req.city.takeIf { it.isNotBlank() }?.let { "City: $it\n" } ?: ""
        val neededBy = req.neededBy?.let { "Needed by: ${displayDate(it)}\n" } ?: ""
        findViewById<TextView>(R.id.tvReqDetailTitle).text =
            "${req.patientName}  (${req.bloodGroup})"
        findViewById<TextView>(R.id.tvReqDetailInfo).text =
            "Hospital: ${req.hospital}\n" +
            city +
            "Units needed: ${req.unitsNeeded}\n" +
            "Urgency: ${req.urgency}\n" +
            // Same rule: the requester and the manager see the number,
            // everyone else is told when it becomes visible.
            "Contact: ${req.contact.ifBlank { "shared after you accept an offer" }}\n" +
            "Requested on: ${displayDate(req.createdAt)}\n" +
            neededBy +
            "Status: ${req.status}\n" +
            "In stock (${req.bloodGroup}): ${db.stockLevels()[req.bloodGroup] ?: 0} unit(s)"

        // Re-bound on every render: a sync can arrive without a contact this
        // account is allowed to see, in which case both buttons disappear.
        ContactActions.bind(
            this,
            findViewById<View>(R.id.contactActions),
            findViewById<Button>(R.id.btnCall),
            findViewById<Button>(R.id.btnWhatsApp),
            req.contact
        )

        val compatibleGroups = BloodCompatibility.donorGroupsFor(req.bloodGroup)
        findViewById<TextView>(R.id.tvCompatNote).text =
            "A ${req.bloodGroup} patient can receive from: ${compatibleGroups.joinToString(", ")}"

        val donors = db.getAllDonors().filter { d ->
            BloodCompatibility.isCompatible(d.bloodGroup, req.bloodGroup) &&
                BloodCompatibility.eligibility(d).first
        }
        val rv = findViewById<RecyclerView>(R.id.rvCompatDonors)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = DonorAdapter(donors) { donor ->
            val i = Intent(this, DonorDetailActivity::class.java)
            i.putExtra("donorId", donor.id)
            startActivity(i)
        }

        renderPeerSections(req)
    }

    // ----------------------------------------------------------------- P2P

    /** True when this is a peer request (it has an owner) rather than a
     *  manager-raised or pre-P2P row. */
    private fun isPeerRequest(requesterId: String?) = !requesterId.isNullOrBlank()

    private fun renderPeerSections(req: BloodRequest) {
        val myId = Session.authUserId
        val isRequester = myId != null && req.requesterId == myId
        val peer = isPeerRequest(req.requesterId)

        // A peer request can be reported; a manager's own row cannot.
        findViewById<Button>(R.id.btnReport).visibility =
            if (peer && !Session.isManager) View.VISIBLE else View.GONE

        if (!peer) {
            hideAllPeerSections()
            return
        }

        if (isRequester) renderRequesterSide(req)
        else renderDonorSide(req)
    }

    private fun hideAllPeerSections() {
        findViewById<Button>(R.id.btnOffer).visibility = View.GONE
        findViewById<TextView>(R.id.tvOfferState).visibility = View.GONE
        findViewById<TextView>(R.id.tvOffersHeader).visibility = View.GONE
        findViewById<LinearLayout>(R.id.offersContainer).visibility = View.GONE
        findViewById<Button>(R.id.btnComplete).visibility = View.GONE
    }

    // ------------------------------------------------------------ donor side

    private fun renderDonorSide(req: BloodRequest) {
        val donorId = Session.donorId
        val btnOffer = findViewById<Button>(R.id.btnOffer)
        val tvState = findViewById<TextView>(R.id.tvOfferState)
        val header = findViewById<TextView>(R.id.tvOffersHeader)
        val container = findViewById<LinearLayout>(R.id.offersContainer)
        header.visibility = View.GONE
        container.visibility = View.GONE
        container.removeAllViews()

        // Without a donor profile there is nothing to offer: this user cannot
        // be matched to a blood group and has no eligibility to check.
        if (donorId == null) {
            btnOffer.visibility = View.GONE
            tvState.visibility = View.VISIBLE
            tvState.text = "Only users with a donor profile can offer to help."
            findViewById<Button>(R.id.btnComplete).visibility = View.GONE
            return
        }

        val existing = db.getOffer(req.id, donorId)
        if (existing == null) {
            // Everyone sees every request now, but only a matching group can
            // offer to give blood directly. A donor whose group cannot give
            // can still help by sharing the request with someone who can,
            // so the Offer button stays hidden for them instead of failing
            // on tap. offerHelp keeps its own compatibility check as well.
            val donor = db.getDonor(donorId)
            if (donor != null && !BloodCompatibility.isCompatible(donor.bloodGroup, req.bloodGroup)) {
                btnOffer.visibility = View.GONE
                tvState.visibility = View.VISIBLE
                tvState.text = "Your group (${donor.bloodGroup}) can't give to ${req.bloodGroup}, " +
                    "but you can share this request with someone who can."
                findViewById<Button>(R.id.btnComplete).visibility = View.GONE
                return
            }
            btnOffer.visibility = View.VISIBLE
            btnOffer.setOnClickListener { offerHelp(req) }
            btnOffer.isEnabled = true
            tvState.visibility = View.GONE
            findViewById<Button>(R.id.btnComplete).visibility = View.GONE
            return
        }

        btnOffer.visibility = View.GONE
        tvState.visibility = View.VISIBLE
        tvState.text = "Your offer: ${existing.status}"
        setStateColor(tvState, existing.status)

        // Once accepted, the requester's phone is revealed — and only then.
        // The database is what enforces this (contact_details policy); this
        // screen just stops asking before that point.
        if (existing.status == "Accepted") {
            val tvContact = TextView(this)
            tvContact.textSize = 14f
            container.visibility = View.VISIBLE
            container.addView(tvContact)
            loadContact(req.requesterId, tvContact, "Requester contact")
            setupCompleteButton(req, existing, asDonor = true)
        } else {
            findViewById<Button>(R.id.btnComplete).visibility = View.GONE
        }

        // Defensive clear so a refresh does not re-append donation-completed/
        // contact views if a previous render left static children behind.
        if (container.childCount > 0 && container.visibility != View.VISIBLE) {
            container.removeAllViews()
        }
    }

    private fun offerHelp(req: BloodRequest) {
        val donorId = Session.donorId ?: return
        val donor = db.getDonor(donorId) ?: return
        val (eligible, reason) = BloodCompatibility.eligibility(donor)
        if (!eligible) {
            Toast.makeText(this, "You cannot offer: $reason", Toast.LENGTH_LONG).show()
            return
        }
        if (!donor.availableToDonate) {
            Toast.makeText(
                this,
                "Switch on \"I'm available to donate\" in your profile first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!BloodCompatibility.isCompatible(donor.bloodGroup, req.bloodGroup)) {
            Toast.makeText(
                this,
                "Your group (${donor.bloodGroup}) cannot give to ${req.bloodGroup}.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val offer = Offer(
            requestId = req.id,
            donorId = donorId,
            donorAuthId = donor.authId ?: Session.authUserId,
            // First name only: the requester does not need a full name to
            // decide, and the phone stays hidden until they accept.
            donorName = donor.name.substringBefore(" "),
            donorGroup = donor.bloodGroup,
            donorCity = donor.city,
            status = "Offered",
            createdAt = LocalDate.now().toString()
        )

        // An offer is online-only: one that only exists on this phone is never
        // seen by the requester, and the donor would sit waiting for an answer
        // that cannot come. Nothing is written if the server refuses.
        if (!SupabaseSync.isOnline) {
            Toast.makeText(this, "Offering to donate needs an internet connection.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val btnOffer = findViewById<Button>(R.id.btnOffer)
        if (isFinishing || isDestroyed) return
        btnOffer.isEnabled = false
        try {
            sync("Sending offer…") {
                val remoteId = SupabaseSync.addOfferRemote(offer)
                val error = SupabaseClient.lastError
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (remoteId == null) {
                        Toast.makeText(
                            this,
                            "The server did not accept the offer" +
                                (error?.let { ": $it" } ?: "."),
                            Toast.LENGTH_LONG
                        ).show()
                        return@runOnUiThread
                    }
                    db.upsertOffer(offer.copy(id = remoteId))
                    Toast.makeText(this, "Offer sent", Toast.LENGTH_SHORT).show()
                    renderFromLocal()
                }
            }
        } finally {
            btnOffer.isEnabled = true
        }
    }


    // -------------------------------------------------------- requester side

    private fun renderRequesterSide(req: BloodRequest) {
        findViewById<Button>(R.id.btnOffer).visibility = View.GONE
        findViewById<TextView>(R.id.tvOfferState).visibility = View.GONE
        val header = findViewById<TextView>(R.id.tvOffersHeader)
        val container = findViewById<LinearLayout>(R.id.offersContainer)
        header.visibility = View.VISIBLE
        container.visibility = View.VISIBLE
        container.removeAllViews()
        val offers = db.getOffersForRequest(req.id)
        if (offers.isEmpty()) {
            val tv = TextView(this)
            tv.text = "No offers yet. Every donor can see this request."
            tv.textSize = 14f
            container.addView(tv)
            findViewById<Button>(R.id.btnComplete).visibility = View.GONE
            return
        }

        var accepted: Offer? = null
        for (o in offers) {
            val tv = TextView(this)
            tv.text = "${o.donorName} • ${o.donorGroup} • ${o.donorCity}\nStatus: ${o.status}"
            tv.textSize = 14f
            tv.setPadding(0, 8, 0, 4)
            container.addView(tv)
            setStateColor(tv, o.status)

            if (o.status == "Offered") {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val accept = Button(this)
                accept.text = getString(R.string.label_accept)
                val decline = Button(this)
                decline.text = getString(R.string.label_decline)
                accept.setOnClickListener { acceptOffer(req, o, accept, decline) }
                decline.setOnClickListener { declineOffer(o, decline) }
                row.addView(accept)
                row.addView(decline)
                container.addView(row)
            }
            if (o.status == "Accepted" || o.status == "Completed") {
                accepted = o
                val tvContact = TextView(this)
                tvContact.textSize = 14f
                container.addView(tvContact)
                loadContact(o.donorAuthId, tvContact, "Donor contact")
            }
        }

        // Completion is offered for the accepted offer only.
        val acc = accepted
        if (acc != null && acc.status == "Accepted") {
            setupCompleteButton(req, acc, asDonor = false)
        } else {
            findViewById<Button>(R.id.btnComplete).visibility = View.GONE
        }
    }

    private fun acceptOffer(req: BloodRequest, offer: Offer, btnAccept: Button, btnDecline: Button) {
        if (isFinishing || isDestroyed) return
        btnAccept.isEnabled = false
        btnDecline.isEnabled = false
        try {
            // Only one accepted offer per request. The server enforces this with a
            // partial unique index; this is the friendly local check.
            val alreadyAccepted = db.getOffersForRequest(req.id).any { it.status == "Accepted" }
            if (alreadyAccepted) {
                Toast.makeText(this, "You have already accepted an offer.", Toast.LENGTH_SHORT).show()
                return
            }
            db.setOfferStatus(offer.id, "Accepted")
            db.setRequestStatus(req.id, "Matched")
            renderFromLocal()
            if (SupabaseSync.isOnline) {
                sync("Accepting offer…") {
                    SupabaseSync.updateOfferStatusRemote(offer.id, "Accepted")
                    pushRequestStatusOnline(req.id, "Matched")
                }
            }
        } finally {
            btnAccept.isEnabled = true
            btnDecline.isEnabled = true
        }
    }

    private fun declineOffer(offer: Offer, btnDecline: Button) {
        if (isFinishing || isDestroyed) return
        btnDecline.isEnabled = false
        try {
            db.setOfferStatus(offer.id, "Declined")
            renderFromLocal()
            if (SupabaseSync.isOnline) {
                sync("Declining offer…") {
                    SupabaseSync.updateOfferStatusRemote(offer.id, "Declined")
                }
            }
        } finally {
            btnDecline.isEnabled = true
        }
    }

    // --------------------------------------------------------- contact reveal

    /**
     * Loads one user's phone. The database only returns it once the viewer is
     * allowed to see it (owner, manager, or the other party on an accepted
     * offer), so a null here means "not shared yet" rather than "no phone".
     */
    private fun loadContact(userId: String?, tv: TextView, label: String) {
        if (userId.isNullOrBlank()) {
            tv.text = "$label: not available"
            return
        }
        val local = db.getContactDetails(userId)
        if (local != null) {
            tv.text = "$label: ${local.phone}"
            return
        }
        tv.text = "$label: loading…"
        if (!SupabaseSync.isOnline) {
            tv.text = "$label: not available offline"
            return
        }
        sync("Loading contact…") {
            val phone = SupabaseSync.getContactDetailsRemote(userId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (phone.isNullOrBlank()) {
                    tv.text = "$label: not shared yet"
                } else {
                    db.upsertContactDetails(ContactDetail(userId, phone))
                    tv.text = "$label: $phone"
                }
            }
        }
    }

    // ------------------------------------------------------------- completion

    private fun setupCompleteButton(
        req: BloodRequest,
        offer: Offer,
        asDonor: Boolean
    ) {
        val btn = findViewById<Button>(R.id.btnComplete)
        val mineDone = if (asDonor) offer.donorConfirmedAt != null
                       else offer.requesterConfirmedAt != null
        btn.visibility = if (mineDone) View.GONE else View.VISIBLE
        if (mineDone) return
        btn.text = getString(R.string.label_donation_completed)
        btn.setOnClickListener { confirmCompletion(req, offer, asDonor) }
        btn.isEnabled = true
        btn.isClickable = true
        btn.backgroundTintList = null
        btn.alpha = 1f
    }

    private fun confirmCompletion(
        req: BloodRequest,
        offer: Offer,
        asDonor: Boolean
    ) {
        val btnComplete = findViewById<Button>(R.id.btnComplete)
        if (isFinishing || isDestroyed) return
        btnComplete.isEnabled = false
        try {
            val today = LocalDate.now().toString()
            val donorAt = if (asDonor) today else offer.donorConfirmedAt
            val requesterAt = if (asDonor) offer.requesterConfirmedAt else today

            if (asDonor) db.setOfferDonorConfirmed(offer.id, today)
            else db.setOfferRequesterConfirmed(offer.id, today)

            val both = P2pRules.bothConfirmed(donorAt, requesterAt)

            if (SupabaseSync.isOnline) {
                sync("Completing donation…") {
                    SupabaseSync.updateOfferConfirmedRemote(offer.id, donorAt, requesterAt)
                    val done = if (both) SupabaseSync.completePeerDonationRemote(offer.id) else false
                    if (done || both) completeLocally(req, offer)
                    UISync.safePost(this) {
                        Toast.makeText(
                            this,
                            if (both) "Donation recorded. Thank you!" else
                                "Marked done on your side. Waiting for the other side.",
                            Toast.LENGTH_LONG
                        ).show()
                        renderFromLocal()
                    }
                }
            } else {
                // Offline: the local copy still records both halves, but nothing
                // reaches the server yet — the next online confirm retries it.
                if (both) completeLocally(req, offer)
                Toast.makeText(
                    this,
                    if (both) "Donation recorded on this phone (not synced)." else
                        "Marked done on your side (offline).",
                    Toast.LENGTH_LONG
                ).show()
                renderFromLocal()
            }
        } finally {
            btnComplete.isEnabled = true
        }
    }

    /** Local half of completion. Deliberately does NOT add units to stock —
     *  a peer donation never enters the blood bank's inventory. */
    private fun completeLocally(req: BloodRequest, offer: Offer) {
        db.setOfferStatus(offer.id, "Completed")
        db.setRequestStatus(req.id, "Fulfilled")
        db.setRequestFulfilledBy(req.id, "donor")
        db.recordPeerDonation(offer.donorId, LocalDate.now())
    }

    // ---------------------------------------------------------------- reports

    private fun showReportDialog() {
        val reasons = arrayOf("Spam or advertising", "Fake request", "Abusive content", "Other")
        AlertDialog.Builder(this)
            .setTitle("Report this request")
            .setItems(reasons) { _, which -> submitReport(reasons[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitReport(reason: String) {
        val reporter = Session.authUserId
        db.insertReport(
            Report(
                requestId = requestId,
                reporterId = reporter,
                reason = reason,
                createdAt = LocalDate.now().toString()
            )
        )
        if (SupabaseSync.isOnline) {
            sync("Sending report…") {
                val ok = SupabaseSync.insertReportRemote(requestId, reporter, reason) != null
                UISync.safePost(this) {
                    Toast.makeText(
                        this,
                        if (ok) "Report sent to the manager." else
                            "Report saved on this phone (not synced).",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            Toast.makeText(this, "Report saved on this phone (offline).", Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------------- network helper

    /** Run a network action on [Background] with the in-flight sync indicator, hidden in a `finally`. */
    private fun sync(label: String, work: () -> Unit) {
        UISync.withSync(this, findViewById<ProgressBar>(R.id.progressSync), findViewById<TextView>(R.id.syncLabel), label, work)
    }

    // ----------------------------------------------------------------- helpers

    private fun setStateColor(tv: TextView, status: String) {
        tv.setTextColor(
            getColor(
                when (status) {
                    "Accepted", "Completed" -> R.color.status_ok
                    "Declined", "Expired" -> R.color.text_secondary
                    else -> R.color.status_warn
                }
            )
        )
    }

    /** Pushes a request's status online (Matched after an accept). */
    private fun pushRequestStatusOnline(requestId: Long, status: String) {
        SupabaseClient.updateRequestStatus(requestId, status)
    }
}
