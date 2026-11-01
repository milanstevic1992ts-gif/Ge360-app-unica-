package it.edilmilan.ge360.agenda

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Taccuino operativo offline-first. I riferimenti ai moduli sono UUID GE360,
 * mai ID locali SQLite. Nessun cliente o cantiere e' obbligatorio.
 */
data class AgendaEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = NOTE,
    val title: String = "",
    val body: String = "",
    val dueAt: Long = 0L,
    val repeat: String = REPEAT_NONE,
    val repeatAnchorDay: Int = 0,
    val reminderEnabled: Boolean = false,
    val reminderLeadMinutes: Int = 0,
    val alertedDueAt: Long = 0L,
    val lastCompletedAt: Long = 0L,
    val priority: String = NORMAL,
    val scope: String = WORK,
    val clientGe360Id: String = "",
    val jobsiteGe360Id: String = "",
    val sourceNoteId: String = "",
    val completed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun asJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("title", title).put("body", body)
        .put("dueAt", dueAt).put("priority", priority).put("scope", scope)
        .put("repeat", repeat).put("repeatAnchorDay", repeatAnchorDay)
        .put("reminderEnabled", reminderEnabled).put("reminderLeadMinutes", reminderLeadMinutes)
        .put("alertedDueAt", alertedDueAt).put("lastCompletedAt", lastCompletedAt)
        .put("clientGe360Id", clientGe360Id).put("jobsiteGe360Id", jobsiteGe360Id)
        .put("sourceNoteId", sourceNoteId).put("completed", completed)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    companion object {
        const val NOTE = "NOTE"
        const val TASK = "TASK"
        const val EVENT = "EVENT"
        const val NORMAL = "NORMAL"
        const val HIGH = "HIGH"
        const val WORK = "WORK"
        const val PRIVATE = "PRIVATE"
        const val REPEAT_NONE = "NONE"
        const val REPEAT_DAILY = "DAILY"
        const val REPEAT_WEEKLY = "WEEKLY"
        const val REPEAT_MONTHLY = "MONTHLY"
        val REPEAT_OPTIONS = listOf(REPEAT_NONE, REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_MONTHLY)

        fun fromJson(row: JSONObject): AgendaEntry {
            val id = row.getString("id")
            require(runCatching { UUID.fromString(id) }.isSuccess) { "ID agenda non valido" }
            val kind = row.getString("kind")
            val scope = row.optString("scope", WORK)
            val priority = row.optString("priority", NORMAL)
            require(kind in setOf(NOTE, TASK, EVENT)) { "Tipo agenda non valido" }
            require(scope in setOf(WORK, PRIVATE)) { "Visibilita' non valida" }
            require(priority in setOf(NORMAL, HIGH)) { "Priorita' non valida" }
            val repeat = row.optString("repeat", REPEAT_NONE)
            val anchor = row.optInt("repeatAnchorDay", 0)
            val lead = row.optInt("reminderLeadMinutes", 0)
            require(repeat in REPEAT_OPTIONS && anchor in 0..31 && lead in setOf(0, 15, 60, 1440)) { "Ricorrenza o anticipo non valido" }
            val title = row.optString("title")
            require(title.isNotBlank()) { "Titolo agenda mancante" }
            val dueAt = row.optLong("dueAt", 0)
            require(dueAt >= 0 && (kind != EVENT || dueAt > 0)) { "Data evento mancante o non valida" }
            val createdAt = row.optLong("createdAt")
            val updatedAt = row.optLong("updatedAt")
            require(createdAt > 0 && updatedAt > 0) { "Cronologia agenda non valida" }
            return AgendaEntry(
                id = id, kind = kind, title = title, body = row.optString("body"),
                dueAt = dueAt, priority = priority, scope = scope,
                repeat = repeat, repeatAnchorDay = anchor,
                reminderEnabled = row.optBoolean("reminderEnabled", false), reminderLeadMinutes = lead,
                alertedDueAt = row.optLong("alertedDueAt", 0L).coerceAtLeast(0L),
                lastCompletedAt = row.optLong("lastCompletedAt", 0L).coerceAtLeast(0L),
                clientGe360Id = row.optString("clientGe360Id"),
                jobsiteGe360Id = row.optString("jobsiteGe360Id"),
                sourceNoteId = row.optString("sourceNoteId"),
                completed = row.optBoolean("completed", false),
                createdAt = createdAt, updatedAt = updatedAt
            )
        }
    }
}

/**
 * Scrittura atomica: un errore di lettura non viene trattato come archivio vuoto.
 * Import merge per UUID; a parita' di revisione prevale il record gia' presente.
 */
class AgendaStore(context: Context) {
    private val appContext = context.applicationContext
    private val atomic = AtomicFile(File(appContext.filesDir, "ge360-agenda-v1.json"))

    fun list(): List<AgendaEntry> = synchronized(LOCK) {
        read().sortedWith(compareBy<AgendaEntry> { it.completed }
            .thenBy { if (it.dueAt > 0) it.dueAt else Long.MAX_VALUE }
            .thenByDescending { it.updatedAt })
    }

    fun save(entry: AgendaEntry) = synchronized(LOCK) {
        require(entry.title.isNotBlank()) { "Titolo obbligatorio" }
        require(entry.kind in setOf(AgendaEntry.NOTE, AgendaEntry.TASK, AgendaEntry.EVENT))
        require(entry.kind != AgendaEntry.EVENT || entry.dueAt > 0) { "Scegli la data dell'appuntamento" }
        require(entry.repeat in AgendaEntry.REPEAT_OPTIONS &&
            (entry.repeat == AgendaEntry.REPEAT_NONE || entry.dueAt > 0))
        require(entry.reminderLeadMinutes in setOf(0, 15, 60, 1440))
        val all = read().associateBy { it.id }.toMutableMap()
        val old = all[entry.id]
        val now = System.currentTimeMillis()
        val anchor = if (entry.repeat == AgendaEntry.REPEAT_MONTHLY && entry.dueAt > 0) {
            entry.repeatAnchorDay.takeIf { it in 1..31 && old?.dueAt == entry.dueAt }
                ?: Calendar.getInstance().apply { timeInMillis = entry.dueAt }.get(Calendar.DAY_OF_MONTH)
        } else 0
        val preserve = old != null && old.dueAt == entry.dueAt &&
            old.reminderEnabled == entry.reminderEnabled && old.reminderLeadMinutes == entry.reminderLeadMinutes
        all[entry.id] = entry.copy(createdAt = old?.createdAt ?: now, updatedAt = now,
            repeatAnchorDay = anchor, alertedDueAt = if (preserve) old!!.alertedDueAt else 0L)
        write(all.values.toList())
    }

    fun setCompleted(id: String, done: Boolean) = synchronized(LOCK) {
        val all = read().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        require(index >= 0) { "Elemento non trovato" }
        require(all[index].kind != AgendaEntry.NOTE) { "Una nota non e' un'attivita'" }
        val current = all[index]
        val now = System.currentTimeMillis()
        all[index] = if (done && current.repeat != AgendaEntry.REPEAT_NONE && current.dueAt > 0) {
            current.copy(dueAt = AgendaRecurrence.nextAfter(current.dueAt, now, current.repeat, current.repeatAnchorDay),
                completed = false, alertedDueAt = 0L, lastCompletedAt = now, updatedAt = now)
        } else current.copy(completed = done, updatedAt = now)
        write(all)
    }

    fun promoteNote(id: String): String = synchronized(LOCK) {
        val all = read().toMutableList()
        val source = all.firstOrNull { it.id == id && it.kind == AgendaEntry.NOTE }
            ?: throw IllegalArgumentException("Nota non trovata")
        val task = source.copy(
            id = UUID.randomUUID().toString(), kind = AgendaEntry.TASK, dueAt = 0,
            sourceNoteId = source.id, completed = false, repeat = AgendaEntry.REPEAT_NONE,
            repeatAnchorDay = 0, reminderEnabled = false, alertedDueAt = 0L,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
        all.add(task)
        write(all)
        task.id
    }

    /** Claim due occurrences atomically so broadcasts cannot show duplicates. */
    fun claimDueAlerts(now: Long): List<AgendaEntry> = synchronized(LOCK) {
        val all = read().toMutableList()
        val due = all.filter { e -> e.kind != AgendaEntry.NOTE && !e.completed &&
            e.reminderEnabled && e.dueAt > 0 && e.alertedDueAt != e.dueAt &&
            e.dueAt - e.reminderLeadMinutes * 60_000L <= now }
        if (due.isNotEmpty()) {
            val ids = due.map { it.id }.toSet()
            all.indices.forEach { i -> if (all[i].id in ids)
                all[i] = all[i].copy(alertedDueAt = all[i].dueAt) }
            write(all)
        }
        due.filter { it.dueAt >= now - 7L * 24 * 60 * 60 * 1000 }
    }

    fun delete(id: String) = synchronized(LOCK) {
        val all = read()
        require(all.any { it.id == id }) { "Elemento non trovato" }
        write(all.filterNot { it.id == id })
    }

    fun exportJson(): String = synchronized(LOCK) { serialize(read()) }

    // Non inviare note private nel Data Hub condiviso.
    fun exportForSyncJson(): String = synchronized(LOCK) {
        serialize(read().filter { it.scope == AgendaEntry.WORK })
    }

    fun importJson(json: String, replaceAll: Boolean): Int = synchronized(LOCK) {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Schema agenda non supportato" }
        val array = root.getJSONArray("records")
        val incoming = (0 until array.length()).map { AgendaEntry.fromJson(array.getJSONObject(it)) }
        require(incoming.map { it.id }.toSet().size == incoming.size) { "ID agenda duplicati" }
        val final = if (replaceAll) incoming else {
            val combined = read().associateBy { it.id }.toMutableMap()
            incoming.forEach { item ->
                val old = combined[item.id]
                if (old == null || item.updatedAt > old.updatedAt) combined[item.id] = item
            }
            combined.values.toList()
        }
        write(final)
        incoming.size
    }

    private fun serialize(rows: List<AgendaEntry>): String = JSONObject()
        .put("schema", SCHEMA)
        .put("exportedAt", System.currentTimeMillis())
        .put("records", JSONArray().apply { rows.forEach { put(it.asJson()) } })
        .toString()

    private fun read(): List<AgendaEntry> {
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(String(atomic.openRead().use { it.readBytes() }, Charsets.UTF_8))
        require(root.optString("schema") == SCHEMA) { "Archivio agenda da migrare" }
        val array = root.getJSONArray("records")
        return (0 until array.length()).map { AgendaEntry.fromJson(array.getJSONObject(it)) }
    }

    private fun write(rows: List<AgendaEntry>) {
        val bytes = serialize(rows).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
            // Reminder scheduling must not invalidate a successfully saved file.
            runCatching { AgendaReminderScheduler.reschedule(appContext) }
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    companion object {
        const val SCHEMA = "ge360.agenda.v1"
        private val LOCK = Any()
    }
}
