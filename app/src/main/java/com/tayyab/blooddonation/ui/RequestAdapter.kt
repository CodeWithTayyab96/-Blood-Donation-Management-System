package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.model.BloodRequest

/**
 * [showCancel] switches the row into its "My requests" form: a Cancel button
 * appears, but only while the request is still open — a Fulfilled,
 * Cancelled or Expired request is terminal and must not offer the button.
 */
class RequestAdapter(
    private var items: List<BloodRequest>,
    private val onClick: (BloodRequest) -> Unit,
    private val showCancel: Boolean = false,
    private val onCancel: ((BloodRequest) -> Unit)? = null
) : RecyclerView.Adapter<RequestAdapter.VH>() {

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

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = items[pos]
        h.group.text = r.bloodGroup
        h.patient.text = r.patientName
        // P2P: the city drives "nearby first", so it belongs on the row.
        val city = r.city.takeIf { it.isNotBlank() }?.let { "$it • " } ?: ""
        h.meta.text = "$city${r.hospital} • ${r.unitsNeeded} unit(s) • ${r.urgency}"
        h.status.text = r.status
        h.status.setTextColor(statusColor(h, r.status))

        if (showCancel && isOpen(r.status)) {
            h.cancel.visibility = View.VISIBLE
            // Consume the click so it does not also open the detail screen.
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

    fun submit(list: List<BloodRequest>) {
        items = list
        notifyDataSetChanged()
    }
}
