package it.edilmilan.ge360.messaggi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject


data class MessageTemplate(
    var id: Long = 0,
    var title: String = "",
    var body: String = "",
    var category: String = "Altro",
    var tags: String = "",
    var favorite: Boolean = false,
    var usageCount: Int = 0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var lastUsedAt: Long = 0
)

class TemplateStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'Altro',
                tags TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0,
                usage_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_templates_category ON templates(category)")
        db.execSQL("CREATE INDEX idx_templates_usage ON templates(usage_count DESC)")
        db.execSQL("CREATE INDEX idx_templates_last_used ON templates(last_used_at DESC)")
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun search(query: String = "", mode: String = "Tutti"): List<MessageTemplate> {
        val q = query.trim()
        val all = mutableListOf<MessageTemplate>()
        readableDatabase.query(TABLE, null, null, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) all += fromCursor(c)
        }
        var rows = if (q.isBlank()) all else all.filter {
            listOf(it.title, it.body, it.category, it.tags).any { value -> value.contains(q, ignoreCase = true) }
        }
        rows = when (mode) {
            "Preferiti" -> rows.filter { it.favorite }
            "Recenti" -> rows.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }
            "Più usati" -> rows.sortedByDescending { it.usageCount }
            "WhatsApp", "Prompt AI", "Cantiere", "Preventivi", "Social", "Altro" -> rows.filter { it.category.equals(mode, true) }
            else -> rows
        }
        return rows
    }

    fun get(id: Long): MessageTemplate? {
        readableDatabase.query(TABLE, null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun save(template: MessageTemplate): Long {
        val now = System.currentTimeMillis()
        if (template.createdAt <= 0) template.createdAt = now
        template.updatedAt = now
        val values = ContentValues().apply {
            put("title", template.title.trim())
            put("body", template.body.trim())
            put("category", template.category.trim().ifEmpty { "Altro" })
            put("tags", template.tags.trim())
            put("favorite", if (template.favorite) 1 else 0)
            put("usage_count", template.usageCount)
            put("created_at", template.createdAt)
            put("updated_at", template.updatedAt)
            put("last_used_at", template.lastUsedAt)
        }
        return if (template.id > 0) {
            writableDatabase.update(TABLE, values, "id=?", arrayOf(template.id.toString()))
            template.id
        } else {
            writableDatabase.insertOrThrow(TABLE, null, values).also { template.id = it }
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE, "id=?", arrayOf(id.toString()))
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        ContentValues().also {
            it.put("favorite", if (favorite) 1 else 0)
            it.put("updated_at", System.currentTimeMillis())
            writableDatabase.update(TABLE, it, "id=?", arrayOf(id.toString()))
        }
    }

    fun markUsed(id: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET usage_count=usage_count+1,last_used_at=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), id)
        )
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "GE360")
        root.put("module", "messaggi")
        root.put("formatVersion", 1)
        root.put("exportedAt", System.currentTimeMillis())
        val rows = JSONArray()
        search().forEach { t ->
            rows.put(JSONObject().apply {
                put("title", t.title)
                put("body", t.body)
                put("category", t.category)
                put("tags", t.tags)
                put("favorite", t.favorite)
                put("usage_count", t.usageCount)
                put("created_at", t.createdAt)
                put("updated_at", t.updatedAt)
                put("last_used_at", t.lastUsedAt)
            })
        }
        root.put("templates", rows)
        return root.toString(2)
    }

    fun importJson(json: String, replaceAll: Boolean = false): Int {
        val root = JSONObject(json)
        val items = root.getJSONArray("templates")
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            if (replaceAll) db.delete(TABLE, null, null)
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val incoming = MessageTemplate(
                    title = o.optString("title", ""),
                    body = o.optString("body", ""),
                    category = o.optString("category", "Altro"),
                    tags = o.optString("tags", ""),
                    favorite = o.optBoolean("favorite", false),
                    usageCount = o.optInt("usage_count", 0),
                    createdAt = o.optLong("created_at", System.currentTimeMillis()),
                    updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
                    lastUsedAt = o.optLong("last_used_at", 0)
                )
                val duplicate = findDuplicate(incoming.title, incoming.body)
                if (duplicate != null) incoming.id = duplicate.id
                save(incoming)
                imported++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return imported
    }

    private fun findDuplicate(title: String, body: String): MessageTemplate? =
        search().firstOrNull { it.title.equals(title.trim(), true) && it.body.trim() == body.trim() }

    private fun fromCursor(c: Cursor) = MessageTemplate(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        body = c.getString(c.getColumnIndexOrThrow("body")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        tags = c.getString(c.getColumnIndexOrThrow("tags")),
        favorite = c.getInt(c.getColumnIndexOrThrow("favorite")) == 1,
        usageCount = c.getInt(c.getColumnIndexOrThrow("usage_count")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
        lastUsedAt = c.getLong(c.getColumnIndexOrThrow("last_used_at"))
    )

    private fun seed(db: SQLiteDatabase) {
        val defaults = listOf(
            MessageTemplate(
                title = "Conferma sopralluogo",
                body = "Buongiorno {{nome}}, confermo il sopralluogo per {{data}} alle {{ora}} presso {{indirizzo}}.",
                category = "WhatsApp",
                tags = "cliente, sopralluogo, appuntamento",
                favorite = true
            ),
            MessageTemplate(
                title = "Invio preventivo",
                body = "Buongiorno {{nome}}, come concordato le invio il preventivo relativo a {{lavoro}} presso {{indirizzo}}. Rimango a disposizione per qualsiasi chiarimento.",
                category = "Preventivi",
                tags = "cliente, preventivo, invio",
                favorite = true
            ),
            MessageTemplate(
                title = "Richiesta recensione",
                body = "Buongiorno {{nome}}, grazie ancora per averci affidato i lavori. Se si è trovato bene, una recensione ci farebbe davvero piacere: {{link}}",
                category = "WhatsApp",
                tags = "cliente, recensione, fine lavori",
                favorite = true
            ),
            MessageTemplate(
                title = "Aggiornamento cantiere",
                body = "Buonasera {{nome}}, aggiornamento del cantiere {{codice_cantiere}}: {{aggiornamento}}. Domani proseguiremo con {{prossimo_lavoro}}.",
                category = "Cantiere",
                tags = "cliente, aggiornamento, cantiere"
            ),
            MessageTemplate(
                title = "Prompt analisi preventivo",
                body = "Analizza questo preventivo per {{lavoro}}. Controlla prezzi, lavorazioni mancanti, margine, rischi e possibili miglioramenti. Testo: {{testo}}",
                category = "Prompt AI",
                tags = "chatgpt, claude, preventivo, analisi"
            )
        )
        defaults.forEach { t ->
            val values = ContentValues().apply {
                put("title", t.title); put("body", t.body); put("category", t.category); put("tags", t.tags)
                put("favorite", if (t.favorite) 1 else 0); put("usage_count", 0)
                put("created_at", t.createdAt); put("updated_at", t.updatedAt); put("last_used_at", 0)
            }
            db.insert(TABLE, null, values)
        }
    }

    companion object {
        private const val TABLE = "templates"
        private const val DB_NAME = "ge360_messaggi.db"
        private const val DB_VERSION = 1
    }
}
