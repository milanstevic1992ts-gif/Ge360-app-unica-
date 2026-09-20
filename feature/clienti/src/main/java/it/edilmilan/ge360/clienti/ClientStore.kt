package it.edilmilan.ge360.clienti

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.telephony.PhoneNumberUtils
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


data class ClientRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var ge360JobsiteId: String = "",
    var firstName: String = "",
    var lastName: String = "",
    var phone: String = "",
    var email: String = "",
    var address: String = "",
    var notes: String = "",
    var contactUri: String = "",
    var temperature: String = "Da coltivare",
    var aiTemperature: String = "Non analizzata",
    var relationshipPhase: String = "Nuovo contatto",
    var pulse: Int = 50,
    var birthday: String = "",
    var followUp: String = "",
    var lastInteractionAt: Long = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun fullName(): String = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Cliente senza nome" }
}

data class ClientInteraction(
    val id: Long = 0,
    val clientId: Long,
    val signal: String,
    val reason: String,
    val detail: String,
    val delta: Int,
    val pulseAfter: Int,
    val createdAt: Long
)

object RelationshipRules {
    fun deltaFor(signal: String): Int = when (signal) {
        "Positivo" -> 10
        "Curioso" -> 5
        "Neutro" -> 0
        "Dubbioso" -> -5
        "Negativo" -> -10
        "Teso" -> -15
        else -> 0
    }
}

class ClientStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE clients (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL DEFAULT '',
                ge360_jobsite_id TEXT NOT NULL DEFAULT '',
                first_name TEXT NOT NULL DEFAULT '',
                last_name TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL DEFAULT '',
                email TEXT NOT NULL DEFAULT '',
                address TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                contact_uri TEXT NOT NULL DEFAULT '',
                temperature TEXT NOT NULL DEFAULT 'Da coltivare',
                ai_temperature TEXT NOT NULL DEFAULT 'Non analizzata',
                relationship_phase TEXT NOT NULL DEFAULT 'Nuovo contatto',
                pulse INTEGER NOT NULL DEFAULT 50,
                birthday TEXT NOT NULL DEFAULT '',
                follow_up TEXT NOT NULL DEFAULT '',
                last_interaction_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE relationship_interactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                client_id INTEGER NOT NULL,
                signal TEXT NOT NULL DEFAULT '',
                reason TEXT NOT NULL DEFAULT '',
                detail TEXT NOT NULL DEFAULT '',
                delta INTEGER NOT NULL DEFAULT 0,
                pulse_after INTEGER NOT NULL DEFAULT 50,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_clients_name ON clients(last_name, first_name)")
        db.execSQL("CREATE INDEX idx_clients_ge360 ON clients(ge360_id)")
        db.execSQL("CREATE INDEX idx_interactions_client ON relationship_interactions(client_id, created_at DESC)")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Prima versione del database unificato. Le migrazioni future saranno incrementali.
    }

    fun search(query: String = ""): List<ClientRecord> {
        val q = query.trim()
        val where: String?
        val args: Array<String>?
        if (q.isEmpty()) {
            where = null
            args = null
        } else {
            val like = "%$q%"
            where = "first_name LIKE ? OR last_name LIKE ? OR phone LIKE ? OR email LIKE ? OR address LIKE ? OR notes LIKE ? OR temperature LIKE ? OR relationship_phase LIKE ?"
            args = Array(8) { like }
        }
        val result = mutableListOf<ClientRecord>()
        readableDatabase.query("clients", null, where, args, null, null, "last_name COLLATE NOCASE, first_name COLLATE NOCASE").use { cursor ->
            while (cursor.moveToNext()) result += fromCursor(cursor)
        }
        return result
    }

    fun get(id: Long): ClientRecord? {
        readableDatabase.query("clients", null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { cursor ->
            return if (cursor.moveToFirst()) fromCursor(cursor) else null
        }
    }

    fun getByGe360Id(ge360Id: String): ClientRecord? {
        if (ge360Id.isBlank()) return null
        readableDatabase.query("clients", null, "ge360_id=?", arrayOf(ge360Id), null, null, null, "1").use { cursor ->
            return if (cursor.moveToFirst()) fromCursor(cursor) else null
        }
    }

    fun save(client: ClientRecord): Long {
        val now = System.currentTimeMillis()
        ensureIdentity(client)
        client.pulse = client.pulse.coerceIn(0, 100)
        client.updatedAt = now
        if (client.createdAt <= 0) client.createdAt = now
        val values = toValues(client)
        return if (client.id > 0) {
            writableDatabase.update("clients", values, "id=?", arrayOf(client.id.toString()))
            client.id
        } else {
            writableDatabase.insertOrThrow("clients", null, values).also { client.id = it }
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("clients", "id=?", arrayOf(id.toString()))
    }

    fun findDuplicate(phone: String, email: String): ClientRecord? {
        val p = normalizePhone(phone)
        val e = email.trim().lowercase(Locale.ROOT)
        return search().firstOrNull {
            (p.isNotEmpty() && normalizePhone(it.phone) == p) ||
                (e.isNotEmpty() && it.email.trim().lowercase(Locale.ROOT) == e)
        }
    }

    fun updateTemperature(clientId: Long, value: String) {
        val current = get(clientId) ?: return
        current.temperature = value
        save(current)
    }

    fun addInteraction(clientId: Long, signal: String, reason: String, detail: String): Int {
        val client = get(clientId) ?: return 50
        val wantedDelta = RelationshipRules.deltaFor(signal)
        val newPulse = (client.pulse + wantedDelta).coerceIn(0, 100)
        val actualDelta = newPulse - client.pulse
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val row = ContentValues().apply {
                put("client_id", clientId)
                put("signal", signal.trim())
                put("reason", reason.trim())
                put("detail", detail.trim())
                put("delta", actualDelta)
                put("pulse_after", newPulse)
                put("created_at", now)
            }
            db.insertOrThrow("relationship_interactions", null, row)
            val update = ContentValues().apply {
                put("pulse", newPulse)
                put("last_interaction_at", now)
                put("updated_at", now)
            }
            db.update("clients", update, "id=?", arrayOf(clientId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return newPulse
    }

    fun interactions(clientId: Long, limit: Int = 30): List<ClientInteraction> {
        val rows = mutableListOf<ClientInteraction>()
        readableDatabase.query(
            "relationship_interactions", null, "client_id=?", arrayOf(clientId.toString()),
            null, null, "created_at DESC", limit.coerceIn(1, 100).toString()
        ).use { c ->
            while (c.moveToNext()) {
                rows += ClientInteraction(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    clientId = c.getLong(c.getColumnIndexOrThrow("client_id")),
                    signal = c.getString(c.getColumnIndexOrThrow("signal")),
                    reason = c.getString(c.getColumnIndexOrThrow("reason")),
                    detail = c.getString(c.getColumnIndexOrThrow("detail")),
                    delta = c.getInt(c.getColumnIndexOrThrow("delta")),
                    pulseAfter = c.getInt(c.getColumnIndexOrThrow("pulse_after")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return rows
    }

    fun exportJson(): String {
        val root = JSONObject()
            .put("app", "GE360 Clienti")
            .put("formatVersion", 3)
            .put("exportedAt", System.currentTimeMillis())
        val clients = JSONArray()
        search().forEach { clients.put(toJson(it)) }
        root.put("clients", clients)
        val history = JSONArray()
        readableDatabase.query("relationship_interactions", null, null, null, null, null, "created_at ASC").use { c ->
            while (c.moveToNext()) {
                history.put(
                    JSONObject()
                        .put("clientId", c.getLong(c.getColumnIndexOrThrow("client_id")))
                        .put("signal", c.getString(c.getColumnIndexOrThrow("signal")))
                        .put("reason", c.getString(c.getColumnIndexOrThrow("reason")))
                        .put("detail", c.getString(c.getColumnIndexOrThrow("detail")))
                        .put("delta", c.getInt(c.getColumnIndexOrThrow("delta")))
                        .put("pulseAfter", c.getInt(c.getColumnIndexOrThrow("pulse_after")))
                        .put("createdAt", c.getLong(c.getColumnIndexOrThrow("created_at")))
                )
            }
        }
        root.put("relationshipInteractions", history)
        return root.toString(2)
    }

    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        val rows = root.getJSONArray("clients")
        val history = root.optJSONArray("relationshipInteractions")
        val db = writableDatabase
        val idMap = mutableMapOf<Long, Long>()
        var imported = 0
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("relationship_interactions", null, null)
                db.delete("clients", null, null)
            }
            for (i in 0 until rows.length()) {
                val source = rows.getJSONObject(i)
                val incoming = fromJson(source)
                val existingByUuid = getByGe360Id(incoming.ge360Id)
                val duplicate = if (incoming.ge360Id.isNotBlank()) existingByUuid else findDuplicate(incoming.phone, incoming.email)
                if (duplicate != null) {
                    incoming.id = duplicate.id
                    if (incoming.ge360Id.isBlank() && duplicate.ge360Id.isNotBlank()) incoming.ge360Id = duplicate.ge360Id
                    if (duplicate.ge360JobsiteId.isNotBlank()) incoming.ge360JobsiteId = duplicate.ge360JobsiteId
                    incoming.createdAt = duplicate.createdAt
                }
                val newId = save(incoming)
                idMap[source.optLong("backupId", 0)] = newId
                imported++
            }
            if (history != null) {
                for (i in 0 until history.length()) {
                    val item = history.getJSONObject(i)
                    val newClientId = idMap[item.optLong("clientId", 0)] ?: continue
                    val createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    val signal = item.optString("signal", "")
                    if (!interactionExists(newClientId, createdAt, signal)) {
                        val values = ContentValues().apply {
                            put("client_id", newClientId)
                            put("signal", signal)
                            put("reason", item.optString("reason", ""))
                            put("detail", item.optString("detail", ""))
                            put("delta", item.optInt("delta", 0))
                            put("pulse_after", item.optInt("pulseAfter", 50).coerceIn(0, 100))
                            put("created_at", createdAt)
                        }
                        db.insert("relationship_interactions", null, values)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    private fun interactionExists(clientId: Long, createdAt: Long, signal: String): Boolean {
        readableDatabase.query(
            "relationship_interactions", arrayOf("id"),
            "client_id=? AND created_at=? AND signal=?",
            arrayOf(clientId.toString(), createdAt.toString(), signal), null, null, null, "1"
        ).use { return it.moveToFirst() }
    }

    private fun ensureIdentity(client: ClientRecord) {
        if (client.ge360Id.isBlank()) client.ge360Id = UUID.randomUUID().toString()
    }

    private fun toValues(c: ClientRecord) = ContentValues().apply {
        put("ge360_id", c.ge360Id)
        put("ge360_jobsite_id", c.ge360JobsiteId)
        put("first_name", c.firstName.trim())
        put("last_name", c.lastName.trim())
        put("phone", c.phone.trim())
        put("email", c.email.trim())
        put("address", c.address.trim())
        put("notes", c.notes.trim())
        put("contact_uri", c.contactUri)
        put("temperature", c.temperature.ifBlank { "Da coltivare" })
        put("ai_temperature", c.aiTemperature.ifBlank { "Non analizzata" })
        put("relationship_phase", c.relationshipPhase.ifBlank { "Nuovo contatto" })
        put("pulse", c.pulse.coerceIn(0, 100))
        put("birthday", c.birthday.trim())
        put("follow_up", c.followUp.trim())
        put("last_interaction_at", c.lastInteractionAt)
        put("created_at", c.createdAt)
        put("updated_at", c.updatedAt)
    }

    private fun fromCursor(c: Cursor) = ClientRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        ge360JobsiteId = c.getString(c.getColumnIndexOrThrow("ge360_jobsite_id")),
        firstName = c.getString(c.getColumnIndexOrThrow("first_name")),
        lastName = c.getString(c.getColumnIndexOrThrow("last_name")),
        phone = c.getString(c.getColumnIndexOrThrow("phone")),
        email = c.getString(c.getColumnIndexOrThrow("email")),
        address = c.getString(c.getColumnIndexOrThrow("address")),
        notes = c.getString(c.getColumnIndexOrThrow("notes")),
        contactUri = c.getString(c.getColumnIndexOrThrow("contact_uri")),
        temperature = c.getString(c.getColumnIndexOrThrow("temperature")),
        aiTemperature = c.getString(c.getColumnIndexOrThrow("ai_temperature")),
        relationshipPhase = c.getString(c.getColumnIndexOrThrow("relationship_phase")),
        pulse = c.getInt(c.getColumnIndexOrThrow("pulse")),
        birthday = c.getString(c.getColumnIndexOrThrow("birthday")),
        followUp = c.getString(c.getColumnIndexOrThrow("follow_up")),
        lastInteractionAt = c.getLong(c.getColumnIndexOrThrow("last_interaction_at")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun toJson(c: ClientRecord) = JSONObject()
        .put("backupId", c.id)
        .put("ge360Id", c.ge360Id)
        .put("ge360JobsiteId", c.ge360JobsiteId)
        .put("firstName", c.firstName)
        .put("lastName", c.lastName)
        .put("phone", c.phone)
        .put("email", c.email)
        .put("address", c.address)
        .put("notes", c.notes)
        .put("contactUri", c.contactUri)
        .put("temperature", c.temperature)
        .put("aiTemperature", c.aiTemperature)
        .put("relationshipPhase", c.relationshipPhase)
        .put("pulse", c.pulse)
        .put("birthday", c.birthday)
        .put("followUp", c.followUp)
        .put("lastInteractionAt", c.lastInteractionAt)
        .put("createdAt", c.createdAt)
        .put("updatedAt", c.updatedAt)

    private fun fromJson(o: JSONObject) = ClientRecord(
        ge360Id = o.optString("ge360Id", ""),
        ge360JobsiteId = o.optString("ge360JobsiteId", ""),
        firstName = o.optString("firstName", ""),
        lastName = o.optString("lastName", ""),
        phone = o.optString("phone", ""),
        email = o.optString("email", ""),
        address = o.optString("address", ""),
        notes = o.optString("notes", ""),
        contactUri = o.optString("contactUri", ""),
        temperature = o.optString("temperature", "Da coltivare"),
        aiTemperature = o.optString("aiTemperature", "Non analizzata"),
        relationshipPhase = o.optString("relationshipPhase", "Nuovo contatto"),
        pulse = o.optInt("pulse", 50).coerceIn(0, 100),
        birthday = o.optString("birthday", ""),
        followUp = o.optString("followUp", ""),
        lastInteractionAt = o.optLong("lastInteractionAt", 0),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    companion object {
        private const val DB_NAME = "ge360_clienti.db"
        private const val DB_VERSION = 1

        fun normalizePhone(phone: String): String {
            var value = PhoneNumberUtils.normalizeNumber(phone.trim()) ?: ""
            if (value.startsWith("00")) value = "+" + value.drop(2)
            return value
        }
    }
}

object ClientBackupManager {
    private const val PREFS = "ge360_clienti_backup"
    private const val DRIVE_URI = "drive_uri"
    private const val JOB_ID = 360101
    private const val KEEP_LOCAL = 10

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, ClientBackupJobService::class.java))
            .setPersisted(true)
            .setPeriodic(24L * 60L * 60L * 1000L)
            .build()
        scheduler.schedule(job)
    }

    fun configureDrive(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DRIVE_URI, uri.toString()).apply()
    }

    fun perform(context: Context): String {
        val json = ClientStore(context).let { store ->
            try { store.exportJson() } finally { store.close() }
        }
        val file = writeLocal(context, json)
        rotateLocal(context)
        val drive = copyToDrive(context, file.name, json)
        return if (drive) "Backup locale + Google Drive completato" else "Backup locale completato"
    }

    fun latestLocal(context: Context): File? = backupDir(context).listFiles()
        ?.filter { it.isFile && it.extension.equals("json", true) }
        ?.maxByOrNull { it.lastModified() }

    fun restoreLatest(context: Context, replaceAll: Boolean): Int {
        val file = latestLocal(context) ?: return 0
        val store = ClientStore(context)
        return try { store.importJson(file.readText(Charsets.UTF_8), replaceAll) } finally { store.close() }
    }

    private fun writeLocal(context: Context, json: String): File {
        val name = "GE360-Clienti_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ITALY).format(Date())}.json"
        return File(backupDir(context), name).apply { writeText(json, Charsets.UTF_8) }
    }

    private fun backupDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(root, "GE360/Backups/Clienti").apply { mkdirs() }
    }

    private fun rotateLocal(context: Context) {
        backupDir(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_LOCAL)
            ?.forEach { it.delete() }
    }

    private fun copyToDrive(context: Context, name: String, json: String): Boolean {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DRIVE_URI, null) ?: return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(value)) ?: return false
            val folder = tree.findFile("GE360") ?: tree.createDirectory("GE360") ?: return false
            val backups = folder.findFile("Backups") ?: folder.createDirectory("Backups") ?: return false
            val clienti = backups.findFile("Clienti") ?: backups.createDirectory("Clienti") ?: return false
            val doc = clienti.createFile("application/json", name) ?: return false
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: return false
            clienti.listFiles()
                .filter { it.isFile && it.name?.endsWith(".json", true) == true }
                .sortedByDescending { it.lastModified() }
                .drop(KEEP_LOCAL)
                .forEach { runCatching { it.delete() } }
            true
        } catch (_: Exception) {
            false
        }
    }
}

class ClientBackupJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            runCatching { ClientBackupManager.perform(applicationContext) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
