package it.edilmilan.ge360.centrocantiere

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.preventivi.EstimateRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class WorkflowTask(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var workType: String = "",
    var templateId: String = "",
    var phase: String = "Lavorazioni",
    var sortOrder: Int = 0,
    var title: String = "",
    var normalizedTitle: String = "",
    var detail: String = "",
    var status: String = WorkflowStatuses.TODO,
    var owner: String = "",
    var source: String = WorkflowSources.MANUAL,
    var workPackId: String = "",
    var photoRequired: Boolean = false,
    var documentRequired: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class JobsiteNote(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var text: String = "",
    var pinned: Boolean = false,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class WorkflowEvent(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var eventType: String = "INFO",
    var title: String = "",
    var detail: String = "",
    var entityId: String = "",
    var createdAt: Long = System.currentTimeMillis()
)

data class LearningSuggestion(
    var id: Long = 0,
    var ge360Id: String = "",
    var workType: String = "",
    var normalizedTitle: String = "",
    var title: String = "",
    var occurrences: Int = 0,
    var status: String = LearningStatuses.PROPOSED,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class WorkflowSummary(
    val total: Int,
    val todo: Int,
    val inProgress: Int,
    val done: Int,
    val blocked: Int,
    val notNeeded: Int
) {
    val relevant: Int get() = (total - notNeeded).coerceAtLeast(0)
    val completionPercent: Int get() = if (relevant <= 0) 0 else ((done * 100.0) / relevant).toInt().coerceIn(0, 100)
}

object WorkflowStatuses {
    const val TODO = "Da fare"
    const val IN_PROGRESS = "In corso"
    const val DONE = "Fatto"
    const val BLOCKED = "Bloccato"
    const val NOT_NEEDED = "Non necessario"
    val ALL = listOf(TODO, IN_PROGRESS, DONE, BLOCKED, NOT_NEEDED)
}

object WorkflowSources {
    const val STANDARD = "STANDARD"
    const val ESTIMATE = "PREVENTIVO"
    const val MANUAL = "MANUALE"
    const val LEARNED = "APPRESO"
}

object LearningStatuses {
    const val PROPOSED = "PROPOSTA"
    const val APPROVED = "APPROVATA"
    const val DISMISSED = "IGNORATA"
}

class JobsiteWorkflowStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE workflow_tasks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                work_type TEXT NOT NULL DEFAULT '',
                template_id TEXT NOT NULL DEFAULT '',
                phase TEXT NOT NULL DEFAULT 'Lavorazioni',
                sort_order INTEGER NOT NULL DEFAULT 0,
                title TEXT NOT NULL,
                normalized_title TEXT NOT NULL DEFAULT '',
                detail TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Da fare',
                owner TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'MANUALE',
                work_pack_id TEXT NOT NULL DEFAULT '',
                photo_required INTEGER NOT NULL DEFAULT 0,
                document_required INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE jobsite_notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                text TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE workflow_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                event_type TEXT NOT NULL DEFAULT 'INFO',
                title TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                entity_id TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE learning_suggestions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                work_type TEXT NOT NULL DEFAULT '',
                normalized_title TEXT NOT NULL,
                title TEXT NOT NULL,
                occurrences INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'PROPOSTA',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(work_type, normalized_title)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_workflow_job ON workflow_tasks(jobsite_id, sort_order, updated_at)")
        db.execSQL("CREATE INDEX idx_workflow_learning ON workflow_tasks(work_type, source, normalized_title)")
        db.execSQL("CREATE INDEX idx_notes_job ON jobsite_notes(jobsite_id, pinned DESC, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_events_job ON workflow_events(jobsite_id, created_at DESC)")
        db.execSQL("CREATE INDEX idx_learning_status ON learning_suggestions(status, occurrences DESC)")
        createTaskPlanTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTaskPlanTable(db)
    }

    private fun createTaskPlanTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS workflow_task_plans (
                task_ge360_id TEXT PRIMARY KEY NOT NULL,
                jobsite_id TEXT NOT NULL,
                planned_workdays INTEGER NOT NULL DEFAULT 0,
                predecessor_ids_json TEXT NOT NULL DEFAULT '[]',
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_workflow_plan_job ON workflow_task_plans(jobsite_id, updated_at DESC)")
    }

    fun tasks(jobsiteId: String): List<WorkflowTask> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<WorkflowTask>()
        readableDatabase.query(
            TASKS, null, "jobsite_id=?", arrayOf(jobsiteId), null, null,
            "sort_order ASC, created_at ASC"
        ).use { c -> while (c.moveToNext()) out += taskFromCursor(c) }
        return out
    }

    fun summary(jobsiteId: String): WorkflowSummary {
        val rows = tasks(jobsiteId)
        return WorkflowSummary(
            total = rows.size,
            todo = rows.count { it.status == WorkflowStatuses.TODO },
            inProgress = rows.count { it.status == WorkflowStatuses.IN_PROGRESS },
            done = rows.count { it.status == WorkflowStatuses.DONE },
            blocked = rows.count { it.status == WorkflowStatuses.BLOCKED },
            notNeeded = rows.count { it.status == WorkflowStatuses.NOT_NEEDED }
        )
    }

    fun applyTemplate(jobsite: JobsiteRecord, template: WorkflowTemplate): Int {
        val existing = tasks(jobsite.ge360Id).map { it.normalizedTitle }.toMutableSet()
        var order = maxOrder(jobsite.ge360Id)
        var added = 0
        template.tasks.forEach { spec ->
            val normalized = normalize(spec.title)
            if (normalized.isBlank() || normalized in existing) return@forEach
            order += 10
            saveTask(
                WorkflowTask(
                    jobsiteId = jobsite.ge360Id,
                    workType = jobsite.workType,
                    templateId = template.id,
                    phase = spec.phase,
                    sortOrder = order,
                    title = spec.title,
                    normalizedTitle = normalized,
                    detail = spec.detail,
                    source = WorkflowSources.STANDARD,
                    workPackId = spec.workPackId,
                    photoRequired = spec.photoRequired,
                    documentRequired = spec.documentRequired
                ),
                recordChange = false
            )
            existing += normalized
            added++
        }
        if (added > 0) {
            recordEvent(jobsite.ge360Id, "TEMPLATE", "Applicato modello ${template.title}", "$added attività aggiunte")
        }
        return added
    }

    fun applyEstimate(jobsite: JobsiteRecord, estimate: EstimateRecord): Int {
        val existing = tasks(jobsite.ge360Id).map { it.normalizedTitle }.toMutableSet()
        var order = maxOrder(jobsite.ge360Id)
        var added = 0
        estimate.items.filter { it.included && !it.optional }.forEach { item ->
            val spec = WorkflowTemplates.fromEstimateItem(item)
            val normalized = normalize(spec.title)
            if (normalized.isBlank() || normalized in existing) return@forEach
            order += 10
            saveTask(
                WorkflowTask(
                    jobsiteId = jobsite.ge360Id,
                    workType = jobsite.workType,
                    templateId = "preventivo:${estimate.ge360Id}",
                    phase = spec.phase,
                    sortOrder = order,
                    title = spec.title,
                    normalizedTitle = normalized,
                    detail = spec.detail,
                    source = WorkflowSources.ESTIMATE,
                    workPackId = spec.workPackId,
                    photoRequired = spec.photoRequired,
                    documentRequired = spec.documentRequired
                ),
                recordChange = false
            )
            existing += normalized
            added++
        }
        if (added > 0) {
            recordEvent(
                jobsite.ge360Id,
                "ESTIMATE",
                "Checklist aggiornata dal preventivo ${estimate.number}",
                "$added lavorazioni aggiunte senza duplicare quelle già presenti",
                estimate.ge360Id
            )
        }
        return added
    }

    fun addManualTask(jobsite: JobsiteRecord, title: String, detail: String = "", owner: String = ""): WorkflowTask {
        val cleaned = title.trim()
        require(cleaned.isNotBlank()) { "Titolo attività obbligatorio" }
        val task = WorkflowTask(
            jobsiteId = jobsite.ge360Id,
            workType = jobsite.workType,
            phase = "Aggiunte manuali",
            sortOrder = maxOrder(jobsite.ge360Id) + 10,
            title = cleaned,
            normalizedTitle = normalize(cleaned),
            detail = detail.trim(),
            owner = owner.trim(),
            source = WorkflowSources.MANUAL
        )
        saveTask(task)
        observeLearning(task)
        return task
    }

    fun saveTask(task: WorkflowTask, recordChange: Boolean = true): Long {
        val now = System.currentTimeMillis()
        if (task.ge360Id.isBlank()) task.ge360Id = UUID.randomUUID().toString()
        if (task.normalizedTitle.isBlank()) task.normalizedTitle = normalize(task.title)
        if (task.createdAt <= 0) task.createdAt = now
        task.updatedAt = now
        val id = if (task.id > 0) {
            writableDatabase.update(TASKS, taskValues(task), "id=?", arrayOf(task.id.toString()))
            task.id
        } else {
            writableDatabase.insertOrThrow(TASKS, null, taskValues(task)).also { task.id = it }
        }
        if (recordChange) recordEvent(task.jobsiteId, "TASK", task.title, "${task.source} · ${task.status}", task.ge360Id)
        return id
    }

    fun updateStatus(task: WorkflowTask, status: String) {
        require(status in WorkflowStatuses.ALL) { "Stato checklist non valido" }
        if (task.status == status) return
        task.status = status
        task.updatedAt = System.currentTimeMillis()
        writableDatabase.update(TASKS, ContentValues().apply {
            put("status", status)
            put("updated_at", task.updatedAt)
        }, "id=?", arrayOf(task.id.toString()))
        recordEvent(task.jobsiteId, "TASK_STATUS", task.title, status, task.ge360Id)
    }

    fun cycleStatus(task: WorkflowTask) {
        val next = when (task.status) {
            WorkflowStatuses.TODO -> WorkflowStatuses.IN_PROGRESS
            WorkflowStatuses.IN_PROGRESS -> WorkflowStatuses.DONE
            WorkflowStatuses.DONE -> WorkflowStatuses.TODO
            WorkflowStatuses.BLOCKED -> WorkflowStatuses.IN_PROGRESS
            WorkflowStatuses.NOT_NEEDED -> WorkflowStatuses.TODO
            else -> WorkflowStatuses.TODO
        }
        updateStatus(task, next)
    }

    fun deleteTask(task: WorkflowTask) {
        writableDatabase.delete(PLANS, "task_ge360_id=?", arrayOf(task.ge360Id))
        writableDatabase.delete(TASKS, "id=?", arrayOf(task.id.toString()))
        // Incoming dependencies are intentionally NOT auto-rewired:
        // the schedule will expose a missing predecessor for manual correction.
        recordEvent(task.jobsiteId, "TASK_DELETE", "Attività rimossa", task.title, task.ge360Id)
    }

    fun notes(jobsiteId: String): List<JobsiteNote> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<JobsiteNote>()
        readableDatabase.query(NOTES, null, "jobsite_id=?", arrayOf(jobsiteId), null, null, "pinned DESC, updated_at DESC")
            .use { c -> while (c.moveToNext()) out += noteFromCursor(c) }
        return out
    }

    fun addNote(jobsiteId: String, text: String, pinned: Boolean = false): JobsiteNote {
        val cleaned = text.trim()
        require(cleaned.isNotBlank()) { "Nota vuota" }
        val note = JobsiteNote(
            ge360Id = UUID.randomUUID().toString(),
            jobsiteId = jobsiteId,
            text = cleaned,
            pinned = pinned
        )
        note.id = writableDatabase.insertOrThrow(NOTES, null, noteValues(note))
        recordEvent(jobsiteId, "NOTE", if (pinned) "Nota importante" else "Nota aggiunta", cleaned.take(180), note.ge360Id)
        return note
    }

    fun deleteNote(note: JobsiteNote) {
        writableDatabase.delete(NOTES, "id=?", arrayOf(note.id.toString()))
        recordEvent(note.jobsiteId, "NOTE_DELETE", "Nota rimossa", note.text.take(120), note.ge360Id)
    }

    fun events(jobsiteId: String, limit: Int = 60): List<WorkflowEvent> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<WorkflowEvent>()
        readableDatabase.query(
            EVENTS, null, "jobsite_id=?", arrayOf(jobsiteId), null, null,
            "created_at DESC", limit.coerceIn(1, 500).toString()
        ).use { c -> while (c.moveToNext()) out += eventFromCursor(c) }
        return out
    }

    fun recordEvent(jobsiteId: String, type: String, title: String, detail: String = "", entityId: String = "") {
        if (jobsiteId.isBlank() || title.isBlank()) return
        val event = WorkflowEvent(
            ge360Id = UUID.randomUUID().toString(),
            jobsiteId = jobsiteId,
            eventType = type,
            title = title.trim(),
            detail = detail.trim(),
            entityId = entityId,
            createdAt = System.currentTimeMillis()
        )
        writableDatabase.insertOrThrow(EVENTS, null, eventValues(event))
    }

    fun learningSuggestions(workType: String = ""): List<LearningSuggestion> {
        val where = if (workType.isBlank()) null else "work_type=?"
        val args = if (workType.isBlank()) null else arrayOf(workType)
        val out = mutableListOf<LearningSuggestion>()
        readableDatabase.query(LEARNING, null, where, args, null, null, "CASE status WHEN 'PROPOSTA' THEN 0 ELSE 1 END, occurrences DESC, updated_at DESC")
            .use { c -> while (c.moveToNext()) out += learningFromCursor(c) }
        return out
    }

    fun setLearningStatus(suggestion: LearningSuggestion, status: String) {
        suggestion.status = status
        suggestion.updatedAt = System.currentTimeMillis()
        writableDatabase.update(LEARNING, ContentValues().apply {
            put("status", suggestion.status)
            put("updated_at", suggestion.updatedAt)
        }, "id=?", arrayOf(suggestion.id.toString()))
    }

    private fun observeLearning(task: WorkflowTask) {
        if (task.source != WorkflowSources.MANUAL || task.normalizedTitle.isBlank()) return
        val occurrences = readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT jobsite_id) FROM $TASKS WHERE source=? AND work_type=? AND normalized_title=?",
            arrayOf(WorkflowSources.MANUAL, task.workType, task.normalizedTitle)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (occurrences < LEARNING_THRESHOLD) return

        val existing = readableDatabase.query(
            LEARNING, null, "work_type=? AND normalized_title=?", arrayOf(task.workType, task.normalizedTitle), null, null, null, "1"
        ).use { if (it.moveToFirst()) learningFromCursor(it) else null }
        val now = System.currentTimeMillis()
        val suggestion = existing ?: LearningSuggestion(
            ge360Id = UUID.randomUUID().toString(),
            workType = task.workType,
            normalizedTitle = task.normalizedTitle,
            title = task.title,
            createdAt = now
        )
        suggestion.occurrences = occurrences
        suggestion.updatedAt = now
        if (suggestion.id > 0) {
            writableDatabase.update(LEARNING, learningValues(suggestion), "id=?", arrayOf(suggestion.id.toString()))
        } else {
            suggestion.id = writableDatabase.insertOrThrow(LEARNING, null, learningValues(suggestion))
        }
    }

    // ---- Programma singole lavorazioni (solo metadati, checklist canonica) ----

    fun taskPlans(jobsiteId: String): List<WorkflowTaskPlan> {
        if (jobsiteId.isBlank()) return emptyList()
        val out = mutableListOf<WorkflowTaskPlan>()
        readableDatabase.query(PLANS, null, "jobsite_id=?", arrayOf(jobsiteId),
            null, null, "updated_at DESC, task_ge360_id").use { cursor ->
            while (cursor.moveToNext()) out += taskPlanFromCursor(cursor)
        }
        return out
    }

    fun saveTaskPlan(plan: WorkflowTaskPlan) {
        val task = findTaskByGe360(plan.taskGe360Id) ?: error("La lavorazione non esiste più")
        val current = taskPlans(task.jobsiteId)
        TaskScheduleEngine.validateCandidate(task, plan, tasks(task.jobsiteId), current)
        require(plan.plannedWorkdays > 0 || plan.predecessorIds.isNotEmpty()) {
            "Imposta durata o predecessori; usa Rimuovi piano per cancellare la programmazione"
        }
        require(plan.note.length <= 500) { "Nota troppo lunga" }
        val prior = current.firstOrNull { it.taskGe360Id == plan.taskGe360Id }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("task_ge360_id", plan.taskGe360Id); put("jobsite_id", plan.jobsiteId)
            put("planned_workdays", plan.plannedWorkdays)
            put("predecessor_ids_json", JSONArray(plan.predecessorIds).toString())
            put("note", plan.note.trim())
            put("created_at", prior?.createdAt ?: now)
            put("updated_at", now)
        }
        if (prior != null) writableDatabase.update(PLANS, values,
            "task_ge360_id=?", arrayOf(plan.taskGe360Id))
        else writableDatabase.insertOrThrow(PLANS, null, values)
    }

    fun removeTaskPlan(taskGe360Id: String) {
        writableDatabase.delete(PLANS, "task_ge360_id=?", arrayOf(taskGe360Id))
    }

    fun taskSchedule(
        jobsiteId: String, today: java.time.LocalDate = java.time.LocalDate.now(),
        targetEndIso: String = ""
    ): TaskScheduleReport =
        TaskScheduleEngine.project(tasks(jobsiteId), taskPlans(jobsiteId), today, targetEndIso)

    private fun taskPlanFromCursor(cursor: Cursor): WorkflowTaskPlan {
        val deps = JSONArray(cursor.string("predecessor_ids_json"))
        return WorkflowTaskPlan(
            taskGe360Id = cursor.string("task_ge360_id"),
            jobsiteId = cursor.string("jobsite_id"),
            plannedWorkdays = cursor.int("planned_workdays"),
            predecessorIds = (0 until deps.length()).map { deps.getString(it) },
            note = cursor.string("note"),
            createdAt = cursor.long("created_at"),
            updatedAt = cursor.long("updated_at")
        )
    }

    private fun allTaskPlans(): List<WorkflowTaskPlan> {
        val out = mutableListOf<WorkflowTaskPlan>()
        readableDatabase.query(PLANS, null, null, null,
            null, null, "jobsite_id, task_ge360_id").use { cursor ->
            while (cursor.moveToNext()) out += taskPlanFromCursor(cursor)
        }
        return out
    }

    private fun taskPlanJson(p: WorkflowTaskPlan) = JSONObject().apply {
        put("taskGe360Id", p.taskGe360Id); put("jobsiteId", p.jobsiteId)
        put("plannedWorkdays", p.plannedWorkdays)
        put("predecessorIds", JSONArray(p.predecessorIds))
        put("note", p.note); put("createdAt", p.createdAt); put("updatedAt", p.updatedAt)
    }

    private fun taskPlanFromJson(o: JSONObject): WorkflowTaskPlan {
        val deps = o.getJSONArray("predecessorIds")
        return WorkflowTaskPlan(
            taskGe360Id = o.getString("taskGe360Id"),
            jobsiteId = o.getString("jobsiteId"),
            plannedWorkdays = o.getInt("plannedWorkdays"),
            predecessorIds = (0 until deps.length()).map { deps.getString(it) },
            note = o.optString("note", ""),
            createdAt = o.getLong("createdAt"),
            updatedAt = o.getLong("updatedAt")
        )
    }

    private fun importTaskPlan(plan: WorkflowTaskPlan) {
        val task = findTaskByGe360(plan.taskGe360Id) ?: error("Lavorazione piano non trovata nel backup")
        val plans = taskPlans(task.jobsiteId)
        TaskScheduleEngine.validateCandidate(task, plan, tasks(task.jobsiteId), plans)
        require(plan.plannedWorkdays > 0 || plan.predecessorIds.isNotEmpty()) {
            "Piano attività privo di durata e dipendenze"
        }
        val previous = plans.firstOrNull { it.taskGe360Id == plan.taskGe360Id }
        if (previous != null) {
            require(previous == plan) {
                "Conflitto piano lavorazione: stesso UUID ma contenuti diversi; risolvi manualmente"
            }
            return
        }
        writableDatabase.insertOrThrow(PLANS, null, ContentValues().apply {
            put("task_ge360_id", plan.taskGe360Id); put("jobsite_id", plan.jobsiteId)
            put("planned_workdays", plan.plannedWorkdays)
            put("predecessor_ids_json", JSONArray(plan.predecessorIds).toString())
            put("note", plan.note); put("created_at", plan.createdAt); put("updated_at", plan.updatedAt)
        })
    }

    fun exportJson(forSync: Boolean = false): String = JSONObject().apply {
        put("schema", if (forSync) "ge360.centro-cantiere.v1" else "ge360.centro-cantiere.v2")
        put("exportedAt", System.currentTimeMillis())
        put("learningPolicy", "observe-propose-approve")
        put("tasks", JSONArray().apply { allTasks().forEach { put(taskToJson(it)) } })
        put("notes", JSONArray().apply { allNotes().forEach { put(noteToJson(it)) } })
        put("events", JSONArray().apply { allEvents().forEach { put(eventToJson(it)) } })
        put("learningSuggestions", JSONArray().apply { learningSuggestions().forEach { put(learningToJson(it)) } })
        // New plans are backed up in the full ZIP but have no Data Hub contract.
        if (!forSync) put("taskPlans", JSONArray().apply { allTaskPlans().forEach { put(taskPlanJson(it)) } })
    }.toString(2)

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") in setOf("ge360.centro-cantiere.v1", "ge360.centro-cantiere.v2")) { "Formato Centro Cantiere non riconosciuto" }
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete(PLANS, null, null)
                db.delete(TASKS, null, null)
                db.delete(NOTES, null, null)
                db.delete(EVENTS, null, null)
                db.delete(LEARNING, null, null)
            }
            root.optJSONArray("tasks")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val incoming = taskFromJson(rows.getJSONObject(i))
                    findTaskByGe360(incoming.ge360Id)?.let { incoming.id = it.id }
                    saveTask(incoming, recordChange = false)
                    count++
                }
            }
            root.optJSONArray("notes")?.let { rows ->
                for (i in 0 until rows.length()) upsertNote(noteFromJson(rows.getJSONObject(i))).also { count++ }
            }
            root.optJSONArray("events")?.let { rows ->
                for (i in 0 until rows.length()) upsertEvent(eventFromJson(rows.getJSONObject(i))).also { count++ }
            }
            root.optJSONArray("learningSuggestions")?.let { rows ->
                for (i in 0 until rows.length()) upsertLearning(learningFromJson(rows.getJSONObject(i))).also { count++ }
            }
            root.optJSONArray("taskPlans")?.let { rows ->
                for (i in 0 until rows.length()) {
                    importTaskPlan(taskPlanFromJson(rows.getJSONObject(i)))
                    count++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    private fun maxOrder(jobsiteId: String): Int = readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(sort_order),0) FROM $TASKS WHERE jobsite_id=?", arrayOf(jobsiteId)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun allTasks(): List<WorkflowTask> {
        val out = mutableListOf<WorkflowTask>()
        readableDatabase.query(TASKS, null, null, null, null, null, "jobsite_id, sort_order").use { c -> while (c.moveToNext()) out += taskFromCursor(c) }
        return out
    }

    private fun allNotes(): List<JobsiteNote> {
        val out = mutableListOf<JobsiteNote>()
        readableDatabase.query(NOTES, null, null, null, null, null, "updated_at DESC").use { c -> while (c.moveToNext()) out += noteFromCursor(c) }
        return out
    }

    private fun allEvents(): List<WorkflowEvent> {
        val out = mutableListOf<WorkflowEvent>()
        readableDatabase.query(EVENTS, null, null, null, null, null, "created_at DESC").use { c -> while (c.moveToNext()) out += eventFromCursor(c) }
        return out
    }

    private fun findTaskByGe360(id: String): WorkflowTask? = if (id.isBlank()) null else readableDatabase.query(
        TASKS, null, "ge360_id=?", arrayOf(id), null, null, null, "1"
    ).use { if (it.moveToFirst()) taskFromCursor(it) else null }

    private fun upsertNote(note: JobsiteNote) {
        val existingId = readableDatabase.query(NOTES, arrayOf("id"), "ge360_id=?", arrayOf(note.ge360Id), null, null, null, "1")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (existingId > 0) writableDatabase.update(NOTES, noteValues(note), "id=?", arrayOf(existingId.toString()))
        else writableDatabase.insertOrThrow(NOTES, null, noteValues(note))
    }

    private fun upsertEvent(event: WorkflowEvent) {
        writableDatabase.insertWithOnConflict(EVENTS, null, eventValues(event), SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun upsertLearning(value: LearningSuggestion) {
        writableDatabase.insertWithOnConflict(LEARNING, null, learningValues(value), SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun taskValues(t: WorkflowTask) = ContentValues().apply {
        put("ge360_id", t.ge360Id); put("jobsite_id", t.jobsiteId); put("work_type", t.workType); put("template_id", t.templateId)
        put("phase", t.phase); put("sort_order", t.sortOrder); put("title", t.title.trim()); put("normalized_title", t.normalizedTitle)
        put("detail", t.detail.trim()); put("status", t.status); put("owner", t.owner.trim()); put("source", t.source); put("work_pack_id", t.workPackId)
        put("photo_required", if (t.photoRequired) 1 else 0); put("document_required", if (t.documentRequired) 1 else 0)
        put("created_at", t.createdAt); put("updated_at", t.updatedAt)
    }

    private fun noteValues(n: JobsiteNote) = ContentValues().apply {
        put("ge360_id", n.ge360Id); put("jobsite_id", n.jobsiteId); put("text", n.text.trim()); put("pinned", if (n.pinned) 1 else 0)
        put("created_at", n.createdAt); put("updated_at", n.updatedAt)
    }

    private fun eventValues(e: WorkflowEvent) = ContentValues().apply {
        put("ge360_id", e.ge360Id); put("jobsite_id", e.jobsiteId); put("event_type", e.eventType); put("title", e.title.trim())
        put("detail", e.detail.trim()); put("entity_id", e.entityId); put("created_at", e.createdAt)
    }

    private fun learningValues(s: LearningSuggestion) = ContentValues().apply {
        put("ge360_id", s.ge360Id); put("work_type", s.workType); put("normalized_title", s.normalizedTitle); put("title", s.title)
        put("occurrences", s.occurrences); put("status", s.status); put("created_at", s.createdAt); put("updated_at", s.updatedAt)
    }

    private fun taskFromCursor(c: Cursor) = WorkflowTask(
        id = c.long("id"), ge360Id = c.string("ge360_id"), jobsiteId = c.string("jobsite_id"), workType = c.string("work_type"),
        templateId = c.string("template_id"), phase = c.string("phase"), sortOrder = c.int("sort_order"), title = c.string("title"),
        normalizedTitle = c.string("normalized_title"), detail = c.string("detail"), status = c.string("status"), owner = c.string("owner"),
        source = c.string("source"), workPackId = c.string("work_pack_id"), photoRequired = c.int("photo_required") == 1,
        documentRequired = c.int("document_required") == 1, createdAt = c.long("created_at"), updatedAt = c.long("updated_at")
    )

    private fun noteFromCursor(c: Cursor) = JobsiteNote(
        id = c.long("id"), ge360Id = c.string("ge360_id"), jobsiteId = c.string("jobsite_id"), text = c.string("text"),
        pinned = c.int("pinned") == 1, createdAt = c.long("created_at"), updatedAt = c.long("updated_at")
    )

    private fun eventFromCursor(c: Cursor) = WorkflowEvent(
        id = c.long("id"), ge360Id = c.string("ge360_id"), jobsiteId = c.string("jobsite_id"), eventType = c.string("event_type"),
        title = c.string("title"), detail = c.string("detail"), entityId = c.string("entity_id"), createdAt = c.long("created_at")
    )

    private fun learningFromCursor(c: Cursor) = LearningSuggestion(
        id = c.long("id"), ge360Id = c.string("ge360_id"), workType = c.string("work_type"), normalizedTitle = c.string("normalized_title"),
        title = c.string("title"), occurrences = c.int("occurrences"), status = c.string("status"), createdAt = c.long("created_at"), updatedAt = c.long("updated_at")
    )

    private fun taskToJson(t: WorkflowTask) = JSONObject().apply {
        put("ge360Id", t.ge360Id); put("jobsiteId", t.jobsiteId); put("workType", t.workType); put("templateId", t.templateId); put("phase", t.phase)
        put("sortOrder", t.sortOrder); put("title", t.title); put("normalizedTitle", t.normalizedTitle); put("detail", t.detail); put("status", t.status)
        put("owner", t.owner); put("source", t.source); put("workPackId", t.workPackId); put("photoRequired", t.photoRequired); put("documentRequired", t.documentRequired)
        put("createdAt", t.createdAt); put("updatedAt", t.updatedAt)
    }

    private fun noteToJson(n: JobsiteNote) = JSONObject().apply {
        put("ge360Id", n.ge360Id); put("jobsiteId", n.jobsiteId); put("text", n.text); put("pinned", n.pinned); put("createdAt", n.createdAt); put("updatedAt", n.updatedAt)
    }

    private fun eventToJson(e: WorkflowEvent) = JSONObject().apply {
        put("ge360Id", e.ge360Id); put("jobsiteId", e.jobsiteId); put("eventType", e.eventType); put("title", e.title); put("detail", e.detail); put("entityId", e.entityId); put("createdAt", e.createdAt)
    }

    private fun learningToJson(s: LearningSuggestion) = JSONObject().apply {
        put("ge360Id", s.ge360Id); put("workType", s.workType); put("normalizedTitle", s.normalizedTitle); put("title", s.title); put("occurrences", s.occurrences)
        put("status", s.status); put("createdAt", s.createdAt); put("updatedAt", s.updatedAt)
    }

    private fun taskFromJson(o: JSONObject) = WorkflowTask(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId"), workType = o.optString("workType"),
        templateId = o.optString("templateId"), phase = o.optString("phase", "Lavorazioni"), sortOrder = o.optInt("sortOrder"), title = o.optString("title"),
        normalizedTitle = o.optString("normalizedTitle"), detail = o.optString("detail"), status = o.optString("status", WorkflowStatuses.TODO),
        owner = o.optString("owner"), source = o.optString("source", WorkflowSources.MANUAL), workPackId = o.optString("workPackId"),
        photoRequired = o.optBoolean("photoRequired"), documentRequired = o.optBoolean("documentRequired"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun noteFromJson(o: JSONObject) = JobsiteNote(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId"), text = o.optString("text"), pinned = o.optBoolean("pinned"),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun eventFromJson(o: JSONObject) = WorkflowEvent(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId"), eventType = o.optString("eventType", "INFO"),
        title = o.optString("title"), detail = o.optString("detail"), entityId = o.optString("entityId"), createdAt = o.optLong("createdAt", System.currentTimeMillis())
    )

    private fun learningFromJson(o: JSONObject) = LearningSuggestion(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), workType = o.optString("workType"), normalizedTitle = o.optString("normalizedTitle"),
        title = o.optString("title"), occurrences = o.optInt("occurrences"), status = o.optString("status", LearningStatuses.PROPOSED),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun normalize(value: String): String = value
        .trim()
        .lowercase(Locale.ITALY)
        .replace(Regex("[^a-z0-9àèéìòù]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

    companion object {
        private const val DB_NAME = "ge360_centro_cantiere.db"
        private const val DB_VERSION = 2
        private const val TASKS = "workflow_tasks"
        private const val NOTES = "jobsite_notes"
        private const val EVENTS = "workflow_events"
        private const val LEARNING = "learning_suggestions"
        private const val PLANS = "workflow_task_plans"
        private const val LEARNING_THRESHOLD = 3
    }
}
