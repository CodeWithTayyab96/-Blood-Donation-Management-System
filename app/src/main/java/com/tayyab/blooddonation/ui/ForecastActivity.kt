package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.StockForecast
import java.time.LocalDate

/**
 * Phase 3: stock forecasting from real dated movements. [StockForecast] derives a
 * burn rate and days-of-stock per blood group; this screen just renders it. The
 * underlying movements are written by [DbHelper] on every donation (inflow) and
 * fulfilment (outflow).
 */
class ForecastActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var adapter: ForecastAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forecast)
        title = getString(R.string.title_forecast)
        db = DbHelper(this)

        val rv = findViewById<RecyclerView>(R.id.rvForecast)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ForecastAdapter()
        rv.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        Background.run {
            val movements = db.getAllMovements()
            val stock = db.stockLevels()
            val asOf = LocalDate.now()
            val data = StockForecast.forecast(movements, stock, asOf)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(data)
                findViewById<View>(R.id.tvEmpty).visibility =
                    if (data.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
