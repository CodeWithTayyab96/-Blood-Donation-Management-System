package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.BloodRequest

class RequestAdapter(
    private var items: List<BloodRequest>,
    private val onClick: (BloodRequest) -> Unit
) : RecyclerView.Adapter<RequestAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val group: TextView = v.findViewById(R.id.tvReqGroup)
        val patient: TextView = v.findViewById(R.id.tvReqPatient)
        val meta: TextView = v.findViewById(R.id.tvReqMeta)
        val status: TextView = v.findViewById(R.id.tvReqStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        h.group.text = r.bloodGroup
        h.patient.text = r.patientName
        h.meta.text = "${r.hospital} • ${r.unitsNeeded} unit(s) • ${r.urgency}"
        h.status.text = r.status
        h.status.setTextColor(
            ContextCompat.getColor(
                h.itemView.context,
                if (r.status == "Fulfilled") R.color.status_ok else R.color.status_bad
            )
        )
        h.itemView.setOnClickListener { onClick(r) }
    }

    fun submit(list: List<BloodRequest>) {
        items = list
        notifyDataSetChanged()
    }
}
