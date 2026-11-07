package com.tayyab.blooddonation.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R

class StockAdapter(private val rows: List<Pair<String, Int>>) :
    RecyclerView.Adapter<StockAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val group: TextView = v.findViewById(R.id.tvStockGroup)
        val units: TextView = v.findViewById(R.id.tvStockUnits)
        val level: TextView = v.findViewById(R.id.tvStockLevel)
        val bar: ProgressBar = v.findViewById(R.id.pbStockLevel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stock, parent, false)
        return VH(v)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (g, u) = rows[pos]
        h.group.text = g
        h.units.text = u.toString()
        // Warm Clinical Sanguine status colours; bar scale is 0-10 units.
        val (label, colorRes, pill) = when {
            u == 0 -> Triple("OUT OF STOCK", R.color.status_red, R.drawable.bg_pill_red)
            u <= 2 -> Triple("LOW", R.color.status_amber, R.drawable.bg_pill_amber)
            else -> Triple("AVAILABLE", R.color.status_green, R.drawable.bg_pill_green)
        }
        h.level.text = label
        h.level.setTextColor(ContextCompat.getColor(h.itemView.context, colorRes))
        h.level.setBackgroundResource(pill)
        h.bar.max = 100
        h.bar.progress = minOf(u, 10) * 10
        h.bar.progressTintList =
            ColorStateList.valueOf(ContextCompat.getColor(h.itemView.context, colorRes))
    }
}
