package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.Camp

/** A camp list row: either a section header ("Upcoming" / "Past") or a camp. */
sealed class CampRow {
    data class Header(val text: String) : CampRow()
    data class Item(val camp: Camp) : CampRow()
}

class CampAdapter(
    private val onClick: (Camp) -> Unit
) : ListAdapter<CampRow, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_CAMP = 1

        private val DIFF = object : DiffUtil.ItemCallback<CampRow>() {
            override fun areItemsTheSame(old: CampRow, new: CampRow) = when {
                old is CampRow.Header && new is CampRow.Header -> old.text == new.text
                old is CampRow.Item && new is CampRow.Item -> old.camp.id == new.camp.id
                else -> false
            }
            override fun areContentsTheSame(old: CampRow, new: CampRow) = old == new
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tvHeader)
    }

    class CampVH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvCampName)
        val meta: TextView = v.findViewById(R.id.tvCampMeta)
        val city: TextView = v.findViewById(R.id.tvCampCity)
        val date: TextView = v.findViewById(R.id.tvCampDate)
    }

    override fun getItemViewType(pos: Int): Int = when (getItem(pos)) {
        is CampRow.Header -> VIEW_HEADER
        is CampRow.Item -> VIEW_CAMP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderVH(inf.inflate(R.layout.item_camp_header, parent, false))
        } else {
            CampVH(inf.inflate(R.layout.item_camp, parent, false))
        }
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        when (val row = getItem(pos)) {
            is CampRow.Header -> (h as HeaderVH).text.text = row.text
            is CampRow.Item -> {
                val c = row.camp
                val vh = h as CampVH
                vh.name.text = c.name
                vh.meta.text = "${c.location} • ${c.organizer.ifBlank { "—" }}"
                vh.city.text = c.city
                vh.date.text = c.campDate
                vh.itemView.setOnClickListener { onClick(c) }
            }
        }
    }

    fun submit(list: List<CampRow>) {
        submitList(list)
    }
}
