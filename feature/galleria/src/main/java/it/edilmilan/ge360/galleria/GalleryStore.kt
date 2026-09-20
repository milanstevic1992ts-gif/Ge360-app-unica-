package it.edilmilan.ge360.galleria

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object GalleryPhases {
    const val BEFORE = "PRIMA"
    const val DURING = "DURANTE"
    const val AFTER = "DOPO"
    const val PROBLEMS = "PROBLEMI"
    const val DOCUMENTS = "DOCUMENTI"
    val ALL = listOf(BEFORE, DURING, AFTER, PROBLEMS, DOCUMENTS)
}

data class GalleryPhoto(
    var id: Long = 0,
    var ge360Id: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var phase: String = GalleryPhases.DURING,
    var localUri: String = "",
    var fileName: String = "",
    var mimeType: String = "image/jpeg",
    var sizeBytes: Long = 0,
    var sha256: String = "",
    var note: String = "",
    var tags: String = "",
    var favorite: Boolean = false,
    var driveUri: String = "",
    var driveStatus: String = DRIVE_PENDING,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object { const val DRIVE_PENDING = "IN_ATTESA" }
}

data class GallerySummary(
    val total: Int,
    val before: Int,
    val during: Int,
    val after: Int,
    val problems: Int,
    val documents: Int,
    val drivePending: Int
)

class GalleryStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE gallery_photos(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ge360_id TEXT NOT NULL UNIQUE,
            jobsite_id TEXT NOT NULL,
            jobsite_code TEXT NOT NULL DEFAULT '',
            jobsite_title TEXT NOT NULL DEFAULT '',
            phase TEXT NOT NULL DEFAULT 'DURANTE',
            local_uri TEXT NOT NULL,
            file_name TEXT NOT NULL DEFAULT '',
            mime_type TEXT NOT NULL DEFAULT 'image/jpeg',
            size_bytes INTEGER NOT NULL DEFAULT 0,
            sha256 TEXT NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            tags TEXT NOT NULL DEFAULT '',
            favorite INTEGER NOT NULL DEFAULT 0,
            drive_uri TEXT NOT NULL DEFAULT '',
            drive_status TEXT NOT NULL DEFAULT 'IN_ATTESA',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_gallery_job_phase ON gallery_photos(jobsite_id, phase, created_at DESC)")
        db.execSQL("CREATE INDEX idx_gallery_drive ON gallery_photos(drive_status, updated_at)")
        db.execSQL("CREATE INDEX idx_gallery_hash ON gallery_photos(sha256)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun list(jobsiteId: String = "", phase: String = "TUTTE", query: String = "", favoritesOnly: Boolean = false): List<GalleryPhoto> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (jobsiteId.isNotBlank()) { where += "jobsite_id=?"; args += jobsiteId }
        if (phase != "TUTTE") { where += "phase=?"; args += phase }
        if (favoritesOnly) where += "favorite=1"
        if (query.isNotBlank()) {
            val like = "%${query.trim()}%"
            where += "(file_name LIKE ? OR note LIKE ? OR tags LIKE ? OR jobsite_code LIKE ? OR jobsite_title LIKE ?)"
            repeat(5) { args += like }
        }
        val out = mutableListOf<GalleryPhoto>()
        readableDatabase.query(
            TABLE, null,
            where.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null, null, "created_at DESC"
        ).use { c -> while (c.moveToNext()) out += fromCursor(c) }
        return out
    }

    fun get(id: Long): GalleryPhoto? {
        readableDatabase.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun save(photo: GalleryPhoto): Long {
        val now = System.currentTimeMillis()
        if (photo.ge360Id.isBlank()) photo.ge360Id = UUID.randomUUID().toString()
        if (photo.createdAt <= 0) photo.createdAt = now
        photo.updatedAt = now
        val values = ContentValues().apply {
            put("ge360_id", photo.ge360Id)
            put("jobsite_id", photo.jobsiteId)
            put("jobsite_code", photo.jobsiteCode)
            put("jobsite_title", photo.jobsiteTitle)
            put("phase", photo.phase)
            put("local_uri", photo.localUri)
            put("file_name", photo.fileName)
            put("mime_type", photo.mimeType)
            put("size_bytes", photo.sizeBytes)
            put("sha256", photo.sha256)
            put("note", photo.note.trim())
            put("tags", photo.tags.trim())
            put("favorite", if (photo.favorite) 1 else 0)
            put("drive_uri", photo.driveUri)
            put("drive_status", photo.driveStatus)
            put("created_at", photo.createdAt)
            put("updated_at", photo.updatedAt)
        }
        return if (photo.id > 0) {
            writableDatabase.update(TABLE, values, "id=?", arrayOf(photo.id.toString()))
            photo.id
        } else {
            writableDatabase.insertOrThrow(TABLE, null, values).also { photo.id = it }
        }
    }

    fun deleteMetadata(id: Long) { writableDatabase.delete(TABLE, "id=?", arrayOf(id.toString())) }

    fun findByHash(hash: String): GalleryPhoto? {
        if (hash.isBlank()) return null
        readableDatabase.query(TABLE, null, "sha256=?", arrayOf(hash), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun pendingDrive(jobsiteId: String = ""): List<GalleryPhoto> = list(jobsiteId).filter { it.driveStatus != DRIVE_OK || it.driveUri.isBlank() }

    fun summary(jobsiteId: String): GallerySummary {
        val rows = list(jobsiteId)
        return GallerySummary(
            total = rows.size,
            before = rows.count { it.phase == GalleryPhases.BEFORE },
            during = rows.count { it.phase == GalleryPhases.DURING },
            after = rows.count { it.phase == GalleryPhases.AFTER },
            problems = rows.count { it.phase == GalleryPhases.PROBLEMS },
            documents = rows.count { it.phase == GalleryPhases.DOCUMENTS },
            drivePending = rows.count { it.driveStatus != DRIVE_OK || it.driveUri.isBlank() }
        )
    }

    fun exportJson(): String {
        val root = JSONObject()
            .put("schema", "ge360.galleria.v1")
            .put("exportedAt", System.currentTimeMillis())
            .put("filesIncluded", false)
        val rows = JSONArray()
        list().forEach { rows.put(toJson(it)) }
        root.put("photos", rows)
        return root.toString(2)
    }

    fun importMetadata(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == "ge360.galleria.v1") { "Formato Galleria non riconosciuto" }
        val photos = root.optJSONArray("photos") ?: JSONArray()
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            if (replaceAll) db.delete(TABLE, null, null)
            for (i in 0 until photos.length()) {
                val incoming = fromJson(photos.getJSONObject(i))
                findByGe360(incoming.ge360Id)?.let { incoming.id = it.id }
                save(incoming)
                count++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    private fun findByGe360(id: String): GalleryPhoto? {
        if (id.isBlank()) return null
        readableDatabase.query(TABLE, null, "ge360_id=?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    private fun fromCursor(c: Cursor) = GalleryPhoto(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ge360Id = c.getString(c.getColumnIndexOrThrow("ge360_id")),
        jobsiteId = c.getString(c.getColumnIndexOrThrow("jobsite_id")),
        jobsiteCode = c.getString(c.getColumnIndexOrThrow("jobsite_code")),
        jobsiteTitle = c.getString(c.getColumnIndexOrThrow("jobsite_title")),
        phase = c.getString(c.getColumnIndexOrThrow("phase")),
        localUri = c.getString(c.getColumnIndexOrThrow("local_uri")),
        fileName = c.getString(c.getColumnIndexOrThrow("file_name")),
        mimeType = c.getString(c.getColumnIndexOrThrow("mime_type")),
        sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes")),
        sha256 = c.getString(c.getColumnIndexOrThrow("sha256")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        tags = c.getString(c.getColumnIndexOrThrow("tags")),
        favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1,
        driveUri = c.getString(c.getColumnIndexOrThrow("drive_uri")),
        driveStatus = c.getString(c.getColumnIndexOrThrow("drive_status")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"))
    )

    private fun toJson(p: GalleryPhoto) = JSONObject()
        .put("ge360Id", p.ge360Id)
        .put("jobsiteId", p.jobsiteId)
        .put("jobsiteCode", p.jobsiteCode)
        .put("jobsiteTitle", p.jobsiteTitle)
        .put("phase", p.phase)
        .put("localUri", p.localUri)
        .put("fileName", p.fileName)
        .put("mimeType", p.mimeType)
        .put("sizeBytes", p.sizeBytes)
        .put("sha256", p.sha256)
        .put("note", p.note)
        .put("tags", p.tags)
        .put("favorite", p.favorite)
        .put("driveUri", p.driveUri)
        .put("driveStatus", p.driveStatus)
        .put("createdAt", p.createdAt)
        .put("updatedAt", p.updatedAt)

    private fun fromJson(o: JSONObject) = GalleryPhoto(
        ge360Id = o.optString("ge360Id", UUID.randomUUID().toString()),
        jobsiteId = o.optString("jobsiteId"),
        jobsiteCode = o.optString("jobsiteCode"),
        jobsiteTitle = o.optString("jobsiteTitle"),
        phase = o.optString("phase", GalleryPhases.DURING),
        localUri = o.optString("localUri"),
        fileName = o.optString("fileName"),
        mimeType = o.optString("mimeType", "image/jpeg"),
        sizeBytes = o.optLong("sizeBytes", 0),
        sha256 = o.optString("sha256"),
        note = o.optString("note"),
        tags = o.optString("tags"),
        favorite = o.optBoolean("favorite", false),
        driveUri = o.optString("driveUri"),
        driveStatus = o.optString("driveStatus", GalleryPhoto.DRIVE_PENDING),
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
    )

    companion object {
        const val DRIVE_OK = "OK"
        const val DRIVE_ERROR = "ERRORE"
        private const val TABLE = "gallery_photos"
        private const val DB_NAME = "ge360_galleria.db"
        private const val DB_VERSION = 1
    }
}
