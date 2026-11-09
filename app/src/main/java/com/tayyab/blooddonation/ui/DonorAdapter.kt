package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.util.BloodCompatibility

class DonorAdapter(
    private val onClick: (Donor) -> Unit
) : ListAdapter<Donor, DonorAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Donor>() {
            override fun areItemsTheSame(old: Donor, new: Donor) = old.id == new.id
            override fun areContentsTheSame(old: Donor, new: Donor) = old == new
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.tvGroupBadge)
        val name: TextView = v.findViewById(R.id.tvDonorName)
        val meta: TextView = v.findViewById(R.id.tvDonorMeta)
        val status: TextView = v.findViewById(R.id.tvDonorStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_donor, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = getItem(pos)
        h.badge.text = d.bloodGroup
        h.name.text = d.name
        h.meta.text = "${d.city} • ${d.age} yrs"
        val (eligible, reason) = BloodCompatibility.eligibility(d)
        h.status.text = if (eligible) "✓ Eligible" else "✗ $reason"
        h.status.setTextColor(
            ContextCompat.getColor(
                h.itemView.context,
                if (eligible) R.color.status_green else R.color.status_amber_dark
            )
        )
        h.status.setBackgroundResource(
            if (eligible) R.drawable.bg_pill_green else R.drawable.bg_pill_amber
        )
        h.itemView.setOnClickListener { onClick(d) }
    }

    fun submit(list: List<Donor>) {
        submitList(list)
    }
}
