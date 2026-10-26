package com.tayyab.blooddonation.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.Donation
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.model.UserAccount
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session
import java.time.LocalDate

class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "blood_donation.db"
        private const val DB_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE donors(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, age INTEGER NOT NULL, gender TEXT NOT NULL,
                blood_group TEXT NOT NULL, phone TEXT NOT NULL, city TEXT NOT NULL,
                weight REAL NOT NULL, last_donation_date TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE donations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                donor_id INTEGER NOT NULL, date TEXT NOT NULL, units INTEGER NOT NULL,
                FOREIGN KEY(donor_id) REFERENCES donors(id))"""
        )
        db.execSQL(
            """CREATE TABLE requests(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                patient_name TEXT NOT NULL, hospital TEXT NOT NULL,
                blood_group TEXT NOT NULL, units_needed INTEGER NOT NULL,
                urgency TEXT NOT NULL, contact TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'Pending', created_at TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE stock(
                blood_group TEXT PRIMARY KEY, units INTEGER NOT NULL DEFAULT 0)"""
        )
        for (g in BloodCompatibility.ALL_GROUPS) {
            db.execSQL("INSERT INTO stock(blood_group, units) VALUES('$g', 0)")
        }
        seed(db)
        createUsersTable(db)
        seedManager(db)
    }

    /** Accounts table, added in v2. Existing installs are migrated, not wiped. */
    private fun createUsersTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS users(
                username TEXT PRIMARY KEY, password_hash TEXT NOT NULL,
                role TEXT NOT NULL, donor_id INTEGER)"""
        )
    }

    /** Demo manager account for the teacher demo: manager / manager123. */
    private fun seedManager(db: SQLiteDatabase) {
        val existing = db.rawQuery(
            "SELECT COUNT(*) FROM users WHERE username='manager'", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (existing == 0) {
            val v = ContentValues().apply {
                put("username", "manager")
                put("password_hash", Session.hashPassword("manager", "manager123"))
                put("role", Session.ROLE_MANAGER)
            }
            db.insert("users", null, v)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 -> v2: only add accounts, keep all existing donors/stock/requests.
            createUsersTable(db)
            seedManager(db)
        } else {
            db.execSQL("DROP TABLE IF EXISTS donors")
            db.execSQL("DROP TABLE IF EXISTS donations")
            db.execSQL("DROP TABLE IF EXISTS requests")
            db.execSQL("DROP TABLE IF EXISTS stock")
            db.execSQL("DROP TABLE IF EXISTS users")
            onCreate(db)
        }
    }

    // ------------------------------------------------------------- accounts
    fun addUser(a: UserAccount) {
        val v = ContentValues().apply {
            put("username", a.username); put("password_hash", a.passwordHash)
            put("role", a.role); put("donor_id", a.donorId)
        }
        writableDatabase.insert("users", null, v)
    }

    fun getUser(username: String): UserAccount? {
        return readableDatabase.rawQuery(
            "SELECT username, password_hash, role, donor_id FROM users WHERE username = ?",
            arrayOf(username)
        ).use { c ->
            if (!c.moveToFirst()) null else UserAccount(
                username = c.getString(0), passwordHash = c.getString(1),
                role = c.getString(2),
                donorId = if (c.isNull(3)) null else c.getLong(3)
            )
        }
    }

    /** Registers a donor AND their login account together (used by RegisterActivity). */
    fun registerDonorAccount(donor: Donor, username: String, passwordHash: String): Long {
        val donorId = addDonor(donor)
        addUser(UserAccount(username, passwordHash, Session.ROLE_DONOR, donorId))
        return donorId
    }

    // ------------------------------------------- Supabase merge helpers (v1.3)
    // These exist so the online copy and the local copy can share ids.
    // A row downloaded from Supabase keeps its server id here (SQLite
    // allows an explicit id in an INTEGER PRIMARY KEY column), so the
    // local users table (donor_id) and donation rows keep pointing at
    // the same donor. Upserts merge: a downloaded row replaces the local
    // row with the same id, and local-only rows are left untouched.
    // The users table is never synced — accounts stay local only.
    fun insertDonorWithId(d: Donor): Long {
        if (d.id <= 0) return addDonor(d)
        val v = ContentValues().apply {
            put("id", d.id); put("name", d.name); put("age", d.age); put("gender", d.gender)
            put("blood_group", d.bloodGroup); put("phone", d.phone); put("city", d.city)
            put("weight", d.weightKg); put("last_donation_date", d.lastDonationDate)
        }
        writableDatabase.insertWithOnConflict(
            "donors", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
        return d.id
    }

    fun upsertDonor(d: Donor) {
        if (d.id <= 0) return
        insertDonorWithId(d)
    }

    fun upsertDonation(d: Donation) {
        if (d.id <= 0) return
        val v = ContentValues().apply {
            put("id", d.id); put("donor_id", d.donorId)
            put("date", d.date); put("units", d.units)
        }
        writableDatabase.insertWithOnConflict(
            "donations", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun insertRequestWithId(r: BloodRequest): Long {
        if (r.id <= 0) return addRequest(r)
        val v = ContentValues().apply {
            put("id", r.id); put("patient_name", r.patientName); put("hospital", r.hospital)
            put("blood_group", r.bloodGroup); put("units_needed", r.unitsNeeded)
            put("urgency", r.urgency); put("contact", r.contact)
            put("status", r.status); put("created_at", r.createdAt)
        }
        writableDatabase.insertWithOnConflict(
            "requests", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
        return r.id
    }

    fun upsertRequest(r: BloodRequest) {
        if (r.id <= 0) return
        insertRequestWithId(r)
    }

    fun setStock(bloodGroup: String, units: Int) {
        val v = ContentValues().apply { put("units", units) }
        writableDatabase.update("stock", v, "blood_group=?", arrayOf(bloodGroup))
    }

    /** Same as registerDonorAccount, but keeps a server-assigned donor id. */
    fun registerDonorAccountWithId(donor: Donor, username: String, passwordHash: String): Long {
        val donorId = insertDonorWithId(donor)
        addUser(UserAccount(username, passwordHash, Session.ROLE_DONOR, donorId))
        return donorId
    }

    /** A few sample donors so the app is demonstrable on first launch. */
    private fun seed(db: SQLiteDatabase) {
        val samples = listOf(
            Donor(name = "Ali Raza", age = 24, gender = "Male", bloodGroup = "B+", phone = "0300-1112233", city = "Faisalabad", weightKg = 68.0, lastDonationDate = null),
            Donor(name = "Fatima Noor", age = 21, gender = "Female", bloodGroup = "O+", phone = "0321-4455667", city = "Shahkot", weightKg = 55.0, lastDonationDate = null),
            Donor(name = "Usman Ali", age = 29, gender = "Male", bloodGroup = "O-", phone = "0333-9988776", city = "Jaranwala", weightKg = 74.0, lastDonationDate = null),
            Donor(name = "Ayesha Khan", age = 26, gender = "Female", bloodGroup = "A+", phone = "0345-2233445", city = "Faisalabad", weightKg = 60.0, lastDonationDate = null),
            Donor(name = "Bilal Ahmed", age = 32, gender = "Male", bloodGroup = "AB+", phone = "0301-6677889", city = "Lahore", weightKg = 80.0, lastDonationDate = null)
        )
        for (d in samples) insertDonor(db, d)
    }

    // ------------------------------------------------------------- donors
    private fun insertDonor(db: SQLiteDatabase, d: Donor): Long {
        val v = ContentValues().apply {
            put("name", d.name); put("age", d.age); put("gender", d.gender)
            put("blood_group", d.bloodGroup); put("phone", d.phone); put("city", d.city)
            put("weight", d.weightKg); put("last_donation_date", d.lastDonationDate)
        }
        return db.insert("donors", null, v)
    }

    fun addDonor(d: Donor): Long = insertDonor(writableDatabase, d)

    fun getAllDonors(): List<Donor> {
        val out = mutableListOf<Donor>()
        readableDatabase.rawQuery("SELECT * FROM donors ORDER BY name", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Donor(
                        id = c.getLong(0), name = c.getString(1), age = c.getInt(2),
                        gender = c.getString(3), bloodGroup = c.getString(4),
                        phone = c.getString(5), city = c.getString(6),
                        weightKg = c.getDouble(7), lastDonationDate = c.getString(8)
                    )
                )
            }
        }
        return out
    }

    fun getDonor(id: Long): Donor? = getAllDonors().firstOrNull { it.id == id }

    fun donorCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM donors", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun deleteDonor(id: Long) {
        writableDatabase.delete("donors", "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------- donations & stock
    /** Records a donation: adds to history, updates the donor, and adds units to stock. */
    fun recordDonation(donor: Donor, units: Int, date: LocalDate = LocalDate.now()) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("donor_id", donor.id); put("date", date.toString()); put("units", units)
        }
        db.insert("donations", null, v)
        val upd = ContentValues().apply { put("last_donation_date", date.toString()) }
        db.update("donors", upd, "id=?", arrayOf(donor.id.toString()))
        db.execSQL("UPDATE stock SET units = units + ? WHERE blood_group = ?",
            arrayOf(units, donor.bloodGroup))
    }

    fun donationsForDonor(donorId: Long): List<Donation> {
        val out = mutableListOf<Donation>()
        val sql = """SELECT d.id, d.donor_id, dn.name, dn.blood_group, d.date, d.units
                     FROM donations d JOIN donors dn ON dn.id = d.donor_id
                     WHERE d.donor_id = ? ORDER BY d.date DESC"""
        readableDatabase.rawQuery(sql, arrayOf(donorId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(Donation(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5)))
            }
        }
        return out
    }

    fun stockLevels(): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT blood_group, units FROM stock", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        }
        return BloodCompatibility.ALL_GROUPS.associateWith { out[it] ?: 0 }
    }

    fun totalUnitsInStock(): Int = stockLevels().values.sum()

    fun donationsThisMonth(): Int {
        val ym = LocalDate.now().toString().substring(0, 7) // yyyy-MM
        return readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(units),0) FROM donations WHERE date LIKE '$ym%'", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ------------------------------------------------------------- requests
    fun addRequest(r: BloodRequest): Long {
        val v = ContentValues().apply {
            put("patient_name", r.patientName); put("hospital", r.hospital)
            put("blood_group", r.bloodGroup); put("units_needed", r.unitsNeeded)
            put("urgency", r.urgency); put("contact", r.contact)
            put("status", r.status); put("created_at", r.createdAt)
        }
        return writableDatabase.insert("requests", null, v)
    }

    fun getAllRequests(): List<BloodRequest> {
        val out = mutableListOf<BloodRequest>()
        readableDatabase.rawQuery("SELECT * FROM requests ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    BloodRequest(
                        id = c.getLong(0), patientName = c.getString(1), hospital = c.getString(2),
                        bloodGroup = c.getString(3), unitsNeeded = c.getInt(4),
                        urgency = c.getString(5), contact = c.getString(6),
                        status = c.getString(7), createdAt = c.getString(8)
                    )
                )
            }
        }
        return out
    }

    fun getRequest(id: Long): BloodRequest? = getAllRequests().firstOrNull { it.id == id }

    fun pendingRequestCount(): Int = getAllRequests().count { it.status == "Pending" }

    /** Fulfils a request from matching stock (exact group first). Returns false if stock is short. */
    fun fulfillRequest(r: BloodRequest): Boolean {
        val stock = stockLevels()
        if ((stock[r.bloodGroup] ?: 0) < r.unitsNeeded) return false
        val db = writableDatabase
        db.execSQL("UPDATE stock SET units = units - ? WHERE blood_group = ?",
            arrayOf(r.unitsNeeded, r.bloodGroup))
        val upd = ContentValues().apply { put("status", "Fulfilled") }
        db.update("requests", upd, "id=?", arrayOf(r.id.toString()))
        return true
    }
}
