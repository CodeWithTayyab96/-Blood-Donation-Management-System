package com.tayyab.blooddonation.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.SupabaseSync

class StockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock)
        title = "Blood Stock"
        val db = DbHelper(this)
        val rv = findViewById<RecyclerView>(R.id.rvStock)
        rv.layoutManager = GridLayoutManager(this, 2)
        // Local stock first, then a quiet online refresh (unreachable
        // Supabase = local stock stays, same rule as everywhere else).
        rv.adapter = StockAdapter(db.stockLevels().toList())
        if (SupabaseSync.isOnline) {
            val spinner = findViewById<android.widget.ProgressBar>(R.id.progressSync)
            val label = findViewById<android.widget.TextView>(R.id.syncLabel)
            spinner.visibility = android.view.View.VISIBLE
            label.visibility = android.view.View.VISIBLE
            label.text = "Updating stock…"
            Background.run {
                SupabaseSync.pullStock(db)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    rv.adapter = StockAdapter(db.stockLevels().toList())
                    spinner.visibility = android.view.View.GONE
                    label.visibility = android.view.View.GONE
                }
            }
        }
    }
}
