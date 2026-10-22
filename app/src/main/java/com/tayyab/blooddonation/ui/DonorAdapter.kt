package com.tayyab.blooddonation.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.util.BloodCompatibility

class DonorAdapter(
    private var donors: List<Donor>,
    private val onClick: (Donor) -> Unit
) : RecyclerView.Adapter<DonorAdapter.VH>() {

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

    override fun getItemCount() = donors.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = donors[pos]
        h.badge.text = d.bloodGroup
        h.name.text = d.name
        h.meta.text = "${d.city} • ${d.age} yrs • ${d.phone}"
        val (eligible, reason) = BloodCompatibility.eligibility(d)
        h.status.text = if (eligible) "✓ Eligible" else "✗ $reason"
        h.status.setTextColor(Color.parseColor(if (eligible) "#2E7D32" else "#E65100"))
        h.itemView.setOnClickListener { onClick(d) }
    }

    fun submit(list: List<Donor>) {
        donors = list
        notifyDataSetChanged()
    }
}
