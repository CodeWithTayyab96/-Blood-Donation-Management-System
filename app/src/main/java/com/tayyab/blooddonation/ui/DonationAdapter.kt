package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.Donation

class DonationAdapter(private val items: List<Donation>) :
    RecyclerView.Adapter<DonationAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: TextView = v.findViewById(R.id.tvDonationRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_donation, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = items[pos]
        h.row.text = "🩸 ${d.date} — ${d.units} unit(s) of ${d.bloodGroup}"
    }
}
