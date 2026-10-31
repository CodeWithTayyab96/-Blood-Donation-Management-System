package com.tayyab.blooddonation.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tayyab.blooddonation.model.BloodRequest
import com.tayyab.blooddonation.model.ContactDetail
import com.tayyab.blooddonation.model.Donation
import com.tayyab.blooddonation.model.Donor
import com.tayyab.blooddonation.model.Offer
import com.tayyab.blooddonation.model.Report
import com.tayyab.blooddonation.model.UserAccount
import com.tayyab.blooddonation.util.BloodCompatibility
import com.tayyab.blooddonation.util.Session
import java.time.LocalDate

class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "blood_donation.db"
        // v3 (v1.4): adds the manager@blood.local seed row for the
        // Supabase Auth manager login. Existing installs upgrade
        // without losing data (see onUpgrade).
        // v4: donations.donor_id gains ON DELETE CASCADE. SQLite cannot alter
        // a constraint, so the v3 -> v4 step rebuilds the donations table and
        // copies every row across (additive: no data is dropped).
        // v5: local password hashes move from SHA-256 to PBKDF2 (see Session).
        // Old hashes cannot be re-derived, so the seeded demo accounts are
        // rewritten here and every other account is upgraded at its next
        // online sign-in.
        // v6: P2P. requests gains requester_id/city/fulfilled_by/needed_by;
        // donors gains auth_id/available_to_donate; three new tables
        // (offers, contact_details, reports). All additive — onUpgrade runs
        // ALTER TABLE ADD COLUMN and CREATE TABLE, so no existing data is
        // dropped and legacy manager rows keep a null requester_id.
        private const val DB_VERSION = 6

        /**
         * Explicit column lists. `SELECT *` plus positional access silently
         * shifts every field the moment a column is added to the table, so the
         * order is pinned here and every row is read by name.
         */
        private const val DONOR_COLUMNS =
            "id, name, age, gender, blood_group, phone, city, weight, " +
            "last_donation_date, auth_id, available_to_donate"
        private const val REQUEST_COLUMNS =
            "id, patient_name, hospital, blood_group, units_needed, urgency, " +
            "contact, status, created_at, requester_id, city, fulfilled_by, needed_by"
    }

    /**
     * FK enforcement is off by default in SQLite and has to be switched on per
     * connection.
     *
     * It is enabled here only when the schema is already at the current
     * version. onConfigure runs *before* onUpgrade, and the v3 -> v4 step
     * rebuilds the donations table; with enforcement already on, that copy
     * would abort on pre-existing orphan rows (donations left behind by
     * v1.2-v1.4, which never cleaned them up when a donor was deleted) and the
     * app would crash on launch. [onOpen] turns enforcement on for every other
     * case, including a fresh install, once the schema work has finished.
     */
    override fun onConfigure(db: SQLiteDatabase) {
        if (db.version >= DB_VERSION) db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Runs after onCreate/onUpgrade and outside their transaction, so this
        // also covers a brand-new database (db.version is 0 during onConfigure).
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE donors(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, age INTEGER NOT NULL, gender TEXT NOT NULL,
                blood_group TEXT NOT NULL, phone TEXT NOT NULL, city TEXT NOT NULL,
                weight REAL NOT NULL, last_donation_date TEXT,
                auth_id TEXT, available_to_donate INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE donations(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                donor_id INTEGER NOT NULL, date TEXT NOT NULL, units INTEGER NOT NULL,
                FOREIGN KEY(donor_id) REFERENCES donors(id) ON DELETE CASCADE)"""
        )
        db.execSQL(
            """CREATE TABLE requests(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                patient_name TEXT NOT NULL, hospital TEXT NOT NULL,
                blood_group TEXT NOT NULL, units_needed INTEGER NOT NULL,
                urgency TEXT NOT NULL, contact TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'Pending', created_at TEXT NOT NULL,
                requester_id TEXT, city TEXT, fulfilled_by TEXT, needed_by TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE stock(
                blood_group TEXT PRIMARY KEY, units INTEGER NOT NULL DEFAULT 0)"""
        )
        createOffersTable(db)
        createContactDetailsTable(db)
        createReportsTable(db)
        for (g in BloodCompatibility.ALL_GROUPS) {
            db.execSQL("INSERT INTO stock(blood_group, units) VALUES('$g', 0)")
        }
        seed(db)
        createUsersTable(db)
        seedManager(db)
    }

    /** P2P: a donor's offer to fulfil a request. One row per (donor, request). */
    private fun createOffersTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE offers(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id INTEGER NOT NULL, donor_id INTEGER NOT NULL,
                donor_auth_id TEXT, donor_name TEXT, donor_group TEXT,
                donor_city TEXT, status TEXT NOT NULL DEFAULT 'Offered',
                donor_confirmed_at TEXT, requester_confirmed_at TEXT,
                created_at TEXT NOT NULL)"""
        )
    }

    /** P2P: a donor's private phone, keyed by Auth user id. */
    private fun createContactDetailsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE contact_details(
                user_id TEXT PRIMARY KEY, phone TEXT NOT NULL)"""
        )
    }

    /** P2P: a report filed against a request. */
    private fun createReportsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE reports(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                request_id INTEGER NOT NULL, reporter_id TEXT, reason TEXT NOT NULL,
                created_at TEXT NOT NULL, resolved_at TEXT)"""
        )
    }

    /** Accounts table, added in v2. Existing installs are migrated, not wiped. */
    private fun createUsersTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS users(
                username TEXT PRIMARY KEY, password_hash TEXT NOT NULL,
                role TEXT NOT NULL, donor_id INTEGER)"""
        )
    }

    /**
     * Demo manager accounts for the teacher demo. "manager" is the
     * original local-only login; "manager@blood.local" is the same
     * manager through Supabase Auth (a matching Auth user exists in
     * the project), so the manager can also sign in with an email.
     * Both use the password manager123.
     *
     * Idempotent, and self-healing across the password-scheme change: a
     * missing row is inserted, and an existing row whose hash is still in the
     * old SHA-256 format (or a weaker PBKDF2 setting) is rewritten in place.
     * Nothing is deleted, so an upgrading install keeps all its data and the
     * demo logins keep working.
     */
    private fun seedManager(db: SQLiteDatabase) {
        for (username in listOf("manager", "manager@blood.local")) {
            val existingHash = db.rawQuery(
                "SELECT password_hash FROM users WHERE username=?", arrayOf(username)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (existingHash != null && !Session.needsRehash(existingHash)) continue
            val v = ContentValues().apply {
                put("username", username)
                put("password_hash", Session.hashPassword("manager123"))
                put("role", Session.ROLE_MANAGER)
            }
            if (existingHash == null) db.insert("users", null, v)
            else db.update("users", v, "username=?", arrayOf(username))
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Sequential, additive steps: an install that jumps several versions
        // (v1 -> v4) runs every step it missed, instead of only the first
        // branch that matches.
        //
        // There is deliberately NO else branch here. onUpgrade must never drop
        // user data — the previous version had an else branch that dropped
        // every table, which would have wiped all donors, donations, requests,
        // stock and accounts the first time DB_VERSION was bumped.
        if (oldVersion < 2) {
            // v1 -> v2: only add the accounts table, keep all existing
            // donors/stock/requests. The rows themselves are seeded by the
            // step below.
            createUsersTable(db)
        }
        if (oldVersion < 4) {
            // v3 -> v4: give donations.donor_id ON DELETE CASCADE so deleting a
            // donor also removes their donation history instead of leaving
            // orphan rows behind (see deleteDonor). Additive: every existing
            // donation row is copied across.
            rebuildDonationsWithCascade(db)
        }
        if (oldVersion < 5) {
            // v2 -> v3 added the manager@blood.local seed, and v4 -> v5 rewrote
            // both demo hashes in the PBKDF2 format. One call covers both: it
            // inserts a missing row and repairs a stale hash in place, and it
            // runs once per upgrade rather than once per skipped version (each
            // hash is 200,000 PBKDF2 rounds, so repeating it is not free).
            seedManager(db)
        }
        if (oldVersion < 6) {
            // v6: P2P schema. Everything here is additive and non-destructive.
            // Existing manager requests keep a null requester_id (treated as
            // manager-owned); existing donors get available_to_donate = 0 and a
            // null auth_id (the seeded demo donors are manager data, not peer
            // accounts, so their phones are deliberately NOT copied into
            // contact_details — that only happens for donors with an auth_id).
            db.execSQL("ALTER TABLE requests ADD COLUMN requester_id TEXT")
            db.execSQL("ALTER TABLE requests ADD COLUMN city TEXT")
            db.execSQL("ALTER TABLE requests ADD COLUMN fulfilled_by TEXT")
            db.execSQL("ALTER TABLE requests ADD COLUMN needed_by TEXT")
            db.execSQL(
                "ALTER TABLE donors ADD COLUMN auth_id TEXT"
            )
            db.execSQL(
                "ALTER TABLE donors ADD COLUMN available_to_donate INTEGER NOT NULL DEFAULT 0"
            )
            createOffersTable(db)
            createContactDetailsTable(db)
            createReportsTable(db)
        }
    }

    /**
     * Recreates the donations table with `ON DELETE CASCADE`. SQLite has no
     * `ALTER TABLE ... ALTER CONSTRAINT`, so the standard rebuild applies:
     * create the new shape, copy every row, drop the old table, rename.
     *
     * This runs inside SQLiteOpenHelper's upgrade transaction and, by design,
     * before FK enforcement is switched on (see [onConfigure]), so rows whose
     * donor was already deleted by an older version are copied as-is rather
     * than aborting the upgrade.
     */
    private fun rebuildDonationsWithCascade(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE donations_v4(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                donor_id INTEGER NOT NULL, date TEXT NOT NULL, units INTEGER NOT NULL,
                FOREIGN KEY(donor_id) REFERENCES donors(id) ON DELETE CASCADE)"""
        )
        // Preserve the AUTOINCREMENT high-water mark so ids are never reused,
        // even for ids that were deleted before this upgrade.
        val previousSeq = db.rawQuery(
            "SELECT seq FROM sqlite_sequence WHERE name = 'donations'", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        db.execSQL(
            """INSERT INTO donations_v4(id, donor_id, date, units)
               SELECT id, donor_id, date, units FROM donations"""
        )
        db.execSQL("DROP TABLE donations")
        db.execSQL("ALTER TABLE donations_v4 RENAME TO donations")
        // Put the AUTOINCREMENT high-water mark back where it was, so an id that
        // was deleted before this upgrade is never handed out a second time.
        val currentSeq = db.rawQuery(
            "SELECT seq FROM sqlite_sequence WHERE name = 'donations'", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val restoredSeq = maxOf(previousSeq, currentSeq)
        db.execSQL("DELETE FROM sqlite_sequence WHERE name = 'donations'")
        db.execSQL(
            "INSERT INTO sqlite_sequence(name, seq) VALUES('donations', ?)",
            arrayOf(restoredSeq)
        )
    }

    // ------------------------------------------------------------- accounts
    /**
     * Inserts an account row. Returns the row id, or -1 when the username
     * already exists — `insert` does not throw on a primary-key conflict, it
     * just reports failure, so callers have to look at the result.
     */
    fun addUser(a: UserAccount): Long {
        val v = ContentValues().apply {
            put("username", a.username); put("password_hash", a.passwordHash)
            put("role", a.role); put("donor_id", a.donorId)
        }
        return writableDatabase.insert("users", null, v)
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

    /**
     * Rewrites a stored password hash in place. Used to migrate a hash that is
     * still in the old SHA-256 format once the password is known again — which
     * only happens at an online Supabase Auth sign-in.
     */
    fun updatePasswordHash(username: String, passwordHash: String) {
        val v = ContentValues().apply { put("password_hash", passwordHash) }
        writableDatabase.update("users", v, "username=?", arrayOf(username))
    }

    /**
     * Registers a donor AND their login account together (used by RegisterActivity).
     *
     * Returns the donor id, or -1 when the username already exists. Both rows go
     * in one transaction, so a duplicate username rolls the donor insert back
     * instead of leaving an orphan donor behind with no account.
     */
    fun registerDonorAccount(donor: Donor, username: String, passwordHash: String): Long {
        val db = writableDatabase
        var donorId = -1L
        db.beginTransaction()
        try {
            val newDonorId = addDonor(donor)
            val userRow = addUser(
                UserAccount(username, passwordHash, Session.ROLE_DONOR, newDonorId)
            )
            if (userRow > 0) {
                db.setTransactionSuccessful()
                donorId = newDonorId
            }
            // else: duplicate username. Not marking the transaction successful
            // makes endTransaction() undo the donor insert as well.
        } finally {
            db.endTransaction()
        }
        return donorId
    }

    // ------------------------------------------- Supabase merge helpers (v1.3)
    // These exist so the online copy and the local copy can share ids.
    // A row downloaded from Supabase keeps its server id here (SQLite
    // allows an explicit id in an INTEGER PRIMARY KEY column), so the
    // local users table (donor_id) and donation rows keep pointing at
    // the same donor. Upserts merge: a downloaded row replaces the local
    // row with the same id, and local-only rows are left untouched.
    // The users table is the local mirror of accounts: v1.4 signs
    // users in through Supabase Auth, and the local row (keyed by
    // email) maps the Auth user to a role and a donor profile, and
    // backs the offline-fallback login. The table itself is never
    // pushed to Supabase — Auth owns the server-side account.
    fun insertDonorWithId(d: Donor): Long {
        if (d.id <= 0) return addDonor(d)
        val db = writableDatabase
        val v = ContentValues().apply {
            put("name", d.name); put("age", d.age); put("gender", d.gender)
            put("blood_group", d.bloodGroup); put("phone", d.phone); put("city", d.city)
            put("weight", d.weightKg); put("last_donation_date", d.lastDonationDate)
            put("auth_id", d.authId)
            put("available_to_donate", if (d.availableToDonate) 1 else 0)
        }
        // Explicit upsert: update first, insert only when the row is new.
        // CONFLICT_REPLACE deletes and re-inserts the row, which with foreign
        // keys enforced would cascade-delete that donor's donation history as
        // a side effect of an ordinary pull.
        if (db.update("donors", v, "id=?", arrayOf(d.id.toString())) == 0) {
            v.put("id", d.id)
            db.insert("donors", null, v)
        }
        return d.id
    }

    fun upsertDonor(d: Donor) {
        if (d.id <= 0) return
        insertDonorWithId(d)
    }

    fun upsertDonation(d: Donation) {
        if (d.id <= 0) return
        val db = writableDatabase
        val v = ContentValues().apply {
            put("donor_id", d.donorId); put("date", d.date); put("units", d.units)
        }
        if (db.update("donations", v, "id=?", arrayOf(d.id.toString())) == 0) {
            v.put("id", d.id)
            db.insert("donations", null, v)
        }
    }

    fun insertRequestWithId(r: BloodRequest): Long {
        if (r.id <= 0) return addRequest(r)
        val db = writableDatabase
        val v = ContentValues().apply {
            put("patient_name", r.patientName); put("hospital", r.hospital)
            put("blood_group", r.bloodGroup); put("units_needed", r.unitsNeeded)
            put("urgency", r.urgency); put("contact", r.contact)
            put("status", r.status); put("created_at", r.createdAt)
            put("requester_id", r.requesterId)
            put("city", r.city)
            put("fulfilled_by", r.fulfilledBy)
            put("needed_by", r.neededBy)
        }
        if (db.update("requests", v, "id=?", arrayOf(r.id.toString())) == 0) {
            v.put("id", r.id)
            db.insert("requests", null, v)
        }
        return r.id
    }

    fun upsertRequest(r: BloodRequest) {
        if (r.id <= 0) return
        insertRequestWithId(r)
    }

    /**
     * P2P: counts open requests (anything not in a terminal state) owned by a
     * given requester. Used to enforce the hard 3-open-requests cap before an
     * insert; the server backs this up with a BEFORE INSERT trigger (RLS alone
     * cannot count cheaply).
     */
    fun openRequestCountForRequester(requesterId: String): Int {
        val terminal = listOf("Fulfilled", "Cancelled", "Expired")
        val placeholders = terminal.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM requests " +
                "WHERE requester_id = ? AND status NOT IN ($placeholders)",
            arrayOf(requesterId, *terminal.toTypedArray())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun setStock(bloodGroup: String, units: Int) {
        val v = ContentValues().apply { put("units", units) }
        writableDatabase.update("stock", v, "blood_group=?", arrayOf(bloodGroup))
    }

    /**
     * Same as registerDonorAccount, but keeps a server-assigned donor id.
     *
     * Returns the donor id, or -1 when the account row could not be written
     * (the email already exists locally). One transaction, so a failure rolls
     * the donor insert back instead of leaving an orphan donor behind.
     */
    fun registerDonorAccountWithId(donor: Donor, username: String, passwordHash: String): Long {
        val db = writableDatabase
        var donorId = -1L
        db.beginTransaction()
        try {
            val newDonorId = insertDonorWithId(donor)
            val userRow = addUser(
                UserAccount(username, passwordHash, Session.ROLE_DONOR, newDonorId)
            )
            if (userRow > 0) {
                db.setTransactionSuccessful()
                donorId = newDonorId
            }
        } finally {
            db.endTransaction()
        }
        return donorId
    }

    /**
     * Re-links a donor profile that was created locally first (its
     * profile insert failed at registration) to the id Supabase
     * assigned when the profile finally synced: the local row is
     * replaced by the server row, and the account row plus any
     * donations recorded meanwhile are moved to the new id. If the
     * server id happens to equal the local id, the row is just
     * replaced in place and nothing is deleted.
     */
    fun relinkDonorProfile(oldId: Long, newDonor: Donor): Long {
        if (newDonor.id == oldId) {
            insertDonorWithId(newDonor)
            return oldId
        }
        insertDonorWithId(newDonor)
        val db = writableDatabase
        val v = ContentValues().apply { put("donor_id", newDonor.id) }
        db.update("users", v, "donor_id=?", arrayOf(oldId.toString()))
        db.update("donations", v, "donor_id=?", arrayOf(oldId.toString()))
        db.delete("donors", "id=?", arrayOf(oldId.toString()))
        return newDonor.id
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
            put("auth_id", d.authId)
            put("available_to_donate", if (d.availableToDonate) 1 else 0)
        }
        return db.insert("donors", null, v)
    }

    fun addDonor(d: Donor): Long = insertDonor(writableDatabase, d)

    fun getAllDonors(): List<Donor> {
        val out = mutableListOf<Donor>()
        readableDatabase.rawQuery(
            "SELECT $DONOR_COLUMNS FROM donors ORDER BY name", null
        ).use { c ->
            while (c.moveToNext()) out.add(readDonor(c))
        }
        return out
    }

    /** One row by id — a WHERE query, not a full scan of every donor. */
    fun getDonor(id: Long): Donor? {
        readableDatabase.rawQuery(
            "SELECT $DONOR_COLUMNS FROM donors WHERE id = ?", arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) readDonor(c) else null
        }
    }

    fun donorCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM donors", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Removes a donor; their donations go with them (ON DELETE CASCADE, DB v4). */
    fun deleteDonor(id: Long) {
        writableDatabase.delete("donors", "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------- donations & stock
    /** Records a donation: adds to history, updates the donor, and adds units to stock. */
    fun recordDonation(donor: Donor, units: Int, date: LocalDate = LocalDate.now()) {
        recordDonationInternal(donor, units, date, donationId = null)
    }

    /**
     * Same as [recordDonation], but stores the row under the id Supabase already
     * assigned, so the local history entry and the online row are the same row.
     * Without this, an online donation would be inserted here under a fresh
     * local id and then downloaded again under its server id — two entries for
     * one donation.
     */
    fun recordDonationWithId(
        donor: Donor,
        units: Int,
        donationId: Long,
        date: LocalDate = LocalDate.now()
    ) {
        recordDonationInternal(donor, units, date, donationId)
    }

    /**
     * The donation row, the donor's last-donation date and the stock increment
     * all land in one transaction, so a failure part-way cannot leave the stock
     * count disagreeing with the history.
     */
    private fun recordDonationInternal(
        donor: Donor,
        units: Int,
        date: LocalDate,
        donationId: Long?
    ) {
        val db = writableDatabase
        val dateStr = date.toString()
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("donor_id", donor.id); put("date", dateStr); put("units", units)
            }
            if (donationId != null) {
                // Explicit upsert, same reason as insertDonorWithId.
                if (db.update("donations", v, "id=?", arrayOf(donationId.toString())) == 0) {
                    v.put("id", donationId)
                    db.insert("donations", null, v)
                }
            } else {
                db.insert("donations", null, v)
            }
            val upd = ContentValues().apply { put("last_donation_date", dateStr) }
            db.update("donors", upd, "id=?", arrayOf(donor.id.toString()))
            db.execSQL(
                "UPDATE stock SET units = units + ? WHERE blood_group = ?",
                arrayOf(units, donor.bloodGroup)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * P2P: records a peer donation — the history row and the donor's
     * last-donation date, and deliberately NOT the stock increment that
     * [recordDonation] does.
     *
     * A peer donation is a private arrangement between two people; the blood
     * never enters the blood bank's inventory, so adding units to stock would
     * overstate what the manager can actually issue (see DESIGN_P2P.md §6).
     */
    fun recordPeerDonation(donorId: Long, date: LocalDate) {
        val db = writableDatabase
        val dateStr = date.toString()
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("donor_id", donorId); put("date", dateStr); put("units", 1)
            }
            db.insert("donations", null, v)
            val upd = ContentValues().apply { put("last_donation_date", dateStr) }
            db.update("donors", upd, "id=?", arrayOf(donorId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
        // Bound parameter rather than string interpolation: the value is safe
        // today, but it should stay safe if its source ever changes.
        return readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(units),0) FROM donations WHERE date LIKE ?",
            arrayOf("$ym%")
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ------------------------------------------------------------- requests
    fun addRequest(r: BloodRequest): Long {
        val v = ContentValues().apply {
            put("patient_name", r.patientName); put("hospital", r.hospital)
            put("blood_group", r.bloodGroup); put("units_needed", r.unitsNeeded)
            put("urgency", r.urgency); put("contact", r.contact)
            put("status", r.status); put("created_at", r.createdAt)
            put("requester_id", r.requesterId)
            put("city", r.city)
            put("fulfilled_by", r.fulfilledBy)
            put("needed_by", r.neededBy)
        }
        return writableDatabase.insert("requests", null, v)
    }

    fun getAllRequests(): List<BloodRequest> {
        val out = mutableListOf<BloodRequest>()
        readableDatabase.rawQuery(
            "SELECT $REQUEST_COLUMNS FROM requests ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) out.add(readRequest(c))
        }
        return out
    }

    /** One row by id — a WHERE query, not a full scan of every request. */
    fun getRequest(id: Long): BloodRequest? {
        readableDatabase.rawQuery(
            "SELECT $REQUEST_COLUMNS FROM requests WHERE id = ?", arrayOf(id.toString())
        ).use { c ->
            return if (c.moveToFirst()) readRequest(c) else null
        }
    }

    /** Counted in SQL; loading every request just to filter them was wasteful. */
    fun pendingRequestCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM requests WHERE status = ?", arrayOf("Pending")
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** P2P: sets a request's status locally (Matched / Fulfilled / Cancelled / Expired). */
    fun setRequestStatus(id: Long, status: String) {
        val v = ContentValues().apply { put("status", status) }
        writableDatabase.update("requests", v, "id=?", arrayOf(id.toString()))
    }

    /** P2P: records how a request was fulfilled ("stock" or "donor"). */
    fun setRequestFulfilledBy(id: Long, fulfilledBy: String) {
        val v = ContentValues().apply { put("fulfilled_by", fulfilledBy) }
        writableDatabase.update("requests", v, "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------- P2P: offers

    /** Inserts an offer; returns the row id. One offer row per (donor, request). */
    fun insertOffer(o: Offer): Long {
        val v = ContentValues().apply {
            put("request_id", o.requestId); put("donor_id", o.donorId)
            put("donor_auth_id", o.donorAuthId)
            put("donor_name", o.donorName); put("donor_group", o.donorGroup)
            put("donor_city", o.donorCity); put("status", o.status)
            put("donor_confirmed_at", o.donorConfirmedAt)
            put("requester_confirmed_at", o.requesterConfirmedAt)
            put("created_at", o.createdAt)
        }
        return writableDatabase.insert("offers", null, v)
    }

    fun getOffer(id: Long): Offer? {
        readableDatabase.rawQuery(
            "SELECT * FROM offers WHERE id = ?", arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) readOffer(c) else null }
    }

    /** The single offer a given donor has made on a given request (or null). */
    fun getOffer(requestId: Long, donorId: Long): Offer? {
        readableDatabase.rawQuery(
            "SELECT * FROM offers WHERE request_id = ? AND donor_id = ?",
            arrayOf(requestId.toString(), donorId.toString())
        ).use { c -> return if (c.moveToFirst()) readOffer(c) else null }
    }

    fun getOffersForRequest(requestId: Long): List<Offer> {
        val out = mutableListOf<Offer>()
        readableDatabase.rawQuery(
            "SELECT * FROM offers WHERE request_id = ? ORDER BY id ASC",
            arrayOf(requestId.toString())
        ).use { c -> while (c.moveToNext()) out.add(readOffer(c)) }
        return out
    }

    /** Every offer, newest first. Used by the manager moderation screen. */
    fun getAllOffers(): List<Offer> {
        val out = mutableListOf<Offer>()
        readableDatabase.rawQuery(
            "SELECT * FROM offers ORDER BY id DESC", null
        ).use { c -> while (c.moveToNext()) out.add(readOffer(c)) }
        return out
    }

    /** Merges a downloaded offer by id (update first; insert only when new). */
    fun upsertOffer(o: Offer) {
        if (o.id <= 0) return
        val v = ContentValues().apply {
            put("request_id", o.requestId); put("donor_id", o.donorId)
            put("donor_auth_id", o.donorAuthId)
            put("donor_name", o.donorName); put("donor_group", o.donorGroup)
            put("donor_city", o.donorCity); put("status", o.status)
            put("donor_confirmed_at", o.donorConfirmedAt)
            put("requester_confirmed_at", o.requesterConfirmedAt)
            put("created_at", o.createdAt)
        }
        if (writableDatabase.update("offers", v, "id=?", arrayOf(o.id.toString())) == 0) {
            v.put("id", o.id)
            writableDatabase.insert("offers", null, v)
        }
    }

    /** Updates an offer's status (Offered / Accepted / Declined / Completed). */
    fun setOfferStatus(id: Long, status: String) {
        val v = ContentValues().apply { put("status", status) }
        writableDatabase.update("offers", v, "id=?", arrayOf(id.toString()))
    }

    fun setOfferDonorConfirmed(id: Long, at: String) {
        val v = ContentValues().apply { put("donor_confirmed_at", at) }
        writableDatabase.update("offers", v, "id=?", arrayOf(id.toString()))
    }

    fun setOfferRequesterConfirmed(id: Long, at: String) {
        val v = ContentValues().apply { put("requester_confirmed_at", at) }
        writableDatabase.update("offers", v, "id=?", arrayOf(id.toString()))
    }

    // ------------------------------------------------------------- P2P: contact_details

    /** The private phone for a user id, or null when none is stored. */
    fun getContactDetails(userId: String): ContactDetail? {
        readableDatabase.rawQuery(
            "SELECT user_id, phone FROM contact_details WHERE user_id = ?",
            arrayOf(userId)
        ).use { c ->
            return if (c.moveToFirst()) ContactDetail(c.getString(0), c.getString(1)) else null
        }
    }

    /** Inserts or replaces a user's private phone (keyed by user id). */
    fun upsertContactDetails(c: ContactDetail) {
        val v = ContentValues().apply { put("user_id", c.userId); put("phone", c.phone) }
        writableDatabase.insertWithOnConflict(
            "contact_details", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ------------------------------------------------------------- P2P: reports

    fun insertReport(r: Report): Long {
        val v = ContentValues().apply {
            put("request_id", r.requestId); put("reporter_id", r.reporterId)
            put("reason", r.reason); put("created_at", r.createdAt)
            put("resolved_at", r.resolvedAt)
        }
        return writableDatabase.insert("reports", null, v)
    }

    /** All reports, newest first. Manager-only in the UI; RLS enforces it too. */
    fun getReports(): List<Report> {
        val out = mutableListOf<Report>()
        readableDatabase.rawQuery(
            "SELECT * FROM reports ORDER BY id DESC", null
        ).use { c -> while (c.moveToNext()) out.add(readReport(c)) }
        return out
    }

    fun getReportsForRequest(requestId: Long): List<Report> {
        val out = mutableListOf<Report>()
        readableDatabase.rawQuery(
            "SELECT * FROM reports WHERE request_id = ? ORDER BY id DESC",
            arrayOf(requestId.toString())
        ).use { c -> while (c.moveToNext()) out.add(readReport(c)) }
        return out
    }

    fun markReportResolved(id: Long, at: String) {
        val v = ContentValues().apply { put("resolved_at", at) }
        writableDatabase.update("reports", v, "id=?", arrayOf(id.toString()))
    }

    /** Merges a downloaded report by id (update first; insert only when new). */
    fun upsertReport(r: Report) {
        if (r.id <= 0) return
        val v = ContentValues().apply {
            put("request_id", r.requestId); put("reporter_id", r.reporterId)
            put("reason", r.reason); put("created_at", r.createdAt)
            put("resolved_at", r.resolvedAt)
        }
        if (writableDatabase.update("reports", v, "id=?", arrayOf(r.id.toString())) == 0) {
            v.put("id", r.id)
            writableDatabase.insert("reports", null, v)
        }
    }

    private fun readOffer(c: Cursor): Offer = Offer(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        requestId = c.getLong(c.getColumnIndexOrThrow("request_id")),
        donorId = c.getLong(c.getColumnIndexOrThrow("donor_id")),
        donorAuthId = c.getString(c.getColumnIndexOrThrow("donor_auth_id")),
        donorName = c.getString(c.getColumnIndexOrThrow("donor_name")) ?: "",
        donorGroup = c.getString(c.getColumnIndexOrThrow("donor_group")) ?: "",
        donorCity = c.getString(c.getColumnIndexOrThrow("donor_city")) ?: "",
        status = c.getString(c.getColumnIndexOrThrow("status")) ?: "Offered",
        donorConfirmedAt = c.getString(c.getColumnIndexOrThrow("donor_confirmed_at")),
        requesterConfirmedAt = c.getString(c.getColumnIndexOrThrow("requester_confirmed_at")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: ""
    )

    private fun readReport(c: Cursor): Report = Report(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        requestId = c.getLong(c.getColumnIndexOrThrow("request_id")),
        reporterId = c.getString(c.getColumnIndexOrThrow("reporter_id")),
        reason = c.getString(c.getColumnIndexOrThrow("reason")) ?: "",
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")) ?: "",
        resolvedAt = c.getString(c.getColumnIndexOrThrow("resolved_at"))
    )

    // ------------------------------------------------------------- row mapping

    private fun readDonor(c: Cursor): Donor = Donor(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        age = c.getInt(c.getColumnIndexOrThrow("age")),
        gender = c.getString(c.getColumnIndexOrThrow("gender")),
        bloodGroup = c.getString(c.getColumnIndexOrThrow("blood_group")),
        phone = c.getString(c.getColumnIndexOrThrow("phone")),
        city = c.getString(c.getColumnIndexOrThrow("city")),
        weightKg = c.getDouble(c.getColumnIndexOrThrow("weight")),
        lastDonationDate = c.getString(c.getColumnIndexOrThrow("last_donation_date")),
        authId = c.getString(c.getColumnIndexOrThrow("auth_id")),
        availableToDonate = c.getInt(c.getColumnIndexOrThrow("available_to_donate")) != 0
    )

    private fun readRequest(c: Cursor): BloodRequest = BloodRequest(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        patientName = c.getString(c.getColumnIndexOrThrow("patient_name")),
        hospital = c.getString(c.getColumnIndexOrThrow("hospital")),
        bloodGroup = c.getString(c.getColumnIndexOrThrow("blood_group")),
        unitsNeeded = c.getInt(c.getColumnIndexOrThrow("units_needed")),
        urgency = c.getString(c.getColumnIndexOrThrow("urgency")),
        contact = c.getString(c.getColumnIndexOrThrow("contact")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        createdAt = c.getString(c.getColumnIndexOrThrow("created_at")),
        requesterId = c.getString(c.getColumnIndexOrThrow("requester_id")),
        city = c.getString(c.getColumnIndexOrThrow("city")) ?: "",
        fulfilledBy = c.getString(c.getColumnIndexOrThrow("fulfilled_by")),
        neededBy = c.getString(c.getColumnIndexOrThrow("needed_by"))
    )

    /**
     * Fulfils a request from matching stock (exact group first). Returns false if stock is short.
     * The stock check, the stock decrement and the status change share one
     * transaction, so the request can never be marked Fulfilled without the
     * units actually leaving stock (or vice versa).
     */
    fun fulfillRequest(r: BloodRequest): Boolean {
        val db = writableDatabase
        var fulfilled = false
        db.beginTransaction()
        try {
            val available = stockLevels()[r.bloodGroup] ?: 0
            if (available >= r.unitsNeeded) {
                db.execSQL(
                    "UPDATE stock SET units = units - ? WHERE blood_group = ?",
                    arrayOf(r.unitsNeeded, r.bloodGroup)
                )
                val upd = ContentValues().apply { put("status", "Fulfilled") }
                db.update("requests", upd, "id=?", arrayOf(r.id.toString()))
                db.setTransactionSuccessful()
                fulfilled = true
            }
        } finally {
            db.endTransaction()
        }
        return fulfilled
    }
}
