package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.LowStock
import com.tayyab.blooddonation.util.SupabaseSync

class StockActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var stockAdapter: StockAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock)
        title = "Blood Stock"

        // Bottom navigation (v1.22): this is the Stock tab.
        BottomNav.setup(this, R.id.nav_stock)
        BottomNav.setupChatFab(this)
        db = DbHelper(this)
        val rv = findViewById<RecyclerView>(R.id.rvStock)
        rv.setHasFixedSize(true)
        rv.layoutManager = GridLayoutManager(this, 2)
        stockAdapter = StockAdapter()
        rv.adapter = stockAdapter
    }

    override fun onResume() {
        super.onResume()
        Background.run {
            val stockMap = db.stockLevels()
            val stock = stockMap.toList()
            val lowGroups = LowStock.groups(stockMap)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                stockAdapter.submitList(stock)
                updateLowStockWarning(lowGroups)
                BottomNav.updateBadges(this@StockActivity)
            }
        }
        if (SupabaseSync.isOnline) {
            val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
            val label = findViewById<android.widget.TextView>(R.id.syncLabel)
            spinner.visibility = android.view.View.VISIBLE
            label.visibility = android.view.View.VISIBLE
            label.text = "Updating stock…"
            Background.run {
                SupabaseSync.pullStock(db)
                val fresh = db.stockLevels().toList()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    stockAdapter.submitList(fresh)
                    updateLowStockWarning(LowStock.groups(fresh.toMap()))
                    BottomNav.updateBadges(this@StockActivity)
                    spinner.visibility = android.view.View.GONE
                    label.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun updateLowStockWarning(lowGroups: List<Pair<String, Int>>) {
        val tv = findViewById<TextView>(R.id.tvLowStockWarning)
        if (lowGroups.isEmpty()) {
            tv.visibility = View.GONE
        } else {
            tv.visibility = View.VISIBLE
            val names = lowGroups.joinToString(", ") { "${it.first} (${it.second})" }
            tv.text = "Low stock: $names"
        }
    }

    /** Three-dot menu in the app bar: the secondary destinations. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean =
        BottomNav.onCreateOptionsMenu(this, menu)

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        BottomNav.onPrepareOptionsMenu(this, menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        BottomNav.onOptionsItemSelected(this, item) || super.onOptionsItemSelected(item)
}
