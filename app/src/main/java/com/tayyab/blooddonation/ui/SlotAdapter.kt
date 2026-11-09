package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.AppointmentSlot
import com.tayyab.blooddonation.util.AppointmentRules

/**
 * Phase 3: appointment slot rows. The book button reflects the same guard the
 * database enforces ([AppointmentRules]): disabled (and labelled) when the slot is
 * full or this donor has already booked it, so the UI can never promise what the
 * DB would reject.
 */
class SlotAdapter(
    private val onBook: (AppointmentSlot) -> Unit
) : ListAdapter<AppointmentSlot, SlotAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppointmentSlot>() {
            override fun areItemsTheSame(old: AppointmentSlot, new: AppointmentSlot) = old.id == new.id
            override fun areContentsTheSame(old: AppointmentSlot, new: AppointmentSlot) = old == new
        }
    }

    private var bookedIds: Set<Long> = emptySet()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.tvSlotTime)
        val capacity: TextView = v.findViewById(R.id.tvSlotCapacity)
        val note: TextView = v.findViewById(R.id.tvSlotNote)
        val book: Button = v.findViewById(R.id.btnSlotBook)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_slot, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = getItem(pos)
        val ctx = h.itemView.context
        h.time.text = s.slotTime
        h.capacity.text = ctx.getString(R.string.slot_capacity_label, s.booked, s.capacity)
        h.note.text = s.note
        h.note.visibility = if (s.note.isBlank()) View.GONE else View.VISIBLE

        val full = AppointmentRules.isFull(s.capacity, s.booked)
        val already = s.id in bookedIds
        h.book.isEnabled = !(full || already)
        h.book.text = when {
            full -> ctx.getString(R.string.slot_full)
            already -> ctx.getString(R.string.slot_booked)
            else -> ctx.getString(R.string.slot_book)
        }
        h.book.setOnClickListener { if (h.book.isEnabled) onBook(s) }
    }

    fun submit(list: List<AppointmentSlot>, booked: Set<Long> = bookedIds) {
        bookedIds = booked
        submitList(list)
    }
}
