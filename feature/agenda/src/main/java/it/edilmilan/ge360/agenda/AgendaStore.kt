package it.edilmilan.ge360.agenda

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

        fun fromJson(row: JSONObject): AgendaEntry {
            val id = row.getString("id")
            require(runCatching { UUID.fromString(id) }.isSuccess) { "ID agenda non valido" }
            val kind = row.getString("kind")
            val scope = row.optString("scope", WORK)
            val priority = row.optString("priority", NORMAL)
            require(kind in setOf(NOTE, TASK, EVENT)) { "Tipo agenda non valido" }
            require(scope in setOf(WORK, PRIVATE)) { "Visibilita' non valida" }
            require(priority in setOf(NORMAL, HIGH)) { "Priorita' non valida" }
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
    private val atomic = AtomicFile(File(context.applicationContext.filesDir, "ge360-agenda-v1.json"))

    fun list(): List<AgendaEntry> = synchronized(LOCK) {
        read().sortedWith(compareBy<AgendaEntry> { it.completed }
            .thenBy { if (it.dueAt > 0) it.dueAt else Long.MAX_VALUE }
            .thenByDescending { it.updatedAt })
    }

    fun save(entry: AgendaEntry) = synchronized(LOCK) {
        require(entry.title.isNotBlank()) { "Titolo obbligatorio" }
        require(entry.kind in setOf(AgendaEntry.NOTE, AgendaEntry.TASK, AgendaEntry.EVENT))
        require(entry.kind != AgendaEntry.EVENT || entry.dueAt > 0) { "Scegli la data dell'appuntamento" }
        val all = read().associateBy { it.id }.toMutableMap()
        val old = all[entry.id]
        val now = System.currentTimeMillis()
        all[entry.id] = entry.copy(createdAt = old?.createdAt ?: now, updatedAt = now)
        write(all.values.toList())
    }

    fun setCompleted(id: String, done: Boolean) = synchronized(LOCK) {
        val all = read().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        require(index >= 0) { "Elemento non trovato" }
        require(all[index].kind != AgendaEntry.NOTE) { "Una nota non e' un'attivita'" }
        all[index] = all[index].copy(completed = done, updatedAt = System.currentTimeMillis())
        write(all)
    }

    fun promoteNote(id: String): String = synchronized(LOCK) {
        val all = read().toMutableList()
        val source = all.firstOrNull { it.id == id && it.kind == AgendaEntry.NOTE }
            ?: throw IllegalArgumentException("Nota non trovata")
        val task = source.copy(
            id = UUID.randomUUID().toString(), kind = AgendaEntry.TASK, dueAt = 0,
            sourceNoteId = source.id, completed = false,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
        all.add(task)
        write(all)
        task.id
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
