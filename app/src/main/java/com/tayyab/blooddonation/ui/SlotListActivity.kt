package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.AppointmentBooking
import com.tayyab.blooddonation.model.AppointmentSlot
import com.tayyab.blooddonation.util.AppointmentRules
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
import java.time.LocalDate

/**
 * Phase 3: bookable appointment slots. Donors book (the DB guard in
 * [DbHelper.bookSlot] enforces capacity and de-dup); managers create slots.
 */
class SlotListActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var adapter: SlotAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slot_list)
        title = getString(R.string.title_slots)
        db = DbHelper(this)

        val rv = findViewById<RecyclerView>(R.id.rvSlots)
        rv.setHasFixedSize(true)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = SlotAdapter { slot -> bookSlot(slot) }
        rv.adapter = adapter

        Session.load(this)
        val fab = findViewById<FloatingActionButton>(R.id.fabAddSlot)
        fab.visibility = if (Session.isManager) View.VISIBLE else View.GONE
        fab.setOnClickListener { startActivity(Intent(this, AddSlotActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val slots = db.getAllSlots()
        val booked = Session.donorId?.let { db.getBookedSlotIds(it) } ?: emptySet()
        adapter.submit(slots, booked)
        findViewById<View>(R.id.tvEmpty).visibility =
            if (slots.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Books a slot online first. A Full refusal from the server is never
     * papered over with a local-only booking — that would claim a seat
     * the server denied. Only when the server could not be asked at all
     * does the booking land on this phone only, and the toast says so.
     */
    private fun bookSlot(slot: AppointmentSlot) {
        val donorId = Session.donorId ?: return
        if (!SupabaseSync.isOnline) {
            bookLocally(slot, donorId)
            return
        }
        Background.run {
            when (val outcome = SupabaseSync.bookSlotRemote(slot.id, donorId)) {
                is SupabaseSync.BookingOutcome.Booked -> {
                    db.storeRemoteBooking(
                        AppointmentBooking(
                            id = outcome.bookingId, slotId = slot.id,
                            donorId = donorId, createdAt = LocalDate.now().toString()
                        )
                    )
                    runOnUiThread {
                        android.widget.Toast.makeText(this, R.string.slot_book_ok, android.widget.Toast.LENGTH_LONG).show()
                        render()
                    }
                }
                SupabaseSync.BookingOutcome.Full -> runOnUiThread {
                    android.widget.Toast.makeText(this, R.string.slot_book_full, android.widget.Toast.LENGTH_LONG).show()
                    render()
                }
                SupabaseSync.BookingOutcome.Duplicate -> runOnUiThread {
                    android.widget.Toast.makeText(this, R.string.slot_book_dup, android.widget.Toast.LENGTH_LONG).show()
                    render()
                }
                is SupabaseSync.BookingOutcome.Failed -> runOnUiThread {
                    // The server could not be asked: book on this phone
                    // only. (A Full refusal never reaches here.)
                    bookLocally(slot, donorId)
                }
            }
        }
    }

    /** Local-only booking, used offline and when the server could not be
     *  asked. A success here is always labelled as phone-only. */
    private fun bookLocally(slot: AppointmentSlot, donorId: Long) {
        val result = db.bookSlot(slot.id, donorId, LocalDate.now().toString())
        val msg = when (result) {
            AppointmentRules.BookResult.Ok -> R.string.slot_book_phone_only
            AppointmentRules.BookResult.Full -> R.string.slot_book_full
            AppointmentRules.BookResult.Duplicate -> R.string.slot_book_dup
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        render()
    }
}
