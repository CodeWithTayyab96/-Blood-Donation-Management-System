package com.tayyab.blooddonation.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.SupabaseSync

class StockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock)
        title = "Blood Stock"
        val db = DbHelper(this)
        val rv = findViewById<RecyclerView>(R.id.rvStock)
        rv.layoutManager = LinearLayoutManager(this)
        // Local stock first, then a quiet online refresh (unreachable
        // Supabase = local stock stays, same rule as everywhere else).
        rv.adapter = StockAdapter(db.stockLevels().toList())
        if (SupabaseSync.isOnline) {
            Thread {
                SupabaseSync.pullStock(db)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    rv.adapter = StockAdapter(db.stockLevels().toList())
                }
            }.start()
        }
    }
}
