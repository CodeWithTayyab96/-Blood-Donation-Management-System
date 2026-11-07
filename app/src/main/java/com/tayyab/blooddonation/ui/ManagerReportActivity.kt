package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.ManagerReports
import com.tayyab.blooddonation.util.Session

/**
 * Phase 2: manager-only monthly CSV export.
 *
 * Session.isManager comes from the JWT claim and is a UI guard; the report
 * itself only reads the local SQLite copy, so there is no new server surface
 * to secure. The whole file build + write runs on [Background] (SQLite and
 * file I/O never on the main thread) and a failure is logged and shown.
 */
class ManagerReportActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (!Session.isManager) {
            finish()
            return
        }
        setContentView(R.layout.activity_manager_report)
        title = getString(R.string.report_title)
        db = DbHelper(this)

        findViewById<Button>(R.id.btnExportCsv).setOnClickListener { export() }
    }

    private fun export() {
        val button = findViewById<Button>(R.id.btnExportCsv)
        button.isEnabled = false
        val appContext = applicationContext
        Background.run {
            val csv = ManagerReports.build(appContext, db)
            val ok = ManagerReports.saveAndShare(appContext, csv)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                if (!ok) {
                    Toast.makeText(this, R.string.report_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
