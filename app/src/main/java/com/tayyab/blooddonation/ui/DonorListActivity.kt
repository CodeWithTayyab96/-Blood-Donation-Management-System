package com.tayyab.blooddonation.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.util.BloodCompatibility

class DonorListActivity : AppCompatActivity() {

    private lateinit var db: DbHelper
    private lateinit var adapter: DonorAdapter
    private var allDonors: List<Donor> = emptyList()
    private var groupFilter = "All groups"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donor_list)
        title = "Donors"
        db = DbHelper(this)

        val rv = findViewById<RecyclerView>(R.id.rvDonors)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = DonorAdapter(emptyList()) { donor ->
            val i = Intent(this, DonorDetailActivity::class.java)
            i.putExtra("donorId", donor.id)
            startActivity(i)
        }
        rv.adapter = adapter

        val groups = listOf("All groups") + BloodCompatibility.ALL_GROUPS
        val sp = findViewById<Spinner>(R.id.spGroupFilter)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groups)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                groupFilter = groups[pos]
                applyFilters()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilters() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<FloatingActionButton>(R.id.fabAddDonor).setOnClickListener {
            startActivity(Intent(this, AddDonorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        allDonors = db.getAllDonors()
        applyFilters()
    }

    private fun applyFilters() {
        val q = findViewById<EditText>(R.id.etSearch).text.toString().trim().lowercase()
        val filtered = allDonors.filter { d ->
            (groupFilter == "All groups" || d.bloodGroup == groupFilter) &&
                (q.isEmpty() || d.name.lowercase().contains(q) || d.city.lowercase().contains(q))
        }
        adapter.submit(filtered)
        findViewById<TextView>(R.id.tvEmpty).visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
}
