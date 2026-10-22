package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.BloodCompatibility

class MainActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = DbHelper(this)

        findViewById<Button>(R.id.btnDonors).setOnClickListener {
            startActivity(Intent(this, DonorListActivity::class.java))
        }
        findViewById<Button>(R.id.btnStock).setOnClickListener {
            startActivity(Intent(this, StockActivity::class.java))
        }
        findViewById<Button>(R.id.btnRequests).setOnClickListener {
            startActivity(Intent(this, RequestListActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.tvStatDonors).text = db.donorCount().toString()
        findViewById<TextView>(R.id.tvStatUnits).text = db.totalUnitsInStock().toString()
        findViewById<TextView>(R.id.tvStatRequests).text = db.pendingRequestCount().toString()
        findViewById<TextView>(R.id.tvStatMonth).text = db.donationsThisMonth().toString()
        val stock = db.stockLevels()
        findViewById<TextView>(R.id.tvStockSummary).text =
            BloodCompatibility.ALL_GROUPS.joinToString("\n") { g -> "$g : ${stock[g] ?: 0} unit(s)" }
    }
}
