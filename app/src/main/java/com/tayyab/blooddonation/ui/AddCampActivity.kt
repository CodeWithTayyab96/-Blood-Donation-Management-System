package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.Camp
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.SupabaseSync
import java.time.LocalDate

/** Phase 3: manager-only creation of a blood camp. */
class AddCampActivity : AppCompatActivity() {

    private lateinit var db: DbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_camp)
        title = getString(R.string.label_add_camp)
        db = DbHelper(this)

        val etName = findViewById<EditText>(R.id.etCampName)
        val etLocation = findViewById<EditText>(R.id.etCampLocation)
        val etCity = findViewById<EditText>(R.id.etCampCity)
        val etDate = findViewById<EditText>(R.id.etCampDate)
        val etOrganizer = findViewById<EditText>(R.id.etCampOrganizer)

        findViewById<Button>(R.id.btnSaveCamp).setOnClickListener {
            val name = etName.text.toString().trim()
            val location = etLocation.text.toString().trim()
            val city = etCity.text.toString().trim()
            val date = etDate.text.toString().trim()
            val organizer = etOrganizer.text.toString().trim()
            val validDate = runCatching { LocalDate.parse(date) }.isSuccess
            if (name.isBlank() || location.isBlank() || city.isBlank() || !validDate) {
                Toast.makeText(this, R.string.camp_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val camp = Camp(
                name = name, location = location, city = city,
                campDate = date, organizer = organizer
            )
            if (SupabaseSync.isOnline) {
                // Online first so the camp keeps its server id locally
                // too; if Supabase is unreachable, save locally only and
                // say so.
                Background.run {
                    val remoteId = SupabaseSync.addCampRemote(camp)
                    if (remoteId != null) db.upsertCamp(camp.copy(id = remoteId))
                    else db.insertCamp(camp)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (remoteId != null) R.string.camp_saved
                            else R.string.camp_saved_phone_only,
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            } else {
                db.insertCamp(camp)
                Toast.makeText(this, R.string.camp_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
