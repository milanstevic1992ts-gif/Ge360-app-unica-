package it.edilmilan.ge360.centrocantiere

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class JobsiteArchiveFile(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var category: String = JobsiteArchiveCategories.PLAN,
    var title: String = "",
    var note: String = "",
    var filePath: String = "",
    var mimeType: String = "application/pdf",
    var source: String = JobsiteArchiveSources.UPLOAD,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class JobsiteMemo(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var type: String = JobsiteMemoTypes.CLIENT,
    var text: String = "",
    var pinned: Boolean = false,
    var priority: String = JobsiteMemoPriorities.NORMAL,
    var author: String = "",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

object JobsiteArchiveCategories {
    const val PLAN = "PLANIMETRIA"
    const val CLIENT_DOCUMENT = "DOCUMENTO_CLIENTE"
    val ALL = listOf(PLAN, CLIENT_DOCUMENT)
}

object JobsiteArchiveSources {
    const val UPLOAD = "CARICATO"
    const val SCAN = "SCANSIONE"
}

object JobsiteMemoTypes {
    const val CLIENT = "CLIENTE"
    const val INTERNAL = "INTERNO"
    val ALL = listOf(CLIENT, INTERNAL)
}

object JobsiteMemoPriorities {
    const val NORMAL = "Normale"
    const val IMPORTANT = "Importante"
    const val URGENT = "Urgente"
    val ALL = listOf(NORMAL, IMPORTANT, URGENT)
}

class JobsiteArchiveStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $FILES(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                jobsite_code TEXT NOT NULL DEFAULT '',
                jobsite_title TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                file_path TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL DEFAULT 'application/pdf',
                source TEXT NOT NULL DEFAULT 'CARICATO',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE $MEMOS(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ge360_id TEXT NOT NULL UNIQUE,
                jobsite_id TEXT NOT NULL,
                type TEXT NOT NULL,
                text TEXT NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0,
                priority TEXT NOT NULL DEFAULT 'Normale',
                author TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_archive_job ON $FILES(jobsite_id, category, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_memo_job ON $MEMOS(jobsite_id, type, pinned DESC, updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun files(jobsiteId: String, category: String = ""): List<JobsiteArchiveFile> {
        if (jobsiteId.isBlank()) return emptyList()
        val where = if (category.isBlank()) "jobsite_id=?" else "jobsite_id=? AND category=?"
        val args = if (category.isBlank()) arrayOf(jobsiteId) else arrayOf(jobsiteId, category)
        val out = mutableListOf<JobsiteArchiveFile>()
        readableDatabase.query(FILES, null, where, args, null, null, "updated_at DESC, id DESC").use { c ->
            while (c.moveToNext()) out += fileFromCursor(c)
        }
        return out
    }

    fun allFiles(): List<JobsiteArchiveFile> {
        val out = mutableListOf<JobsiteArchiveFile>()
        readableDatabase.query(FILES, null, null, null, null, null, "updated_at DESC, id DESC").use { c ->
            while (c.moveToNext()) out += fileFromCursor(c)
        }
        return out
    }

    fun saveFile(value: JobsiteArchiveFile): Long {
        require(value.jobsiteId.isNotBlank()) { "Cantiere obbligatorio" }
        require(value.category in JobsiteArchiveCategories.ALL) { "Categoria archivio non valida" }
        val now = System.currentTimeMillis()
        if (value.ge360Id.isBlank()) value.ge360Id = UUID.randomUUID().toString()
        if (value.createdAt <= 0) value.createdAt = now
        value.updatedAt = now
        return if (value.id > 0) {
            writableDatabase.update(FILES, fileValues(value), "id=?", arrayOf(value.id.toString()))
            value.id
        } else {
            writableDatabase.insertOrThrow(FILES, null, fileValues(value)).also { value.id = it }
        }
    }

    fun deleteFile(value: JobsiteArchiveFile) {
        writableDatabase.delete(FILES, "id=?", arrayOf(value.id.toString()))
    }

    fun memos(jobsiteId: String, type: String): List<JobsiteMemo> {
        if (jobsiteId.isBlank() || type !in JobsiteMemoTypes.ALL) return emptyList()
        val out = mutableListOf<JobsiteMemo>()
        readableDatabase.query(
            MEMOS, null, "jobsite_id=? AND type=?", arrayOf(jobsiteId, type), null, null,
            "pinned DESC, CASE priority WHEN 'Urgente' THEN 0 WHEN 'Importante' THEN 1 ELSE 2 END, updated_at DESC"
        ).use { c -> while (c.moveToNext()) out += memoFromCursor(c) }
        return out
    }

    fun allMemos(): List<JobsiteMemo> {
        val out = mutableListOf<JobsiteMemo>()
        readableDatabase.query(MEMOS, null, null, null, null, null, "updated_at DESC, id DESC").use { c ->
            while (c.moveToNext()) out += memoFromCursor(c)
        }
        return out
    }

    fun saveMemo(value: JobsiteMemo): Long {
        require(value.jobsiteId.isNotBlank()) { "Cantiere obbligatorio" }
        require(value.type in JobsiteMemoTypes.ALL) { "Tipo appunto non valido" }
        require(value.text.trim().isNotBlank()) { "Appunto vuoto" }
        if (value.priority !in JobsiteMemoPriorities.ALL) value.priority = JobsiteMemoPriorities.NORMAL
        val now = System.currentTimeMillis()
        if (value.ge360Id.isBlank()) value.ge360Id = UUID.randomUUID().toString()
        if (value.createdAt <= 0) value.createdAt = now
        value.updatedAt = now
        return if (value.id > 0) {
            writableDatabase.update(MEMOS, memoValues(value), "id=?", arrayOf(value.id.toString()))
            value.id
        } else {
            writableDatabase.insertOrThrow(MEMOS, null, memoValues(value)).also { value.id = it }
        }
    }

    fun deleteMemo(value: JobsiteMemo) {
        writableDatabase.delete(MEMOS, "id=?", arrayOf(value.id.toString()))
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", "ge360.jobsite-archive.v1")
        put("exportedAt", System.currentTimeMillis())
        put("files", JSONArray().apply { allFiles().forEach { put(fileToJson(it)) } })
        put("memos", JSONArray().apply { allMemos().forEach { put(memoToJson(it)) } })
    }.toString(2)

    fun importJson(json: String, replaceAll: Boolean, restoredPaths: Map<String, String> = emptyMap()): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == "ge360.jobsite-archive.v1") { "Formato archivio cantiere non riconosciuto" }
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete(FILES, null, null)
                db.delete(MEMOS, null, null)
            }
            root.optJSONArray("files")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val incoming = fileFromJson(rows.getJSONObject(i))
                    val existingId = findId(FILES, incoming.ge360Id)
                    if (existingId > 0) incoming.id = existingId
                    restoredPaths[incoming.ge360Id]?.let { incoming.filePath = it }
                    saveFile(incoming)
                    count++
                }
            }
            root.optJSONArray("memos")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val incoming = memoFromJson(rows.getJSONObject(i))
                    val existingId = findId(MEMOS, incoming.ge360Id)
                    if (existingId > 0) incoming.id = existingId
                    saveMemo(incoming)
                    count++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    private fun findId(table: String, ge360Id: String): Long {
        if (ge360Id.isBlank()) return 0
        return readableDatabase.query(table, arrayOf("id"), "ge360_id=?", arrayOf(ge360Id), null, null, null, "1")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    private fun fileValues(v: JobsiteArchiveFile) = ContentValues().apply {
        put("ge360_id", v.ge360Id)
        put("jobsite_id", v.jobsiteId)
        put("jobsite_code", v.jobsiteCode)
        put("jobsite_title", v.jobsiteTitle)
        put("category", v.category)
        put("title", v.title.trim())
        put("note", v.note.trim())
        put("file_path", v.filePath)
        put("mime_type", v.mimeType.ifBlank { "application/pdf" })
        put("source", v.source)
        put("created_at", v.createdAt)
        put("updated_at", v.updatedAt)
    }

    private fun memoValues(v: JobsiteMemo) = ContentValues().apply {
        put("ge360_id", v.ge360Id)
        put("jobsite_id", v.jobsiteId)
        put("type", v.type)
        put("text", v.text.trim())
        put("pinned", if (v.pinned) 1 else 0)
        put("priority", v.priority)
        put("author", v.author.trim())
        put("created_at", v.createdAt)
        put("updated_at", v.updatedAt)
    }

    private fun fileFromCursor(c: Cursor) = JobsiteArchiveFile(
        id = c.long("id"), ge360Id = c.string("ge360_id"), jobsiteId = c.string("jobsite_id"),
        jobsiteCode = c.string("jobsite_code"), jobsiteTitle = c.string("jobsite_title"), category = c.string("category"),
        title = c.string("title"), note = c.string("note"), filePath = c.string("file_path"), mimeType = c.string("mime_type"),
        source = c.string("source"), createdAt = c.long("created_at"), updatedAt = c.long("updated_at")
    )

    private fun memoFromCursor(c: Cursor) = JobsiteMemo(
        id = c.long("id"), ge360Id = c.string("ge360_id"), jobsiteId = c.string("jobsite_id"), type = c.string("type"),
        text = c.string("text"), pinned = c.int("pinned") == 1, priority = c.string("priority"), author = c.string("author"),
        createdAt = c.long("created_at"), updatedAt = c.long("updated_at")
    )

    private fun fileToJson(v: JobsiteArchiveFile) = JSONObject().apply {
        put("ge360Id", v.ge360Id); put("jobsiteId", v.jobsiteId); put("jobsiteCode", v.jobsiteCode); put("jobsiteTitle", v.jobsiteTitle)
        put("category", v.category); put("title", v.title); put("note", v.note); put("filePath", v.filePath); put("mimeType", v.mimeType)
        put("source", v.source); put("createdAt", v.createdAt); put("updatedAt", v.updatedAt)
    }

    private fun memoToJson(v: JobsiteMemo) = JSONObject().apply {
        put("ge360Id", v.ge360Id); put("jobsiteId", v.jobsiteId); put("type", v.type); put("text", v.text); put("pinned", v.pinned)
        put("priority", v.priority); put("author", v.author); put("createdAt", v.createdAt); put("updatedAt", v.updatedAt)
    }

    private fun fileFromJson(o: JSONObject) = JobsiteArchiveFile(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId", ""),
        jobsiteCode = o.optString("jobsiteCode", ""), jobsiteTitle = o.optString("jobsiteTitle", ""),
        category = o.optString("category", JobsiteArchiveCategories.PLAN), title = o.optString("title", ""), note = o.optString("note", ""),
        filePath = o.optString("filePath", ""), mimeType = o.optString("mimeType", "application/pdf"),
        source = o.optString("source", JobsiteArchiveSources.UPLOAD), createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun memoFromJson(o: JSONObject) = JobsiteMemo(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()), jobsiteId = o.optString("jobsiteId", ""),
        type = o.optString("type", JobsiteMemoTypes.CLIENT), text = o.optString("text", ""), pinned = o.optBoolean("pinned", false),
        priority = o.optString("priority", JobsiteMemoPriorities.NORMAL), author = o.optString("author", ""),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))

    companion object {
        private const val DB_NAME = "ge360_jobsite_archive.db"
        private const val DB_VERSION = 1
        private const val FILES = "jobsite_archive_files"
        private const val MEMOS = "jobsite_memos"
    }
}
