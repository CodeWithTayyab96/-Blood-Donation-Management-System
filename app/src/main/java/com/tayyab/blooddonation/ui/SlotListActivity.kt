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
import com.tayyab.blooddonation.model.AppointmentSlot
import com.tayyab.blooddonation.util.AppointmentRules
import com.tayyab.blooddonation.util.Session
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
        rv.layoutManager = LinearLayoutManager(this)
        adapter = SlotAdapter(emptyList()) { slot -> bookSlot(slot) }
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
        val booked = Session.donorId?.let { id ->
            slots.filter { db.hasBooking(it.id, id) }.map { it.id }.toSet()
        } ?: emptySet()
        adapter.submit(slots, booked)
        findViewById<View>(R.id.tvEmpty).visibility =
            if (slots.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bookSlot(slot: AppointmentSlot) {
        val donorId = Session.donorId ?: return
        val result = db.bookSlot(slot.id, donorId, LocalDate.now().toString())
        val msg = when (result) {
            AppointmentRules.BookResult.Ok -> R.string.slot_book_ok
            AppointmentRules.BookResult.Full -> R.string.slot_book_full
            AppointmentRules.BookResult.Duplicate -> R.string.slot_book_dup
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        render()
    }
}
