package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync

class RequestListActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var adapter: RequestAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        setContentView(R.layout.activity_request_list)
        title = "Blood Requests"
        db = DbHelper(this)

        val rv = findViewById<RecyclerView>(R.id.rvRequests)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = RequestAdapter(emptyList()) { req ->
            val i = Intent(this, RequestDetailActivity::class.java)
            i.putExtra("requestId", req.id)
            startActivity(i)
        }
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAddRequest).setOnClickListener {
            // Managers raise a request on behalf of a patient and fulfil it
            // from stock (AddRequestActivity). Everyone else posts a peer
            // request of their own, which donors can offer to help with.
            startActivity(
                Intent(
                    this,
                    if (Session.isManager) AddRequestActivity::class.java
                    else CreateRequestActivity::class.java
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        renderFromLocal()
        if (SupabaseSync.isOnline) {
            Background.run {
                SupabaseSync.pullRequests(db)
                runOnUiThread { if (!isFinishing && !isDestroyed) renderFromLocal() }
            }
        }
    }

    private fun renderFromLocal() {
        val items = db.getAllRequests()
        adapter.submit(items)
        findViewById<TextView>(R.id.tvEmptyRequests).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
