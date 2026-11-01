package it.edilmilan.ge360.cantieri

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import kotlin.math.roundToInt


data class JobsiteRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var code: String = "",
    var clientId: Long = 0,
    var clientGe360Id: String = "",
    var clientName: String = "",
    var title: String = "",
    var address: String = "",
    var workType: String = "",
    var status: String = STATUS_INSPECTION,
    var startDate: String = "",
    var targetEndDate: String = "",
    var notes: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun displayTitle(): String = title.trim().ifEmpty {
        address.trim().ifEmpty { clientName.trim().ifEmpty { code } }
    }

    companion object {
        const val STATUS_INSPECTION = "Sopralluogo"
    }
}

data class JobsiteProgressItem(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var phase: String = "",
    var title: String = "",
    var weight: Int = 10,
    var progressPercent: Int = 0,
    var note: String = "",
    var sortOrder: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class JobsiteProgressSummary(
    val percent: Int,
    val totalWeight: Int,
    val completedWeight: Double,
    val items: List<JobsiteProgressItem>
)

data class JobsiteMilestone(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var title: String = "SAL / Acconto",
    var amount: Double = 0.0,
    var dueDate: String = "",
    var status: String = STATUS_PLANNED,
    var note: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PLANNED = "Pianificato"
        const val STATUS_TO_REQUEST = "Da richiedere"
        const val STATUS_REQUESTED = "Richiesto"
        const val STATUS_CLOSED = "Chiuso"
        const val STATUS_CANCELLED = "Annullato"
        val STATUSES = listOf(STATUS_PLANNED, STATUS_TO_REQUEST, STATUS_REQUESTED, STATUS_CLOSED, STATUS_CANCELLED)
    }
}

class JobsiteStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE jobsites (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                code TEXT NOT NULL UNIQUE,
                client_id INTEGER NOT NULL DEFAULT 0,
                client_ge360_id TEXT NOT NULL DEFAULT '',
                client_name TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                work_type TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Sopralluogo',
                start_date TEXT NOT NULL DEFAULT '',
                target_end_date TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_jobsites_status ON jobsites(status, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_jobsites_client ON jobsites(client_id, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_jobsites_code ON jobsites(code)")
        createControlTables(db)
        createProgressHistoryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createControlTables(db)
        if (oldVersion < 3) createProgressHistoryTable(db)
    }

    private fun createControlTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $PROGRESS_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                phase TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                weight INTEGER NOT NULL DEFAULT 10,
                progress_percent INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_job_progress_job ON $PROGRESS_TABLE(jobsite_id, sort_order, id)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $MILESTONE_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                title TEXT NOT NULL,
                amount REAL NOT NULL DEFAULT 0,
                due_date TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Pianificato',
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_job_milestone_job ON $MILESTONE_TABLE(jobsite_id, due_date, updated_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_job_milestone_status ON $MILESTONE_TABLE(status, due_date)")
    }

    fun list(query: String = "", status: String? = null): List<JobsiteRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        val q = query.trim()
        if (q.isNotEmpty()) {
            clauses += "(code LIKE ? OR client_name LIKE ? OR title LIKE ? OR address LIKE ? OR work_type LIKE ? OR notes LIKE ?)"
            val like = "%$q%"
            repeat(6) { args += like }
        }
        if (!status.isNullOrBlank() && status != "Tutti") {
            clauses += "status=?"
            args += status
        }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val result = mutableListOf<JobsiteRecord>()
        readableDatabase.query(
            TABLE, null, where, args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null, null, "CASE WHEN status='In corso' THEN 0 WHEN status='Programmato' THEN 1 WHEN status='Sopralluogo' THEN 2 ELSE 3 END, updated_at DESC"
        ).use { cursor -> while (cursor.moveToNext()) result += fromCursor(cursor) }
        return result
    }

    fun get(id: Long): JobsiteRecord? = readableDatabase.query(
        TABLE, null, "id=?", arrayOf(id.toString()), null, null, null, "1"
    ).use { if (it.moveToFirst()) fromCursor(it) else null }

    fun getByGe360Id(ge360Id: String): JobsiteRecord? {
        if (ge360Id.isBlank()) return null
        return readableDatabase.query(TABLE, null, "ge360_id=?", arrayOf(ge360Id.trim()), null, null, null, "1")
            .use { if (it.moveToFirst()) fromCursor(it) else null }
    }

    fun getByCode(code: String): JobsiteRecord? {
        if (code.isBlank()) return null
        return readableDatabase.query(TABLE, null, "code=?", arrayOf(code.trim()), null, null, null, "1")
            .use { if (it.moveToFirst()) fromCursor(it) else null }
    }

    fun forClient(clientId: Long): List<JobsiteRecord> {
        val result = mutableListOf<JobsiteRecord>()
        readableDatabase.query(TABLE, null, "client_id=?", arrayOf(clientId.toString()), null, null, "updated_at DESC")
            .use { cursor -> while (cursor.moveToNext()) result += fromCursor(cursor) }
        return result
    }

    fun save(record: JobsiteRecord): Long {
        val now = System.currentTimeMillis()
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.code.isBlank()) record.code = nextCode()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        val values = toValues(record)
        return if (record.id > 0) {
            writableDatabase.update(TABLE, values, "id=?", arrayOf(record.id.toString()))
            record.id
        } else {
            writableDatabase.insertOrThrow(TABLE, null, values).also { record.id = it }
        }
    }

    fun delete(id: Long) {
        val job = get(id)
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (job != null) {
                db.delete(PROGRESS_TABLE, "jobsite_id=?", arrayOf(job.ge360Id))
                db.delete(PROGRESS_SNAPSHOT_TABLE, "jobsite_id=?", arrayOf(job.ge360Id))
                db.delete(MILESTONE_TABLE, "jobsite_id=?", arrayOf(job.ge360Id))
            }
            db.delete(TABLE, "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun setStatus(id: Long, status: String) {
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "id=?", arrayOf(id.toString()))
    }

    fun counts(): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM $TABLE GROUP BY status", null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getInt(1)
        }
        return out
    }

    // ---- Avanzamento lavori -------------------------------------------------

    fun progressItems(jobsiteId: String): List<JobsiteProgressItem> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<JobsiteProgressItem>()
        readableDatabase.query(PROGRESS_TABLE, null, "jobsite_id=?", arrayOf(jobsiteId), null, null, "sort_order ASC, id ASC")
            .use { c -> while (c.moveToNext()) out += progressFromCursor(c) }
        return out
    }

    fun progressSummary(jobsiteId: String): JobsiteProgressSummary? {
        val items = progressItems(jobsiteId)
        if (items.isEmpty()) return null
        val totalWeight = items.sumOf { it.weight.coerceAtLeast(0) }
        if (totalWeight <= 0) return null
        val completedWeight = items.sumOf { it.weight.coerceAtLeast(0) * it.progressPercent.coerceIn(0, 100) / 100.0 }
        return JobsiteProgressSummary(
            percent = ((completedWeight / totalWeight) * 100.0).roundToInt().coerceIn(0, 100),
            totalWeight = totalWeight,
            completedWeight = completedWeight,
            items = items
        )
    }

    fun ensureProgressTemplate(job: JobsiteRecord): List<JobsiteProgressItem> {
        val existing = progressItems(job.ge360Id)
        if (existing.isNotEmpty()) return existing
        templateFor(job.workType).forEachIndexed { index, spec ->
            saveProgressItem(
                JobsiteProgressItem(
                    jobsiteId = job.ge360Id,
                    phase = spec.first,
                    title = spec.first,
                    weight = spec.second,
                    sortOrder = index
                )
            )
        }
        return progressItems(job.ge360Id)
    }

    fun saveProgressItem(item: JobsiteProgressItem): Long {
        require(item.jobsiteId.isNotBlank()) { "Cantiere mancante" }
        require(item.title.isNotBlank()) { "Nome fase mancante" }
        val now = System.currentTimeMillis()
        if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
        if (item.createdAt <= 0) item.createdAt = now
        item.updatedAt = now
        item.weight = item.weight.coerceIn(1, 1000)
        item.progressPercent = item.progressPercent.coerceIn(0, 100)
        val existing = progressByGe360Id(item.ge360Id)
        if (item.id <= 0 && existing != null) {
            item.id = existing.id
            item.createdAt = existing.createdAt
        }
        val values = progressValues(item)
        return if (item.id > 0) {
            writableDatabase.update(PROGRESS_TABLE, values, "id=?", arrayOf(item.id.toString()))
            item.id
        } else writableDatabase.insertOrThrow(PROGRESS_TABLE, null, values).also { item.id = it }
    }

    fun setProgress(itemId: Long, percent: Int) {
        writableDatabase.update(
            PROGRESS_TABLE,
            ContentValues().apply {
                put("progress_percent", percent.coerceIn(0, 100))
                put("updated_at", System.currentTimeMillis())
            },
            "id=?",
            arrayOf(itemId.toString())
        )
    }

    fun deleteProgressItem(itemId: Long) {
        writableDatabase.delete(PROGRESS_TABLE, "id=?", arrayOf(itemId.toString()))
    }

    // ---- SAL / Acconti ------------------------------------------------------

    fun milestones(jobsiteId: String): List<JobsiteMilestone> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<JobsiteMilestone>()
        readableDatabase.query(
            MILESTONE_TABLE, null, "jobsite_id=?", arrayOf(jobsiteId), null, null,
            "CASE WHEN status='Da richiedere' THEN 0 WHEN status='Richiesto' THEN 1 WHEN status='Pianificato' THEN 2 ELSE 3 END, CASE WHEN due_date='' THEN 1 ELSE 0 END, due_date ASC, updated_at DESC"
        ).use { c -> while (c.moveToNext()) out += milestoneFromCursor(c) }
        return out
    }

    fun saveMilestone(row: JobsiteMilestone): Long {
        require(row.jobsiteId.isNotBlank()) { "Cantiere mancante" }
        require(row.title.isNotBlank()) { "Titolo SAL mancante" }
        val now = System.currentTimeMillis()
        if (row.ge360Id.isBlank()) row.ge360Id = UUID.randomUUID().toString()
        if (row.createdAt <= 0) row.createdAt = now
        row.updatedAt = now
        row.amount = row.amount.coerceAtLeast(0.0)
        if (row.status !in JobsiteMilestone.STATUSES) row.status = JobsiteMilestone.STATUS_PLANNED
        val existing = milestoneByGe360Id(row.ge360Id)
        if (row.id <= 0 && existing != null) {
            row.id = existing.id
            row.createdAt = existing.createdAt
        }
        val values = milestoneValues(row)
        return if (row.id > 0) {
            writableDatabase.update(MILESTONE_TABLE, values, "id=?", arrayOf(row.id.toString()))
            row.id
        } else writableDatabase.insertOrThrow(MILESTONE_TABLE, null, values).also { row.id = it }
    }

    fun setMilestoneStatus(id: Long, status: String) {
        if (status !in JobsiteMilestone.STATUSES) return
        writableDatabase.update(
            MILESTONE_TABLE,
            ContentValues().apply { put("status", status); put("updated_at", System.currentTimeMillis()) },
            "id=?",
            arrayOf(id.toString())
        )
    }

    fun deleteMilestone(id: Long) {
        writableDatabase.delete(MILESTONE_TABLE, "id=?", arrayOf(id.toString()))
    }

    // ---- Cronologia rilevazioni avanzamento -------------------------------

    private fun createProgressHistoryTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $PROGRESS_SNAPSHOT_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                captured_at INTEGER NOT NULL,
                observed_date TEXT NOT NULL,
                percent INTEGER NOT NULL,
                total_weight INTEGER NOT NULL,
                phase_count INTEGER NOT NULL,
                phase_fingerprint TEXT NOT NULL,
                phases_json TEXT NOT NULL,
                target_end_date TEXT NOT NULL DEFAULT '',
                jobsite_status TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_progress_snapshots_job ON $PROGRESS_SNAPSHOT_TABLE(jobsite_id, captured_at DESC, id DESC)")
    }

    /**
     * Explicit user action only: captures current phases at the real device time.
     * Never automatically backfills the past, creates pseudo-events or changes phases.
     */
    fun captureProgressSnapshot(jobsiteId: String, note: String = ""): JobsiteProgressSnapshot {
        require(jobsiteId.isNotBlank()) { "Cantiere obbligatorio" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val job = getByGe360Id(jobsiteId) ?: error("Cantiere non trovato")
            val phases = progressItems(jobsiteId)
            require(phases.isNotEmpty()) { "Nessuna fase da rilevare" }
            val totalWeight = phases.sumOf { it.weight.coerceAtLeast(0) }
            require(totalWeight > 0) { "Pesi delle fasi non validi" }
            val weighted = phases.sumOf {
                it.weight.coerceAtLeast(0) * it.progressPercent.coerceIn(0, 100) / 100.0
            }
            val percent = ((weighted / totalWeight) * 100.0).roundToInt().coerceIn(0, 100)
            val phasesJson = JSONArray().apply {
                phases.forEach { phase ->
                    put(JSONObject()
                        .put("ge360Id", phase.ge360Id)
                        .put("title", phase.title)
                        .put("weight", phase.weight)
                        .put("percent", phase.progressPercent)
                        .put("updatedAt", phase.updatedAt)
                    )
                }
            }.toString()
            // Same fingerprint as the forecasting gate: immutable phase identity and weight.
            val fingerprint = ProgressScenarioForecast.fingerprint(phases)
            val result = JobsiteProgressSnapshot(
                ge360Id = UUID.randomUUID().toString(),
                jobsiteId = jobsiteId,
                capturedAt = System.currentTimeMillis(),
                observedDate = LocalDate.now().toString(),
                percent = percent,
                totalWeight = totalWeight,
                phaseCount = phases.size,
                phaseFingerprint = fingerprint,
                phasesJson = phasesJson,
                targetEndDate = job.targetEndDate,
                jobsiteStatus = job.status,
                note = note.trim().take(500)
            )
            insertProgressSnapshot(db, result)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun progressSnapshots(jobsiteId: String, limit: Int = 200): List<JobsiteProgressSnapshot> {
        if (jobsiteId.isBlank()) return emptyList()
        val result = mutableListOf<JobsiteProgressSnapshot>()
        readableDatabase.query(
            PROGRESS_SNAPSHOT_TABLE, null, "jobsite_id=?", arrayOf(jobsiteId),
            null, null, "captured_at DESC, id DESC", limit.coerceIn(1, 2000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result += progressSnapshotFromCursor(cursor)
        }
        return result
    }

    fun progressTrend(jobsiteId: String): JobsiteProgressTrend =
        JobsiteProgressHistory.analyze(progressSnapshots(jobsiteId))

    /**
     * Read-only, conditional scenarios. No DB writes, auto-created measurements
     * or changes to the customer's contractual deadline.
     */
    fun progressForecast(
        jobsiteId: String,
        hasUnresolvedBlockers: Boolean = false
    ): ProgressFinishAssessment {
        val job = getByGe360Id(jobsiteId) ?: error("Cantiere non trovato")
        return ProgressScenarioForecast.evaluate(
            snapshots = progressSnapshots(jobsiteId, 2000),
            current = progressSummary(jobsiteId),
            targetDateIso = job.targetEndDate,
            hasUnresolvedBlockers = hasUnresolvedBlockers,
            isOperational = job.status != STATUS_INSPECTION && job.status != STATUS_ESTIMATE
        )
    }

    private fun allProgressSnapshots(): List<JobsiteProgressSnapshot> {
        val result = mutableListOf<JobsiteProgressSnapshot>()
        readableDatabase.query(PROGRESS_SNAPSHOT_TABLE, null, null, null,
            null, null, "jobsite_id, captured_at ASC, id ASC").use { cursor ->
            while (cursor.moveToNext()) result += progressSnapshotFromCursor(cursor)
        }
        return result
    }

    private fun progressSnapshotByGe360Id(ge360Id: String): JobsiteProgressSnapshot? =
        readableDatabase.query(PROGRESS_SNAPSHOT_TABLE, null, "ge360_id=?",
            arrayOf(ge360Id), null, null, null, "1").use {
            if (it.moveToFirst()) progressSnapshotFromCursor(it) else null
        }

    private fun insertProgressSnapshot(db: SQLiteDatabase, row: JobsiteProgressSnapshot) {
        require(row.ge360Id.isNotBlank() && row.jobsiteId.isNotBlank()) { "UUID rilevazione non valido" }
        require(row.capturedAt > 0L && row.percent in 0..100 &&
            row.phaseCount > 0 && row.totalWeight > 0 &&
            row.phaseFingerprint.isNotBlank() &&
            runCatching { LocalDate.parse(row.observedDate) }.isSuccess
        ) { "Rilevazione avanzamento non valida" }
        require(getByGe360Id(row.jobsiteId) != null) { "Cantiere rilevazione assente" }
        val current = progressSnapshotByGe360Id(row.ge360Id)
        if (current != null) {
            require(current == row) { "Conflitto rilevazione esistente: nessuna sovrascrittura silenziosa" }
            return // Same UUID and same content: idempotent merge.
        }
        db.insertOrThrow(PROGRESS_SNAPSHOT_TABLE, null, ContentValues().apply {
            put("ge360_id", row.ge360Id); put("jobsite_id", row.jobsiteId)
            put("captured_at", row.capturedAt); put("observed_date", row.observedDate)
            put("percent", row.percent); put("total_weight", row.totalWeight)
            put("phase_count", row.phaseCount); put("phase_fingerprint", row.phaseFingerprint)
            put("phases_json", row.phasesJson); put("target_end_date", row.targetEndDate)
            put("jobsite_status", row.jobsiteStatus); put("note", row.note)
        })
    }

    private fun progressSnapshotFromCursor(c: Cursor) = JobsiteProgressSnapshot(
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        capturedAt = c.getLong(c.getColumnIndexOrThrow("captured_at")),
        observedDate = c.getString(c.getColumnIndexOrThrow("observed_date")),
        percent = c.getInt(c.getColumnIndexOrThrow("percent")),
        totalWeight = c.getInt(c.getColumnIndexOrThrow("total_weight")),
        phaseCount = c.getInt(c.getColumnIndexOrThrow("phase_count")),
        phaseFingerprint = c.getString(c.getColumnIndexOrThrow("phase_fingerprint")),
        phasesJson = c.getString(c.getColumnIndexOrThrow("phases_json")),
        targetEndDate = c.getString(c.getColumnIndexOrThrow("target_end_date")),
        jobsiteStatus = c.getString(c.getColumnIndexOrThrow("jobsite_status")),
        note = c.getString(c.getColumnIndexOrThrow("note"))
    )

    private fun progressSnapshotToJson(s: JobsiteProgressSnapshot) = JSONObject().apply {
        put("ge360Id", s.ge360Id); put("jobsiteId", s.jobsiteId)
        put("capturedAt", s.capturedAt); put("observedDate", s.observedDate)
        put("percent", s.percent); put("totalWeight", s.totalWeight)
        put("phaseCount", s.phaseCount); put("phaseFingerprint", s.phaseFingerprint)
        put("phasesJson", s.phasesJson); put("targetEndDate", s.targetEndDate)
        put("jobsiteStatus", s.jobsiteStatus); put("note", s.note)
    }

    private fun progressSnapshotFromJson(o: JSONObject) = JobsiteProgressSnapshot(
        ge360Id = o.getString("ge360Id"),
        jobsiteId = o.getString("jobsiteId"),
        capturedAt = o.getLong("capturedAt"),
        observedDate = o.getString("observedDate"),
        percent = o.getInt("percent"),
        totalWeight = o.getInt("totalWeight"),
        phaseCount = o.getInt("phaseCount"),
        phaseFingerprint = o.getString("phaseFingerprint"),
        phasesJson = o.getString("phasesJson"),
        targetEndDate = o.optString("targetEndDate", ""),
        jobsiteStatus = o.optString("jobsiteStatus", ""),
        note = o.optString("note", "")
    )

    fun exportJson(forSync: Boolean = false): String {
        val root = JSONObject()
            .put("app", "GE360")
            .put("module", "cantieri")
            .put("formatVersion", if (forSync) 2 else 3)
            .put("exportedAt", System.currentTimeMillis())

        val jobsRows = JSONArray()
        list().forEach { jobsRows.put(toJson(it)) }
        root.put("jobsites", jobsRows)

        val progressRows = JSONArray()
        allProgressItems().forEach { progressRows.put(progressToJson(it)) }
        root.put("progressItems", progressRows)

        val milestoneRows = JSONArray()
        allMilestones().forEach { milestoneRows.put(milestoneToJson(it)) }
        root.put("milestones", milestoneRows)
        // Data Hub schema has no snapshot lifecycle/tombstone contract yet.
        // Preserve snapshots in full ZIP backup, but do not send them to Debian.
        if (!forSync) root.put("progressSnapshots", JSONArray().apply {
            allProgressSnapshots().forEach { put(progressSnapshotToJson(it)) }
        })
        return root.toString(2)
    }

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        val rows = root.getJSONArray("jobsites")
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete(PROGRESS_SNAPSHOT_TABLE, null, null)
                db.delete(PROGRESS_TABLE, null, null)
                db.delete(MILESTONE_TABLE, null, null)
                db.delete(TABLE, null, null)
            }
            for (index in 0 until rows.length()) {
                val incoming = fromJson(rows.getJSONObject(index))
                val existing = getByGe360Id(incoming.ge360Id)
                if (existing != null) {
                    incoming.id = existing.id
                    incoming.createdAt = existing.createdAt
                    if (incoming.code.isBlank()) incoming.code = existing.code
                } else {
                    val owner = getByCode(incoming.code)
                    if (owner != null && owner.ge360Id != incoming.ge360Id) incoming.code = nextCode()
                }
                save(incoming)
                imported++
            }
            val progress = root.optJSONArray("progressItems")
            if (progress != null) {
                for (index in 0 until progress.length()) saveProgressItem(progressFromJson(progress.getJSONObject(index)))
            }
            val milestones = root.optJSONArray("milestones")
            if (milestones != null) {
                for (index in 0 until milestones.length()) saveMilestone(milestoneFromJson(milestones.getJSONObject(index)))
            }
            val snapshots = root.optJSONArray("progressSnapshots")
            if (snapshots != null) {
                for (index in 0 until snapshots.length()) {
                    insertProgressSnapshot(db, progressSnapshotFromJson(snapshots.getJSONObject(index)))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return imported
    }

    private fun nextCode(): String {
        val year = SimpleDateFormat("yyyy", Locale.ITALY).format(Date())
        val prefix = "CANT-$year-"
        var max = 0
        readableDatabase.rawQuery("SELECT code FROM $TABLE WHERE code LIKE ?", arrayOf("$prefix%" )).use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0).removePrefix(prefix).toIntOrNull() ?: 0
                if (value > max) max = value
            }
        }
        var candidate = prefix + (max + 1).toString().padStart(3, '0')
        while (getByCode(candidate) != null) {
            max++
            candidate = prefix + (max + 1).toString().padStart(3, '0')
        }
        return candidate
    }

    private fun templateFor(workType: String): List<Pair<String, Int>> = when (workType.trim().lowercase(Locale.ITALY)) {
        "bagno" -> listOf(
            "Protezioni e preparazione" to 5,
            "Demolizioni e sgombero" to 15,
            "Impianti" to 20,
            "Ripristini e fondi" to 12,
            "Impermeabilizzazione" to 10,
            "Posa rivestimenti e pavimenti" to 20,
            "Finiture" to 13,
            "Consegna" to 5
        )
        "cucina" -> listOf(
            "Preparazione" to 8, "Demolizioni" to 12, "Impianti" to 22, "Ripristini" to 15,
            "Rivestimenti e pavimento" to 23, "Finiture" to 15, "Consegna" to 5
        )
        "pavimenti" -> listOf(
            "Preparazione e protezioni" to 8, "Rimozioni" to 12, "Preparazione fondo" to 18,
            "Posa pavimento" to 45, "Battiscopa e finiture" to 12, "Consegna" to 5
        )
        "cartongesso" -> listOf(
            "Tracciamento e preparazione" to 10, "Struttura" to 22, "Lastre" to 28,
            "Stuccatura e rasatura" to 22, "Finitura" to 13, "Consegna" to 5
        )
        "tinteggiatura" -> listOf(
            "Protezioni" to 10, "Preparazione fondi" to 25, "Pittura" to 45, "Ritocchi" to 15, "Consegna" to 5
        )
        "terrazza" -> listOf(
            "Protezioni" to 8, "Demolizioni" to 12, "Preparazione supporto" to 18, "Impermeabilizzazione" to 20,
            "Posa" to 27, "Finiture" to 10, "Consegna" to 5
        )
        "ristrutturazione" -> listOf(
            "Preparazione" to 5, "Demolizioni" to 10, "Impianti" to 20, "Opere murarie" to 15,
            "Fondi e supporti" to 15, "Posa e montaggi" to 20, "Finiture" to 10, "Consegna" to 5
        )
        else -> listOf(
            "Preparazione" to 10, "Lavorazioni iniziali" to 20, "Lavorazione principale" to 40,
            "Finiture" to 20, "Consegna" to 10
        )
    }

    private fun toValues(r: JobsiteRecord) = ContentValues().apply {
        put("ge360_id", r.ge360Id.trim()); put("code", r.code.trim()); put("client_id", r.clientId)
        put("client_ge360_id", r.clientGe360Id.trim()); put("client_name", r.clientName.trim())
        put("title", r.title.trim()); put("address", r.address.trim()); put("work_type", r.workType.trim())
        put("status", r.status.trim().ifEmpty { JobsiteRecord.STATUS_INSPECTION }); put("start_date", r.startDate.trim())
        put("target_end_date", r.targetEndDate.trim()); put("notes", r.notes.trim()); put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun progressValues(r: JobsiteProgressItem) = ContentValues().apply {
        put("ge360_id", r.ge360Id); put("jobsite_id", r.jobsiteId); put("phase", r.phase); put("title", r.title)
        put("weight", r.weight); put("progress_percent", r.progressPercent); put("note", r.note); put("sort_order", r.sortOrder)
        put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun milestoneValues(r: JobsiteMilestone) = ContentValues().apply {
        put("ge360_id", r.ge360Id); put("jobsite_id", r.jobsiteId); put("title", r.title); put("amount", r.amount)
        put("due_date", r.dueDate); put("status", r.status); put("note", r.note); put("created_at", r.createdAt); put("updated_at", r.updatedAt)
    }

    private fun fromCursor(c: Cursor) = JobsiteRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        code = c.getString(c.getColumnIndexOrThrow("code")), clientId = c.getLong(c.getColumnIndexOrThrow("client_id")),
        clientGe360Id = c.getString(c.getColumnIndexOrThrow("client_ge360_id")), clientName = c.getString(c.getColumnIndexOrThrow("client_name")),
        title = c.getString(c.getColumnIndexOrThrow("title")), address = c.getString(c.getColumnIndexOrThrow("address")),
        workType = c.getString(c.getColumnIndexOrThrow("work_type")), status = c.getString(c.getColumnIndexOrThrow("status")),
        startDate = c.getString(c.getColumnIndexOrThrow("start_date")), targetEndDate = c.getString(c.getColumnIndexOrThrow("target_end_date")),
        notes = c.getString(c.getColumnIndexOrThrow("notes")), createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun progressFromCursor(c: Cursor) = JobsiteProgressItem(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")), phase = c.getString(c.getColumnIndexOrThrow("phase")),
        title = c.getString(c.getColumnIndexOrThrow("title")), weight = c.getInt(c.getColumnIndexOrThrow("weight")),
        progressPercent = c.getInt(c.getColumnIndexOrThrow("progress_percent")), note = c.getString(c.getColumnIndexOrThrow("note")),
        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")), createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun milestoneFromCursor(c: Cursor) = JobsiteMilestone(
        id = c.getLong(c.getColumnIndexOrThrow("id")), ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")), title = c.getString(c.getColumnIndexOrThrow("title")),
        amount = c.getDouble(c.getColumnIndexOrThrow("amount")), dueDate = c.getString(c.getColumnIndexOrThrow("due_date")),
        status = c.getString(c.getColumnIndexOrThrow("status")), note = c.getString(c.getColumnIndexOrThrow("note")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")), updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun progressByGe360Id(id: String): JobsiteProgressItem? = readableDatabase.query(
        PROGRESS_TABLE, null, "ge360_id=?", arrayOf(id), null, null, null, "1"
    ).use { if (it.moveToFirst()) progressFromCursor(it) else null }

    private fun milestoneByGe360Id(id: String): JobsiteMilestone? = readableDatabase.query(
        MILESTONE_TABLE, null, "ge360_id=?", arrayOf(id), null, null, null, "1"
    ).use { if (it.moveToFirst()) milestoneFromCursor(it) else null }

    private fun allProgressItems(): List<JobsiteProgressItem> {
        val out = mutableListOf<JobsiteProgressItem>()
        readableDatabase.query(PROGRESS_TABLE, null, null, null, null, null, "jobsite_id, sort_order, id")
            .use { c -> while (c.moveToNext()) out += progressFromCursor(c) }
        return out
    }

    private fun allMilestones(): List<JobsiteMilestone> {
        val out = mutableListOf<JobsiteMilestone>()
        readableDatabase.query(MILESTONE_TABLE, null, null, null, null, null, "jobsite_id, due_date, updated_at")
            .use { c -> while (c.moveToNext()) out += milestoneFromCursor(c) }
        return out
    }

    private fun toJson(r: JobsiteRecord) = JSONObject().apply {
        put("ge360Id", r.ge360Id); put("code", r.code); put("clientId", r.clientId); put("clientGe360Id", r.clientGe360Id)
        put("clientName", r.clientName); put("title", r.title); put("address", r.address); put("workType", r.workType)
        put("status", r.status); put("startDate", r.startDate); put("targetEndDate", r.targetEndDate); put("notes", r.notes)
        put("createdAt", r.createdAt); put("updatedAt", r.updatedAt)
    }

    private fun progressToJson(r: JobsiteProgressItem) = JSONObject().apply {
        put("ge360Id", r.ge360Id); put("jobsiteId", r.jobsiteId); put("phase", r.phase); put("title", r.title)
        put("weight", r.weight); put("progressPercent", r.progressPercent); put("note", r.note); put("sortOrder", r.sortOrder)
        put("createdAt", r.createdAt); put("updatedAt", r.updatedAt)
    }

    private fun milestoneToJson(r: JobsiteMilestone) = JSONObject().apply {
        put("ge360Id", r.ge360Id); put("jobsiteId", r.jobsiteId); put("title", r.title); put("amount", r.amount)
        put("dueDate", r.dueDate); put("status", r.status); put("note", r.note); put("createdAt", r.createdAt); put("updatedAt", r.updatedAt)
    }

    private fun fromJson(o: JSONObject) = JobsiteRecord(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), code = o.optString("code", ""),
        clientId = o.optLong("clientId", 0), clientGe360Id = o.optString("clientGe360Id", ""), clientName = o.optString("clientName", ""),
        title = o.optString("title", ""), address = o.optString("address", ""), workType = o.optString("workType", ""),
        status = o.optString("status", JobsiteRecord.STATUS_INSPECTION), startDate = o.optString("startDate", ""),
        targetEndDate = o.optString("targetEndDate", ""), notes = o.optString("notes", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun progressFromJson(o: JSONObject) = JobsiteProgressItem(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId", ""),
        phase = o.optString("phase", ""), title = o.optString("title", "Fase"), weight = o.optInt("weight", 10),
        progressPercent = o.optInt("progressPercent", 0), note = o.optString("note", ""), sortOrder = o.optInt("sortOrder", 0),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun milestoneFromJson(o: JSONObject) = JobsiteMilestone(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId", ""),
        title = o.optString("title", "SAL / Acconto"), amount = o.optDouble("amount", 0.0), dueDate = o.optString("dueDate", ""),
        status = o.optString("status", JobsiteMilestone.STATUS_PLANNED), note = o.optString("note", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    companion object {
        const val STATUS_INSPECTION = "Sopralluogo"
        const val STATUS_ESTIMATE = "Preventivo"
        const val STATUS_SCHEDULED = "Programmato"
        const val STATUS_ACTIVE = "In corso"
        const val STATUS_PAUSED = "Sospeso"
        const val STATUS_COMPLETED = "Completato"
        const val STATUS_ARCHIVED = "Archiviato"

        val STATUSES = listOf(STATUS_INSPECTION, STATUS_ESTIMATE, STATUS_SCHEDULED, STATUS_ACTIVE, STATUS_PAUSED, STATUS_COMPLETED, STATUS_ARCHIVED)
        val WORK_TYPES = listOf("Bagno", "Cucina", "Ristrutturazione", "Pavimenti", "Cartongesso", "Tinteggiatura", "Terrazza", "Opere murarie", "Manutenzione", "Altro")

        private const val TABLE = "jobsites"
        private const val PROGRESS_TABLE = "jobsite_progress_items"
        private const val MILESTONE_TABLE = "jobsite_milestones"
        private const val PROGRESS_SNAPSHOT_TABLE = "jobsite_progress_snapshots"
        private const val DB_NAME = "ge360_cantieri.db"
        private const val DB_VERSION = 3
    }
}
