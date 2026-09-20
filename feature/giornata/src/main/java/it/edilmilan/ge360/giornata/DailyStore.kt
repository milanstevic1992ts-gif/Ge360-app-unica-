package it.edilmilan.ge360.giornata

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.edilmilan.ge360.cantieri.JobsiteRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID


data class WorkSession(
    var id: Long = 0,
    var ge360Id: String = "",
    var personnelId: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var workDate: String = LocalDate.now().toString(),
    var collaborator: String = "Milan",
    var hourlyRate: Double = 0.0,
    var startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long = 0,
    var note: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun isRunning(): Boolean = endedAt <= 0
    fun durationMinutes(now: Long = System.currentTimeMillis()): Long =
        ((if (endedAt > 0) endedAt else now) - startedAt).coerceAtLeast(0L) / 60_000L

    fun laborCost(now: Long = System.currentTimeMillis()): Double =
        (durationMinutes(now) / 60.0) * hourlyRate.coerceAtLeast(0.0)
}


data class DailyReport(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var workDate: String = LocalDate.now().toString(),
    var doneToday: String = "",
    var nextSteps: String = "",
    var issues: String = "",
    var materialsNeeded: String = "",
    var notes: String = "",
    var closed: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)


data class LaborBreakdown(
    val collaborator: String,
    val minutes: Long,
    val ratedMinutes: Long,
    val unratedMinutes: Long,
    val cost: Double,
    val currentRate: Double,
    val sessionRates: List<Double>
) {
    val hasMissingRate: Boolean get() = unratedMinutes > 0
}


data class PersonnelRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var name: String = "",
    var role: String = "Collaboratore",
    var hourlyRate: Double = 0.0,
    var phone: String = "",
    var note: String = "",
    var active: Boolean = true,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)


data class PersonnelStats(
    val personnel: PersonnelRecord,
    val minutes: Long,
    val cost: Double,
    val sessions: Int,
    val daysWorked: Int,
    val jobsites: Int,
    val unratedMinutes: Long,
    val currentJobsiteTitle: String?,
    val currentSessionMinutes: Long,
    val jobsiteBreakdown: List<PersonnelJobsiteStats>
) {
    val averageMinutesPerDay: Long get() = if (daysWorked <= 0) 0 else minutes / daysWorked
}


data class PersonnelJobsiteStats(
    val jobsiteId: String,
    val jobsiteCode: String,
    val jobsiteTitle: String,
    val minutes: Long,
    val cost: Double,
    val sessions: Int
)


data class PersonnelAssignment(
    var id: Long = 0,
    var ge360Id: String = "",
    var personnelId: String = "",
    var personnelName: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var workDate: String = LocalDate.now().toString(),
    var plannedMinutes: Int = 480,
    var hourlyRate: Double = 0.0,
    var note: String = "",
    var status: String = STATUS_PLANNED,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun plannedCost(): Double = plannedMinutes.coerceAtLeast(0) / 60.0 * hourlyRate.coerceAtLeast(0.0)

    companion object {
        const val STATUS_PLANNED = "Pianificato"
        const val STATUS_CANCELLED = "Annullato"
    }
}


data class PersonnelAssignmentActual(
    val assignment: PersonnelAssignment,
    val actualMinutes: Long,
    val actualCost: Double
) {
    val varianceMinutes: Long get() = actualMinutes - assignment.plannedMinutes
}


data class PersonnelPlanStats(
    val personnel: PersonnelRecord,
    val plannedMinutes: Long,
    val actualMinutes: Long,
    val plannedCost: Double,
    val actualCost: Double,
    val plannedDays: Int,
    val plannedJobsites: Int,
    val overbookedDays: Int,
    val unplannedActualMinutes: Long
) {
    val varianceMinutes: Long get() = actualMinutes - plannedMinutes
    val adherencePct: Int?
        get() = if (plannedMinutes > 0) ((actualMinutes.toDouble() / plannedMinutes.toDouble()) * 100.0).toInt() else null
}


class DailyStore(private val context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyRatePreference()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE work_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                personnel_id TEXT NOT NULL DEFAULT '',
                jobsite_id TEXT NOT NULL,
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                work_date TEXT NOT NULL,
                collaborator TEXT NOT NULL DEFAULT '',
                hourly_rate REAL NOT NULL DEFAULT 0,
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_daily_session_date ON work_sessions(work_date, started_at DESC)")
        db.execSQL("CREATE INDEX idx_daily_session_jobsite ON work_sessions(jobsite_id, work_date DESC)")
        db.execSQL("CREATE INDEX idx_daily_session_running ON work_sessions(ended_at, started_at DESC)")
        db.execSQL("CREATE INDEX idx_daily_session_personnel ON work_sessions(personnel_id, work_date DESC)")
        db.execSQL(
            """CREATE TABLE daily_reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                work_date TEXT NOT NULL,
                done_today TEXT NOT NULL DEFAULT '',
                next_steps TEXT NOT NULL DEFAULT '',
                issues TEXT NOT NULL DEFAULT '',
                materials_needed TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                closed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(jobsite_id, work_date)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_daily_report_date ON daily_reports(work_date DESC, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_daily_report_jobsite ON daily_reports(jobsite_id, work_date DESC)")
        createPersonnelTable(db)
        createPersonnelPlanningTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE work_sessions ADD COLUMN hourly_rate REAL NOT NULL DEFAULT 0")
            val legacy = legacyHourlyCost()
            if (legacy > 0.0) db.execSQL("UPDATE work_sessions SET hourly_rate=? WHERE hourly_rate<=0", arrayOf(legacy))
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE work_sessions ADD COLUMN personnel_id TEXT NOT NULL DEFAULT ''")
            createPersonnelTable(db)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_session_personnel ON work_sessions(personnel_id, work_date DESC)")
        }
        if (oldVersion < 4) createPersonnelPlanningTable(db)
    }

    private fun createPersonnelTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $PERSONNEL_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'Collaboratore',
                hourly_rate REAL NOT NULL DEFAULT 0,
                phone TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_personnel_name ON $PERSONNEL_TABLE(name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_personnel_active ON $PERSONNEL_TABLE(active, name COLLATE NOCASE)")
    }

    private fun createPersonnelPlanningTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $ASSIGNMENT_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                personnel_id TEXT NOT NULL,
                personnel_name TEXT NOT NULL DEFAULT '',
                jobsite_id TEXT NOT NULL,
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                work_date TEXT NOT NULL,
                planned_minutes INTEGER NOT NULL DEFAULT 480,
                hourly_rate REAL NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Pianificato',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(personnel_id, jobsite_id, work_date)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assignment_week ON $ASSIGNMENT_TABLE(work_date, personnel_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assignment_jobsite ON $ASSIGNMENT_TABLE(jobsite_id, work_date)")
    }

    // ---- Personale ---------------------------------------------------------

    fun ensurePersonnelCatalog() {
        val known = linkedMapOf<String, Pair<String, Double>>()
        configuredRatesFromPrefs().forEach { (name, rate) -> known[collaboratorKey(name)] = name to rate }
        readSessions(null, null, "started_at ASC").forEach { session ->
            val key = collaboratorKey(session.collaborator)
            if (key.isNotBlank()) {
                val existing = known[key]
                known[key] = session.collaborator.trim() to (existing?.second ?: session.hourlyRate)
            }
        }
        if (known.isEmpty()) known["milan"] = "Milan" to hourlyRateFromPrefs("Milan")
        known.values.forEach { (name, rate) ->
            if (personnelByName(name) == null) {
                savePersonnel(PersonnelRecord(name = name, hourlyRate = rate.coerceAtLeast(0.0)))
            }
        }
    }

    fun personnel(activeOnly: Boolean = false): List<PersonnelRecord> {
        createPersonnelTable(writableDatabase)
        val where = if (activeOnly) "active=1" else null
        val out = mutableListOf<PersonnelRecord>()
        readableDatabase.query(PERSONNEL_TABLE, null, where, null, null, null, "active DESC, name COLLATE NOCASE ASC").use { c ->
            while (c.moveToNext()) out += personnelFromCursor(c)
        }
        return out
    }

    fun getPersonnel(ge360Id: String): PersonnelRecord? {
        if (ge360Id.isBlank()) return null
        return readableDatabase.query(PERSONNEL_TABLE, null, "ge360_id=?", arrayOf(ge360Id), null, null, null, "1").use {
            if (it.moveToFirst()) personnelFromCursor(it) else null
        }
    }

    fun personnelByName(name: String): PersonnelRecord? {
        if (name.isBlank()) return null
        createPersonnelTable(writableDatabase)
        return readableDatabase.query(PERSONNEL_TABLE, null, "name=? COLLATE NOCASE", arrayOf(name.trim()), null, null, null, "1").use {
            if (it.moveToFirst()) personnelFromCursor(it) else null
        }
    }

    fun savePersonnel(record: PersonnelRecord): Long {
        val now = System.currentTimeMillis()
        require(record.name.trim().isNotBlank()) { "Nome persona obbligatorio" }
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        val existingByName = personnelByName(record.name)
        if (record.id <= 0 && existingByName != null && existingByName.ge360Id != record.ge360Id) {
            record.id = existingByName.id
            record.ge360Id = existingByName.ge360Id
            record.createdAt = existingByName.createdAt
        }
        val values = personnelValues(record)
        val id = if (record.id > 0) {
            writableDatabase.update(PERSONNEL_TABLE, values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else writableDatabase.insertOrThrow(PERSONNEL_TABLE, null, values).also { record.id = it }
        setRateInPrefs(record.name, record.hourlyRate)
        return id
    }

    fun setPersonnelActive(ge360Id: String, active: Boolean) {
        writableDatabase.update(PERSONNEL_TABLE, ContentValues().apply {
            put("active", if (active) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "ge360_id=?", arrayOf(ge360Id))
    }

    fun runningPersonnelSessions(): List<WorkSession> =
        readSessions("ended_at=0 AND personnel_id<>''", null, "started_at ASC")

    fun runningSessionForPersonnel(personnelId: String, personnelName: String = ""): WorkSession? {
        if (personnelId.isBlank() && personnelName.isBlank()) return null
        val where: String
        val args: Array<String>
        if (personnelId.isNotBlank() && personnelName.isNotBlank()) {
            where = "ended_at=0 AND (personnel_id=? OR (personnel_id='' AND collaborator=? COLLATE NOCASE))"
            args = arrayOf(personnelId, personnelName.trim())
        } else if (personnelId.isNotBlank()) {
            where = "ended_at=0 AND personnel_id=?"
            args = arrayOf(personnelId)
        } else {
            where = "ended_at=0 AND personnel_id='' AND collaborator=? COLLATE NOCASE"
            args = arrayOf(personnelName.trim())
        }
        return readableDatabase.query("work_sessions", null, where, args, null, null, "started_at DESC", "1").use {
            if (it.moveToFirst()) sessionFromCursor(it) else null
        }
    }

    fun startPersonnelSession(jobsite: JobsiteRecord, personnel: PersonnelRecord): WorkSession {
        require(personnel.ge360Id.isNotBlank()) { "Persona senza UUID GE360" }
        runningSessionForPersonnel(personnel.ge360Id, personnel.name)?.let { return it }
        require(jobsite.ge360Id.isNotBlank()) { "Cantiere senza UUID GE360" }
        val now = System.currentTimeMillis()
        val session = WorkSession(
            ge360Id = UUID.randomUUID().toString(),
            personnelId = personnel.ge360Id,
            jobsiteId = jobsite.ge360Id,
            jobsiteCode = jobsite.code,
            jobsiteTitle = jobsite.displayTitle(),
            workDate = LocalDate.now().toString(),
            collaborator = personnel.name,
            hourlyRate = personnel.hourlyRate.coerceAtLeast(0.0),
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        session.id = writableDatabase.insertOrThrow("work_sessions", null, sessionValues(session))
        return session
    }

    fun sessionsForPersonnel(personnel: PersonnelRecord, fromDate: LocalDate? = null, toDate: LocalDate? = null): List<WorkSession> {
        val clauses = mutableListOf("(personnel_id=? OR (personnel_id='' AND collaborator=? COLLATE NOCASE))")
        val args = mutableListOf(personnel.ge360Id, personnel.name)
        fromDate?.let { clauses += "work_date>=?"; args += it.toString() }
        toDate?.let { clauses += "work_date<=?"; args += it.toString() }
        return readSessions(clauses.joinToString(" AND "), args.toTypedArray(), "work_date DESC, started_at DESC")
    }

    fun personnelStats(personnel: PersonnelRecord, fromDate: LocalDate? = null, toDate: LocalDate? = null): PersonnelStats {
        val rows = sessionsForPersonnel(personnel, fromDate, toDate)
        val now = System.currentTimeMillis()
        val running = runningSessionForPersonnel(personnel.ge360Id, personnel.name)
        val breakdown = rows.groupBy { it.jobsiteId.ifBlank { it.jobsiteTitle } }.map { (_, sessions) ->
            val first = sessions.first()
            PersonnelJobsiteStats(
                jobsiteId = first.jobsiteId,
                jobsiteCode = first.jobsiteCode,
                jobsiteTitle = first.jobsiteTitle.ifBlank { first.jobsiteCode.ifBlank { "Cantiere" } },
                minutes = sessions.sumOf { it.durationMinutes(now) },
                cost = sessions.sumOf { it.laborCost(now) },
                sessions = sessions.size
            )
        }.sortedByDescending { it.minutes }
        return PersonnelStats(
            personnel = personnel,
            minutes = rows.sumOf { it.durationMinutes(now) },
            cost = rows.sumOf { it.laborCost(now) },
            sessions = rows.size,
            daysWorked = rows.map { it.workDate }.filter { it.isNotBlank() }.distinct().size,
            jobsites = rows.map { it.jobsiteId.ifBlank { it.jobsiteTitle } }.filter { it.isNotBlank() }.distinct().size,
            unratedMinutes = rows.filter { it.hourlyRate <= 0.0 }.sumOf { it.durationMinutes(now) },
            currentJobsiteTitle = running?.jobsiteTitle,
            currentSessionMinutes = running?.durationMinutes(now) ?: 0,
            jobsiteBreakdown = breakdown
        )
    }

    // ---- Pianificazione squadra -------------------------------------------

    fun assignments(
        fromDate: LocalDate? = null,
        toDate: LocalDate? = null,
        personnelId: String? = null,
        jobsiteId: String? = null,
        includeCancelled: Boolean = false
    ): List<PersonnelAssignment> {
        createPersonnelPlanningTable(writableDatabase)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        fromDate?.let { clauses += "work_date>=?"; args += it.toString() }
        toDate?.let { clauses += "work_date<=?"; args += it.toString() }
        if (!personnelId.isNullOrBlank()) { clauses += "personnel_id=?"; args += personnelId }
        if (!jobsiteId.isNullOrBlank()) { clauses += "jobsite_id=?"; args += jobsiteId }
        if (!includeCancelled) { clauses += "status<>?"; args += PersonnelAssignment.STATUS_CANCELLED }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val out = mutableListOf<PersonnelAssignment>()
        readableDatabase.query(
            ASSIGNMENT_TABLE, null, where, args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null, null, "work_date ASC, personnel_name COLLATE NOCASE ASC, jobsite_title COLLATE NOCASE ASC"
        ).use { c -> while (c.moveToNext()) out += assignmentFromCursor(c) }
        return out
    }

    fun saveAssignment(record: PersonnelAssignment): Long {
        require(record.personnelId.isNotBlank()) { "Persona obbligatoria" }
        require(record.jobsiteId.isNotBlank()) { "Cantiere obbligatorio" }
        require(runCatching { LocalDate.parse(record.workDate) }.isSuccess) { "Data non valida" }
        val now = System.currentTimeMillis()
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        record.plannedMinutes = record.plannedMinutes.coerceIn(15, 24 * 60)
        val existing = assignmentFor(record.personnelId, record.jobsiteId, record.workDate)
        if (record.id <= 0 && existing != null && existing.ge360Id != record.ge360Id) {
            record.id = existing.id
            record.ge360Id = existing.ge360Id
            record.createdAt = existing.createdAt
        }
        val values = assignmentValues(record)
        return if (record.id > 0) {
            writableDatabase.update(ASSIGNMENT_TABLE, values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else writableDatabase.insertOrThrow(ASSIGNMENT_TABLE, null, values).also { record.id = it }
    }

    fun assignmentFor(personnelId: String, jobsiteId: String, workDate: String): PersonnelAssignment? {
        if (personnelId.isBlank() || jobsiteId.isBlank() || workDate.isBlank()) return null
        return readableDatabase.query(
            ASSIGNMENT_TABLE, null, "personnel_id=? AND jobsite_id=? AND work_date=?",
            arrayOf(personnelId, jobsiteId, workDate), null, null, null, "1"
        ).use { if (it.moveToFirst()) assignmentFromCursor(it) else null }
    }

    fun deleteAssignment(id: Long) {
        writableDatabase.delete(ASSIGNMENT_TABLE, "id=?", arrayOf(id.toString()))
    }

    fun setAssignmentStatus(id: Long, status: String) {
        writableDatabase.update(ASSIGNMENT_TABLE, ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString()))
    }

    fun assignmentActual(record: PersonnelAssignment): PersonnelAssignmentActual {
        val person = getPersonnel(record.personnelId)
        val rows = if (person != null) {
            sessionsForPersonnel(person, LocalDate.parse(record.workDate), LocalDate.parse(record.workDate))
        } else {
            readSessions(
                "work_date=? AND (personnel_id=? OR (personnel_id='' AND collaborator=? COLLATE NOCASE))",
                arrayOf(record.workDate, record.personnelId, record.personnelName),
                "started_at ASC"
            )
        }.filter { it.jobsiteId == record.jobsiteId }
        val now = System.currentTimeMillis()
        return PersonnelAssignmentActual(
            assignment = record,
            actualMinutes = rows.sumOf { it.durationMinutes(now) },
            actualCost = rows.sumOf { it.laborCost(now) }
        )
    }

    fun personnelPlanStats(personnel: PersonnelRecord, fromDate: LocalDate? = null, toDate: LocalDate? = null): PersonnelPlanStats {
        val plannedRows = assignments(fromDate, toDate, personnel.ge360Id)
        val actualRows = sessionsForPersonnel(personnel, fromDate, toDate)
        val plannedByDay = plannedRows.groupBy { it.workDate }
        val plannedKeys = plannedRows.map { "${it.workDate}|${it.jobsiteId}" }.toSet()
        val now = System.currentTimeMillis()
        return PersonnelPlanStats(
            personnel = personnel,
            plannedMinutes = plannedRows.sumOf { it.plannedMinutes.toLong() },
            actualMinutes = actualRows.sumOf { it.durationMinutes(now) },
            plannedCost = plannedRows.sumOf { it.plannedCost() },
            actualCost = actualRows.sumOf { it.laborCost(now) },
            plannedDays = plannedRows.map { it.workDate }.distinct().size,
            plannedJobsites = plannedRows.map { it.jobsiteId }.filter { it.isNotBlank() }.distinct().size,
            overbookedDays = plannedByDay.count { (_, rows) -> rows.sumOf { it.plannedMinutes } > STANDARD_DAY_MINUTES },
            unplannedActualMinutes = actualRows
                .filter { "${it.workDate}|${it.jobsiteId}" !in plannedKeys }
                .sumOf { it.durationMinutes(now) }
        )
    }

    fun plannedMinutesForJobsite(jobsiteId: String, fromDate: LocalDate? = null, toDate: LocalDate? = null): Long =
        assignments(fromDate, toDate, jobsiteId = jobsiteId).sumOf { it.plannedMinutes.toLong() }

    fun plannedCostForJobsite(jobsiteId: String, fromDate: LocalDate? = null, toDate: LocalDate? = null): Double =
        assignments(fromDate, toDate, jobsiteId = jobsiteId).sumOf { it.plannedCost() }

    fun overbookedPersonnelDays(fromDate: LocalDate, toDate: LocalDate): Map<String, List<String>> {
        return assignments(fromDate, toDate)
            .groupBy { it.personnelId to it.workDate }
            .filterValues { rows -> rows.sumOf { it.plannedMinutes } > STANDARD_DAY_MINUTES }
            .entries
            .groupBy({ it.key.first }, { it.key.second })
    }

    // ---- Timer legacy Giornata --------------------------------------------

    /** Il timer storico di Giornata resta separato dai timer multi-persona. */
    fun runningSession(): WorkSession? = readableDatabase.query(
        "work_sessions", null, "ended_at=0 AND personnel_id=''", null, null, null, "started_at DESC", "1"
    ).use { if (it.moveToFirst()) sessionFromCursor(it) else null }

    fun startSession(jobsite: JobsiteRecord, collaborator: String): WorkSession {
        runningSession()?.let { return it }
        require(jobsite.ge360Id.isNotBlank()) { "Cantiere senza UUID GE360" }
        val now = System.currentTimeMillis()
        val name = collaborator.trim().ifBlank { "Milan" }
        val session = WorkSession(
            ge360Id = UUID.randomUUID().toString(),
            personnelId = "",
            jobsiteId = jobsite.ge360Id,
            jobsiteCode = jobsite.code,
            jobsiteTitle = jobsite.displayTitle(),
            workDate = LocalDate.now().toString(),
            collaborator = name,
            hourlyRate = hourlyRate(name),
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        session.id = writableDatabase.insertOrThrow("work_sessions", null, sessionValues(session))
        return session
    }

    fun stopSession(id: Long, note: String = ""): WorkSession? {
        val session = getSession(id) ?: return null
        if (session.endedAt <= 0) session.endedAt = System.currentTimeMillis()
        session.note = note.trim()
        session.updatedAt = System.currentTimeMillis()
        writableDatabase.update("work_sessions", sessionValues(session), "id=?", arrayOf(id.toString()))
        return session
    }

    fun getSession(id: Long): WorkSession? = readableDatabase.query(
        "work_sessions", null, "id=?", arrayOf(id.toString()), null, null, null, "1"
    ).use { if (it.moveToFirst()) sessionFromCursor(it) else null }

    fun sessions(workDate: String = LocalDate.now().toString(), jobsiteId: String? = null): List<WorkSession> {
        val clauses = mutableListOf("work_date=?")
        val args = mutableListOf(workDate)
        if (!jobsiteId.isNullOrBlank()) { clauses += "jobsite_id=?"; args += jobsiteId }
        return readSessions(clauses.joinToString(" AND "), args.toTypedArray(), "started_at DESC")
    }

    fun sessionsForJobsite(jobsiteId: String): List<WorkSession> =
        if (jobsiteId.isBlank()) emptyList() else readSessions("jobsite_id=?", arrayOf(jobsiteId), "started_at ASC")

    fun totalMinutes(workDate: String = LocalDate.now().toString(), jobsiteId: String? = null): Long =
        sessions(workDate, jobsiteId).sumOf { it.durationMinutes() }

    fun totalMinutesForJobsite(jobsiteId: String): Long = sessionsForJobsite(jobsiteId).sumOf { it.durationMinutes() }

    // ---- Tariffe / compatibilità ------------------------------------------

    fun hourlyRate(collaborator: String): Double {
        val person = runCatching { personnelByName(collaborator) }.getOrNull()
        if (person != null) return person.hourlyRate.coerceAtLeast(0.0)
        return hourlyRateFromPrefs(collaborator)
    }

    fun setHourlyRate(collaborator: String, value: Double) {
        val name = collaborator.trim().ifBlank { "Milan" }
        setRateInPrefs(name, value)
        runCatching {
            personnelByName(name)?.let { existing ->
                existing.hourlyRate = value.coerceAtLeast(0.0)
                savePersonnel(existing)
            }
        }
    }

    fun configuredCollaborators(): List<Pair<String, Double>> {
        val people = runCatching { personnel(false) }.getOrDefault(emptyList())
        if (people.isNotEmpty()) return people.map { it.name to it.hourlyRate }
        return configuredRatesFromPrefs()
    }

    fun knownCollaborators(): List<String> {
        val names = linkedMapOf<String, String>()
        configuredCollaborators().forEach { (name, _) -> names[collaboratorKey(name)] = name }
        readSessions(null, null, "started_at ASC").forEach { session ->
            val key = collaboratorKey(session.collaborator)
            if (key.isNotBlank()) names.putIfAbsent(key, session.collaborator.trim())
        }
        return names.values.sortedBy { it.lowercase(Locale.ITALY) }
    }

    fun laborBreakdownForJobsite(jobsiteId: String): List<LaborBreakdown> {
        val rows = sessionsForJobsite(jobsiteId)
        return rows.groupBy { it.personnelId.ifBlank { collaboratorKey(it.collaborator) } }
            .map { (_, sessions) ->
                val name = sessions.firstOrNull()?.collaborator?.trim().orEmpty().ifBlank { "Senza nome" }
                val minutes = sessions.sumOf { it.durationMinutes() }
                val rated = sessions.filter { it.hourlyRate > 0.0 }.sumOf { it.durationMinutes() }
                val unrated = sessions.filter { it.hourlyRate <= 0.0 }.sumOf { it.durationMinutes() }
                val rates = sessions.map { it.hourlyRate }.filter { it > 0.0 }.distinct().sorted()
                LaborBreakdown(
                    collaborator = name,
                    minutes = minutes,
                    ratedMinutes = rated,
                    unratedMinutes = unrated,
                    cost = sessions.sumOf { it.laborCost() },
                    currentRate = hourlyRate(name),
                    sessionRates = rates
                )
            }
            .sortedBy { it.collaborator.lowercase(Locale.ITALY) }
    }

    fun laborCostForJobsite(jobsiteId: String): Double = sessionsForJobsite(jobsiteId).sumOf { it.laborCost() }

    fun unratedMinutesForJobsite(jobsiteId: String): Long =
        sessionsForJobsite(jobsiteId).filter { it.hourlyRate <= 0.0 }.sumOf { it.durationMinutes() }

    fun hourlyCost(): Double = configuredCollaborators().map { it.second }.filter { it > 0.0 }.averageOrZero()

    fun setHourlyCost(value: Double) = setHourlyRate("Milan", value)

    // ---- Rapportini --------------------------------------------------------

    fun report(jobsiteId: String, workDate: String = LocalDate.now().toString()): DailyReport? = readableDatabase.query(
        "daily_reports", null, "jobsite_id=? AND work_date=?", arrayOf(jobsiteId, workDate), null, null, null, "1"
    ).use { if (it.moveToFirst()) reportFromCursor(it) else null }

    fun saveReport(report: DailyReport): Long {
        require(report.jobsiteId.isNotBlank()) { "Seleziona un cantiere" }
        val now = System.currentTimeMillis()
        if (report.ge360Id.isBlank()) report.ge360Id = UUID.randomUUID().toString()
        if (report.createdAt <= 0) report.createdAt = now
        report.updatedAt = now
        val existing = report(report.jobsiteId, report.workDate)
        if (report.id <= 0 && existing != null) {
            report.id = existing.id
            report.ge360Id = existing.ge360Id
            report.createdAt = existing.createdAt
        }
        return if (report.id > 0) {
            writableDatabase.update("daily_reports", reportValues(report), "id=?", arrayOf(report.id.toString()))
            report.id
        } else writableDatabase.insertOrThrow("daily_reports", null, reportValues(report)).also { report.id = it }
    }

    fun reports(limit: Int = 60): List<DailyReport> {
        val out = mutableListOf<DailyReport>()
        readableDatabase.query("daily_reports", null, null, null, null, null, "work_date DESC, updated_at DESC", limit.coerceAtLeast(1).toString()).use {
            while (it.moveToNext()) out += reportFromCursor(it)
        }
        return out
    }

    // ---- Backup ------------------------------------------------------------

    fun exportJson(): String {
        ensurePersonnelCatalog()
        val root = JSONObject()
            .put("app", "GE360")
            .put("module", "giornata")
            .put("formatVersion", 5)
            .put("exportedAt", System.currentTimeMillis())
            .put("hourlyCost", hourlyRate("Milan"))
            .put("laborRates", JSONArray().apply {
                configuredCollaborators().forEach { (name, rate) -> put(JSONObject().put("name", name).put("rate", rate)) }
            })
            .put("personnel", JSONArray().apply { personnel(false).forEach { put(personnelToJson(it)) } })
            .put("assignments", JSONArray().apply { assignments(includeCancelled = true).forEach { put(assignmentToJson(it)) } })
        root.put("sessions", JSONArray().apply { readSessions(null, null, "started_at ASC").forEach { put(sessionToJson(it)) } })
        root.put("reports", JSONArray().apply { reports(Int.MAX_VALUE).reversed().forEach { put(reportToJson(it)) } })
        return root.toString(2)
    }

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        if (replaceAll) prefs.edit().remove(KEY_LABOR_RATES).apply()
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("work_sessions", null, null)
                db.delete("daily_reports", null, null)
                db.delete(ASSIGNMENT_TABLE, null, null)
                db.delete(PERSONNEL_TABLE, null, null)
            }

            val importedRates = root.optJSONArray("laborRates")
            if (importedRates != null) {
                for (i in 0 until importedRates.length()) {
                    val row = importedRates.optJSONObject(i) ?: continue
                    setRateInPrefs(row.optString("name", ""), row.optDouble("rate", 0.0))
                }
            } else if (root.has("hourlyCost")) {
                setRateInPrefs("Milan", root.optDouble("hourlyCost", 0.0))
            }

            val peopleRows = root.optJSONArray("personnel") ?: JSONArray()
            for (i in 0 until peopleRows.length()) {
                val incoming = personnelFromJson(peopleRows.getJSONObject(i))
                getPersonnel(incoming.ge360Id)?.let { incoming.id = it.id }
                personnelByName(incoming.name)?.let { if (incoming.id <= 0) incoming.id = it.id }
                savePersonnel(incoming)
                imported++
            }

            val assignmentRows = root.optJSONArray("assignments") ?: JSONArray()
            for (i in 0 until assignmentRows.length()) {
                val incoming = assignmentFromJson(assignmentRows.getJSONObject(i))
                assignmentByUuid(incoming.ge360Id)?.let { incoming.id = it.id }
                saveAssignment(incoming)
                imported++
            }

            val legacyRate = root.optDouble("hourlyCost", 0.0).coerceAtLeast(0.0)
            val sessionRows = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until sessionRows.length()) {
                val incoming = sessionFromJson(sessionRows.getJSONObject(i), legacyRate)
                sessionByUuid(incoming.ge360Id)?.let { incoming.id = it.id }
                if (incoming.id > 0) db.update("work_sessions", sessionValues(incoming), "id=?", arrayOf(incoming.id.toString()))
                else incoming.id = db.insertOrThrow("work_sessions", null, sessionValues(incoming))
                imported++
            }
            val reportRows = root.optJSONArray("reports") ?: JSONArray()
            for (i in 0 until reportRows.length()) {
                val incoming = reportFromJson(reportRows.getJSONObject(i))
                (reportByUuid(incoming.ge360Id) ?: report(incoming.jobsiteId, incoming.workDate))?.let { incoming.id = it.id }
                saveReport(incoming)
                imported++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ensurePersonnelCatalog()
        return imported
    }

    // ---- Mapping -----------------------------------------------------------

    private fun readSessions(where: String?, args: Array<String>?, order: String): List<WorkSession> {
        val out = mutableListOf<WorkSession>()
        readableDatabase.query("work_sessions", null, where, args, null, null, order).use {
            while (it.moveToNext()) out += sessionFromCursor(it)
        }
        return out
    }

    private fun sessionByUuid(uuid: String): WorkSession? = if (uuid.isBlank()) null else readableDatabase.query(
        "work_sessions", null, "ge360_id=?", arrayOf(uuid), null, null, null, "1"
    ).use { if (it.moveToFirst()) sessionFromCursor(it) else null }

    private fun reportByUuid(uuid: String): DailyReport? = if (uuid.isBlank()) null else readableDatabase.query(
        "daily_reports", null, "ge360_id=?", arrayOf(uuid), null, null, null, "1"
    ).use { if (it.moveToFirst()) reportFromCursor(it) else null }

    private fun assignmentByUuid(uuid: String): PersonnelAssignment? = if (uuid.isBlank()) null else readableDatabase.query(
        ASSIGNMENT_TABLE, null, "ge360_id=?", arrayOf(uuid), null, null, null, "1"
    ).use { if (it.moveToFirst()) assignmentFromCursor(it) else null }

    private fun sessionValues(r: WorkSession) = ContentValues().apply {
        put("ge360_id", r.ge360Id); put("personnel_id", r.personnelId); put("jobsite_id", r.jobsiteId)
        put("jobsite_code", r.jobsiteCode); put("jobsite_title", r.jobsiteTitle); put("work_date", r.workDate)
        put("collaborator", r.collaborator); put("hourly_rate", r.hourlyRate.coerceAtLeast(0.0)); put("started_at", r.startedAt)
        put("ended_at", r.endedAt); put("note", r.note); put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun reportValues(r: DailyReport) = ContentValues().apply {
        put("ge360_id", r.ge360Id); put("jobsite_id", r.jobsiteId); put("jobsite_code", r.jobsiteCode); put("jobsite_title", r.jobsiteTitle)
        put("work_date", r.workDate); put("done_today", r.doneToday); put("next_steps", r.nextSteps); put("issues", r.issues)
        put("materials_needed", r.materialsNeeded); put("notes", r.notes); put("closed", if (r.closed) 1 else 0)
        put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun personnelValues(r: PersonnelRecord) = ContentValues().apply {
        put("ge360_id", r.ge360Id); put("name", r.name.trim()); put("role", r.role.trim().ifBlank { "Collaboratore" })
        put("hourly_rate", r.hourlyRate.coerceAtLeast(0.0)); put("phone", r.phone.trim()); put("note", r.note.trim())
        put("active", if (r.active) 1 else 0); put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun assignmentValues(r: PersonnelAssignment) = ContentValues().apply {
        put("ge360_id", r.ge360Id); put("personnel_id", r.personnelId); put("personnel_name", r.personnelName.trim())
        put("jobsite_id", r.jobsiteId); put("jobsite_code", r.jobsiteCode.trim()); put("jobsite_title", r.jobsiteTitle.trim())
        put("work_date", r.workDate); put("planned_minutes", r.plannedMinutes.coerceIn(15, 24 * 60)); put("hourly_rate", r.hourlyRate.coerceAtLeast(0.0))
        put("note", r.note.trim()); put("status", r.status); put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun sessionFromCursor(c: Cursor) = WorkSession(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        personnelId = c.getString(c.getColumnIndexOrThrow("personnel_id")), jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")), jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
        workDate = c.getString(c.getColumnIndexOrThrow("work_date")), collaborator = c.getString(c.getColumnIndexOrThrow("collaborator")),
        hourlyRate = c.getDouble(c.getColumnIndexOrThrow("hourly_rate")), startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
        endedAt = c.getLong(c.getColumnIndexOrThrow("ended_at")), note = c.getString(c.getColumnIndexOrThrow("note")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")), updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun reportFromCursor(c: Cursor) = DailyReport(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")), jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")), workDate = c.getString(c.getColumnIndexOrThrow("work_date")),
        doneToday = c.getString(c.getColumnIndexOrThrow("done_today")), nextSteps = c.getString(c.getColumnIndexOrThrow("next_steps")),
        issues = c.getString(c.getColumnIndexOrThrow("issues")), materialsNeeded = c.getString(c.getColumnIndexOrThrow("materials_needed")),
        notes = c.getString(c.getColumnIndexOrThrow("notes")), closed = c.getInt(c.getColumnIndexOrThrow("closed")) == 1,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")), updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun personnelFromCursor(c: Cursor) = PersonnelRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        name = c.getString(c.getColumnIndexOrThrow("name")), role = c.getString(c.getColumnIndexOrThrow("role")),
        hourlyRate = c.getDouble(c.getColumnIndexOrThrow("hourly_rate")), phone = c.getString(c.getColumnIndexOrThrow("phone")),
        note = c.getString(c.getColumnIndexOrThrow("note")), active = c.getInt(c.getColumnIndexOrThrow("active")) == 1,
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")), updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun assignmentFromCursor(c: Cursor) = PersonnelAssignment(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        personnelId = c.getString(c.getColumnIndexOrThrow("personnel_id")), personnelName = c.getString(c.getColumnIndexOrThrow("personnel_name")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")), jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")), workDate = c.getString(c.getColumnIndexOrThrow("work_date")),
        plannedMinutes = c.getInt(c.getColumnIndexOrThrow("planned_minutes")), hourlyRate = c.getDouble(c.getColumnIndexOrThrow("hourly_rate")),
        note = c.getString(c.getColumnIndexOrThrow("note")), status = c.getString(c.getColumnIndexOrThrow("status")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")), updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun sessionToJson(r: WorkSession) = JSONObject()
        .put("ge360Id", r.ge360Id).put("personnelId", r.personnelId).put("jobsiteId", r.jobsiteId)
        .put("jobsiteCode", r.jobsiteCode).put("jobsiteTitle", r.jobsiteTitle).put("workDate", r.workDate)
        .put("collaborator", r.collaborator).put("hourlyRate", r.hourlyRate).put("startedAt", r.startedAt)
        .put("endedAt", r.endedAt).put("note", r.note).put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)

    private fun reportToJson(r: DailyReport) = JSONObject()
        .put("ge360Id", r.ge360Id).put("jobsiteId", r.jobsiteId).put("jobsiteCode", r.jobsiteCode).put("jobsiteTitle", r.jobsiteTitle)
        .put("workDate", r.workDate).put("doneToday", r.doneToday).put("nextSteps", r.nextSteps).put("issues", r.issues)
        .put("materialsNeeded", r.materialsNeeded).put("notes", r.notes).put("closed", r.closed).put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)

    private fun personnelToJson(r: PersonnelRecord) = JSONObject()
        .put("ge360Id", r.ge360Id).put("name", r.name).put("role", r.role).put("hourlyRate", r.hourlyRate)
        .put("phone", r.phone).put("note", r.note).put("active", r.active).put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)

    private fun assignmentToJson(r: PersonnelAssignment) = JSONObject()
        .put("ge360Id", r.ge360Id).put("personnelId", r.personnelId).put("personnelName", r.personnelName)
        .put("jobsiteId", r.jobsiteId).put("jobsiteCode", r.jobsiteCode).put("jobsiteTitle", r.jobsiteTitle).put("workDate", r.workDate)
        .put("plannedMinutes", r.plannedMinutes).put("hourlyRate", r.hourlyRate).put("note", r.note).put("status", r.status)
        .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)

    private fun sessionFromJson(o: JSONObject, legacyRate: Double) = WorkSession(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), personnelId = o.optString("personnelId", ""),
        jobsiteId = o.optString("jobsiteId", ""), jobsiteCode = o.optString("jobsiteCode", ""), jobsiteTitle = o.optString("jobsiteTitle", ""),
        workDate = o.optString("workDate", LocalDate.now().toString()), collaborator = o.optString("collaborator", "Milan"),
        hourlyRate = if (o.has("hourlyRate")) o.optDouble("hourlyRate", 0.0).coerceAtLeast(0.0) else legacyRate,
        startedAt = o.optLong("startedAt", System.currentTimeMillis()), endedAt = o.optLong("endedAt", 0), note = o.optString("note", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun reportFromJson(o: JSONObject) = DailyReport(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId", ""),
        jobsiteCode = o.optString("jobsiteCode", ""), jobsiteTitle = o.optString("jobsiteTitle", ""), workDate = o.optString("workDate", LocalDate.now().toString()),
        doneToday = o.optString("doneToday", ""), nextSteps = o.optString("nextSteps", ""), issues = o.optString("issues", ""),
        materialsNeeded = o.optString("materialsNeeded", ""), notes = o.optString("notes", ""), closed = o.optBoolean("closed", false),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun personnelFromJson(o: JSONObject) = PersonnelRecord(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), name = o.optString("name", ""),
        role = o.optString("role", "Collaboratore"), hourlyRate = o.optDouble("hourlyRate", 0.0).coerceAtLeast(0.0),
        phone = o.optString("phone", ""), note = o.optString("note", ""), active = o.optBoolean("active", true),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun assignmentFromJson(o: JSONObject) = PersonnelAssignment(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), personnelId = o.optString("personnelId", ""),
        personnelName = o.optString("personnelName", ""), jobsiteId = o.optString("jobsiteId", ""),
        jobsiteCode = o.optString("jobsiteCode", ""), jobsiteTitle = o.optString("jobsiteTitle", ""),
        workDate = o.optString("workDate", LocalDate.now().toString()), plannedMinutes = o.optInt("plannedMinutes", 480).coerceIn(15, 24 * 60),
        hourlyRate = o.optDouble("hourlyRate", 0.0).coerceAtLeast(0.0), note = o.optString("note", ""),
        status = o.optString("status", PersonnelAssignment.STATUS_PLANNED),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun ratesObject(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_LABOR_RATES, "{}") ?: "{}") }.getOrElse { JSONObject() }

    private fun configuredRatesFromPrefs(): List<Pair<String, Double>> {
        val rates = ratesObject()
        val out = mutableListOf<Pair<String, Double>>()
        val keys = rates.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val row = rates.optJSONObject(key) ?: continue
            out += row.optString("name", key) to row.optDouble("rate", 0.0).coerceAtLeast(0.0)
        }
        return out.sortedBy { it.first.lowercase(Locale.ITALY) }
    }

    private fun hourlyRateFromPrefs(collaborator: String): Double {
        val key = collaboratorKey(collaborator)
        if (key.isBlank()) return 0.0
        return ratesObject().optJSONObject(key)?.optDouble("rate", 0.0)?.coerceAtLeast(0.0) ?: 0.0
    }

    private fun setRateInPrefs(collaborator: String, value: Double) {
        val name = collaborator.trim().ifBlank { "Milan" }
        val key = collaboratorKey(name)
        val rates = ratesObject()
        rates.put(key, JSONObject().put("name", name).put("rate", value.coerceAtLeast(0.0)))
        prefs.edit().putString(KEY_LABOR_RATES, rates.toString()).apply()
    }

    private fun collaboratorKey(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun legacyHourlyCost(): Double = prefs.getString(KEY_HOURLY_COST_LEGACY, "0")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    private fun migrateLegacyRatePreference() {
        val legacy = legacyHourlyCost()
        if (legacy > 0.0 && hourlyRateFromPrefs("Milan") <= 0.0) setRateInPrefs("Milan", legacy)
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        private const val DB_NAME = "ge360_giornata.db"
        private const val DB_VERSION = 4
        private const val PERSONNEL_TABLE = "personnel"
        private const val ASSIGNMENT_TABLE = "personnel_assignments"
        private const val STANDARD_DAY_MINUTES = 480
        private const val PREFS = "ge360_giornata_settings"
        private const val KEY_LABOR_RATES = "labor_rates_json"
        private const val KEY_HOURLY_COST_LEGACY = "hourly_cost"
    }
}
