package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stock, parent, false)
        return VH(v)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val (g, u) = rows[pos]
        h.group.text = g
        h.units.text = "$u unit(s) available"
        val (label, colorRes) = when {
            u == 0 -> "OUT OF STOCK" to R.color.status_bad
            u <= 2 -> "LOW" to R.color.status_warn
            else -> "AVAILABLE" to R.color.status_ok
        }
        h.level.text = label
        h.level.setTextColor(ContextCompat.getColor(h.itemView.context, colorRes))
    }
}
