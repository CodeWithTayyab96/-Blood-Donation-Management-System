package com.tayyab.blooddonation.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tayyab.blooddonation.R
import com.tayyab.blooddonation.db.DbHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.util.Background
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.P2pRules
import com.tayyab.blooddonation.util.Session
import com.tayyab.blooddonation.util.SupabaseSync
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * P2P: any signed-in user posts a blood request of their own.
 *
 * This is deliberately NOT the manager-only [AddRequestActivity]. A peer
 * request is owned by its creator (`requests.requester_id`), which is what
 * lets donors offer to help directly and what keeps the request out of other
 * people's lists. Managers keep using AddRequestActivity for requests they
 * raise on behalf of a patient, which are fulfilled from stock.
 */
class CreateRequestActivity : AppCompatActivity() {

    private companion object {
        /** Sanity cap for a single request; also bounds the units input field. */
        const val MAX_UNITS_PER_REQUEST = 20
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.load(this)
        if (!Session.isLoggedIn) {
            finish()
            return
        }

        // The owner is the signed-in Auth user. A local-only account (the
        // seeded demo manager, or an offline fallback) has no Auth id, so a
        // request it created would land with a null requester_id and be
        // treated as manager-owned — invisible in "My requests" and
        // un-cancellable. Say so instead of creating it.
        val requesterId = Session.authUserId
        if (requesterId.isNullOrBlank()) {
            Toast.makeText(
                this,
                "Posting a request needs an email sign-in. This account is signed " +
                    "in locally only, so the request would not sync or be yours.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        setContentView(R.layout.activity_create_request)
        title = "Request Blood"
        val db = DbHelper(this)

        val spGroup = findViewById<Spinner>(R.id.spReqGroup)
        spGroup.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            BloodCompatibility.ALL_GROUPS
        )
        val spUrgency = findViewById<Spinner>(R.id.spUrgency)
        spUrgency.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Normal", "Urgent", "Critical")
        )

        findViewById<Button>(R.id.btnSaveRequest).setOnClickListener {
            val patient = textOf(R.id.etPatient)
            val hospital = textOf(R.id.etHospital)
            val unitsText = textOf(R.id.etUnits)
            val city = textOf(R.id.etCity)
            val contact = textOf(R.id.etContact)
            val neededByRaw = textOf(R.id.etNeededBy)

            if (patient.isEmpty() || hospital.isEmpty() || unitsText.isEmpty() ||
                city.isEmpty() || contact.isEmpty()
            ) {
                Toast.makeText(this, "Please fill all fields marked *", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Parse defensively: toInt() throws on "2.5", "1 0" or anything
            // outside Int range, which would crash the app.
            val units = unitsText.toIntOrNull()
            if (units == null || units !in 1..MAX_UNITS_PER_REQUEST) {
                Toast.makeText(
                    this,
                    "Units needed must be a whole number between 1 and $MAX_UNITS_PER_REQUEST",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Needed-by is optional; when given it must be a real ISO date
            // because the server column is a `date` and lazy expiry parses it.
            val neededBy = if (neededByRaw.isBlank()) {
                null
            } else {
                try {
                    LocalDate.parse(neededByRaw).toString()
                } catch (_: DateTimeParseException) {
                    Toast.makeText(
                        this,
                        "Needed-by must look like yyyy-mm-dd, or be left blank",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
            }

            // Client-side half of the open-requests cap. The server enforces
            // it too (BEFORE INSERT trigger), because RLS alone cannot count
            // rows cheaply — this is only the friendly message.
            val cap = P2pRules.MAX_OPEN_REQUESTS
            if (db.openRequestCountForRequester(requesterId) >= cap) {
                Toast.makeText(
                    this,
                    "You already have $cap open requests. " +
                        "Cancel one before posting another.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val req = BloodRequest(
                patientName = patient,
                hospital = hospital,
                bloodGroup = spGroup.selectedItem.toString(),
                unitsNeeded = units,
                urgency = spUrgency.selectedItem.toString(),
                contact = contact,
                status = "Pending",
                createdAt = LocalDate.now().toString(),
                requesterId = requesterId,
                city = city,
                neededBy = neededBy
            )
            save(db, req)
        }
    }

    private fun textOf(id: Int): String =
        findViewById<EditText>(id).text.toString().trim()

    /** Online first so the request keeps its server id locally; unreachable
     *  Supabase = saved locally only, and the user is told exactly that. */
    private fun save(db: DbHelper, req: BloodRequest) {
        if (!SupabaseSync.isOnline) {
            db.addRequest(req)
            Toast.makeText(this, "Request saved on this phone (offline).", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Background.run {
            val remoteId = SupabaseSync.addRequestRemote(req)
            if (remoteId != null) db.insertRequestWithId(req.copy(id = remoteId))
            else db.addRequest(req)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (remoteId != null) "Request posted" else
                        "Request saved on this phone (not synced to Supabase).",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}
