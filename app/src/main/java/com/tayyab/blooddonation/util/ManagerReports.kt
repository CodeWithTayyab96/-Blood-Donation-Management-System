package com.tayyab.blooddonation.util

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import java.io.File
import java.time.LocalDate

/**
 * Phase 2: the manager's monthly report as CSV.
 *
 * Three sections in one file (donations this month, stock levels, low stock).
 * Every cell goes through [CsvCells], which both escapes commas/quotes and
 * neutralises spreadsheet formula injection — donor and hospital names are
 * user-supplied and must never become `=SUM(...)` in Excel.
 *
 * The file is written into the app's private cache and shared through the
 * FileProvider, so no storage permission is needed. PDF is deliberately not
 * produced: it would need a new library, and the brief allows skipping it.
 */
object ManagerReports {

    private const val TAG = "ManagerReports"

    /** Builds the CSV text. Touches SQLite, so call off the main thread. */
    fun build(context: Context, db: DbHelper): String {
        val header = listOf(
            context.getString(R.string.report_col_section),
            context.getString(R.string.report_col_item),
            context.getString(R.string.report_col_value)
        )
        val rows = mutableListOf<List<String>>()
        rows.add(
            listOf(
                context.getString(R.string.report_section_donations),
                context.getString(R.string.report_month_units),
                db.donationsThisMonth().toString()
            )
        )
        val stock = db.stockLevels()
        for ((group, units) in stock) {
            rows.add(listOf(context.getString(R.string.report_section_stock), group, units.toString()))
        }
        for ((group, units) in LowStock.groups(stock)) {
            rows.add(listOf(context.getString(R.string.report_section_low), group, units.toString()))
        }
        return CsvCells.document(header, rows)
    }

    /** Writes and shares the report. Returns false (and logs) on any failure. */
    fun saveAndShare(context: Context, csv: String): Boolean {
        return try {
            val dir = File(context.cacheDir, "reports")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Could not create the report cache directory")
                return false
            }
            val month = LocalDate.now().toString().substring(0, 7)
            val file = File(dir, "monthly_report_$month.csv")
            file.writeText(csv, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                send, context.getString(R.string.report_share_chooser)
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not write or share the monthly report", e)
            false
        }
    }
}
