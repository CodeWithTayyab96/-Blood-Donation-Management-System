package com.tayyab.blooddonation.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.util.StockForecast

/**
 * Phase 3: one per-blood-group forecast row. Colour follows the same thresholds
 * the [StockForecast] rules derive, so the worst groups stand out at a glance.
 */
class ForecastAdapter : ListAdapter<StockForecast.GroupForecast, ForecastAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StockForecast.GroupForecast>() {
            override fun areItemsTheSame(old: StockForecast.GroupForecast, new: StockForecast.GroupForecast) =
                old.group == new.group
            override fun areContentsTheSame(old: StockForecast.GroupForecast, new: StockForecast.GroupForecast) =
                old == new
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val group: TextView = v.findViewById(R.id.tvForecastGroup)
        val status: TextView = v.findViewById(R.id.tvForecastStatus)
        val detail: TextView = v.findViewById(R.id.tvForecastDetail)
        val stock: TextView = v.findViewById(R.id.tvForecastStock)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_forecast, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = getItem(pos)
        val ctx = h.itemView.context
        h.group.text = f.group
        h.status.text = statusLabel(ctx, f.status)
        h.detail.text = buildString {
            append(
                if (f.daysRemaining == null) ctx.getString(R.string.forecast_no_burn)
                else ctx.getString(R.string.forecast_days_left, f.daysRemaining)
            )
            append("  •  ")
            append(ctx.getString(R.string.forecast_burn, f.burnPerDay))
        }
        h.stock.text = ctx.getString(R.string.forecast_stock_label, f.stock)
        val colorRes = when (f.status) {
            StockForecast.Status.CRITICAL -> R.color.status_bad
            StockForecast.Status.LOW -> R.color.status_warn
            else -> R.color.status_ok
        }
        h.status.setTextColor(ContextCompat.getColor(ctx, colorRes))
    }

    private fun statusLabel(ctx: android.content.Context, s: StockForecast.Status): String =
        ctx.getString(
            when (s) {
                StockForecast.Status.SURPLUS -> R.string.forecast_surplus
                StockForecast.Status.OK -> R.string.forecast_ok
                StockForecast.Status.LOW -> R.string.forecast_low
                StockForecast.Status.CRITICAL -> R.string.forecast_critical
            }
        )

    fun submit(list: List<StockForecast.GroupForecast>) {
        submitList(list)
    }
}
