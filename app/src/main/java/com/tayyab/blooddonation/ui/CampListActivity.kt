package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.CampRules
import com.tayyab.blooddonation.util.Session
import java.time.LocalDate

/**
 * Phase 3: list of blood camps, split into Upcoming and Past by [CampRules].
 * Only managers create camps in this build, so the FAB is manager-only.
 */
class CampListActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var adapter: CampAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_list)
        title = getString(R.string.title_camps)
        db = DbHelper(this)

        val rv = findViewById<RecyclerView>(R.id.rvCamps)
        rv.setHasFixedSize(true)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = CampAdapter { camp -> openDetail(camp.id) }
        rv.adapter = adapter

        Session.load(this)
        val fab = findViewById<FloatingActionButton>(R.id.fabAddCamp)
        fab.visibility = if (Session.isManager) View.VISIBLE else View.GONE
        fab.setOnClickListener { startActivity(Intent(this, AddCampActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val today = LocalDate.now()
        val all = db.getAllCamps()
        val upcoming = CampRules.sortByDate(all.filter { CampRules.isUpcoming(it, today) })
        val past = CampRules.sortByDate(all.filter { CampRules.isPast(it, today) }).reversed()

        val rows = mutableListOf<CampRow>()
        if (upcoming.isNotEmpty()) {
            rows += CampRow.Header(getString(R.string.camps_upcoming))
            rows += upcoming.map { CampRow.Item(it) }
        }
        if (past.isNotEmpty()) {
            rows += CampRow.Header(getString(R.string.camps_past))
            rows += past.map { CampRow.Item(it) }
        }
        adapter.submit(rows)
        findViewById<View>(R.id.tvEmpty).visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDetail(campId: Long) {
        startActivity(
            Intent(this, CampDetailActivity::class.java).putExtra("campId", campId)
        )
    }
}
