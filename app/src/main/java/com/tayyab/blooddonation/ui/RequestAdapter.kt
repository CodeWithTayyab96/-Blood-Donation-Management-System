package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.BloodRequest

/**
 * [showCancel] switches the row into its "My requests" form: a Cancel button
 * appears, but only while the request is still open — a Fulfilled,
 * Cancelled or Expired request is terminal and must not offer the button.
 */
class RequestAdapter(
    private val onClick: (BloodRequest) -> Unit,
    private val showCancel: Boolean = false,
    private val onCancel: ((BloodRequest) -> Unit)? = null
) : ListAdapter<BloodRequest, RequestAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BloodRequest>() {
            override fun areItemsTheSame(old: BloodRequest, new: BloodRequest) = old.id == new.id
            override fun areContentsTheSame(old: BloodRequest, new: BloodRequest) = old == new
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val group: TextView = v.findViewById(R.id.tvReqGroup)
        val patient: TextView = v.findViewById(R.id.tvReqPatient)
        val meta: TextView = v.findViewById(R.id.tvReqMeta)
        val status: TextView = v.findViewById(R.id.tvReqStatus)
        val cancel: Button = v.findViewById(R.id.btnCancelRequest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = getItem(pos)
        h.group.text = r.bloodGroup
        h.patient.text = r.patientName
        val city = r.city.takeIf { it.isNotBlank() }?.let { "$it • " } ?: ""
        h.meta.text = "$city${r.hospital} • ${r.unitsNeeded} unit(s) • ${r.urgency}"
        h.status.text = r.status
        h.status.setTextColor(statusColor(h, r.status))
        h.status.setBackgroundResource(statusPill(r.status))

        if (showCancel && isOpen(r.status)) {
            h.cancel.visibility = View.VISIBLE
            h.cancel.setOnClickListener { onCancel?.invoke(r) }
        } else {
            h.cancel.visibility = View.GONE
            h.cancel.setOnClickListener(null)
        }
        h.itemView.setOnClickListener { onClick(r) }
    }

    private fun isOpen(status: String): Boolean =
        status !in listOf("Fulfilled", "Cancelled", "Expired")

    private fun statusColor(h: VH, status: String): Int = ContextCompat.getColor(
        h.itemView.context,
        when (status) {
            "Fulfilled" -> R.color.status_ok
            "Matched" -> R.color.status_warn
            "Cancelled", "Expired" -> R.color.text_secondary
            else -> R.color.status_bad
        }
    )

    private fun statusPill(status: String): Int = when (status) {
        "Fulfilled" -> R.drawable.bg_pill_green
        "Matched" -> R.drawable.bg_pill_amber
        "Cancelled", "Expired" -> R.drawable.bg_pill_grey
        else -> R.drawable.bg_pill_rose
    }

    fun submit(list: List<BloodRequest>) {
        submitList(list)
    }
}
