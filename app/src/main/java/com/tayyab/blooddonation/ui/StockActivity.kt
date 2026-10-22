package com.tayyab.blooddonation.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper

class StockActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock)
        title = "Blood Stock"
        val db = DbHelper(this)
        val rows = db.stockLevels().toList()
        val rv = findViewById<RecyclerView>(R.id.rvStock)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = StockAdapter(rows)
    }
}
