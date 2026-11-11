package it.edilmilan.ge360.preventivi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class EstimateHistoryEvent(
    val id: Long,
    val estimateGe360Id: String,
    val type: String,
    val title: String,
    val detail: String,
    val createdAt: Long
)

data class EstimateRelation(
    val childGe360Id: String,
    val parentGe360Id: String,
    val type: String,
    val sequence: Int,
    val createdAt: Long
)

object EstimateRelationTypes {
    const val REVISION = "Revisione"
    const val EXTRA = "Extra"
    const val VARIANT = "Variante"
    val ALL = listOf(REVISION, EXTRA, VARIANT)
}

class EstimateLifecycleStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "ge360_preventivi_lifecycle.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE estimate_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                estimate_ge360_id TEXT NOT NULL,
                event_type TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                snapshot_hash TEXT NOT NULL DEFAULT '',
                snapshot_json TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_est_event_estimate ON estimate_events(estimate_ge360_id, created_at DESC)")
        db.execSQL(
            """CREATE TABLE accepted_snapshots(
                estimate_ge360_id TEXT PRIMARY KEY,
                accepted_at INTEGER NOT NULL,
                signer TEXT NOT NULL DEFAULT '',
                acceptance_note TEXT NOT NULL DEFAULT '',
                snapshot_hash TEXT NOT NULL,
                snapshot_json TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE estimate_relations(
                child_ge360_id TEXT PRIMARY KEY,
                parent_ge360_id TEXT NOT NULL,
                relation_type TEXT NOT NULL,
                sequence_no INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_est_relation_parent ON estimate_relations(parent_ge360_id, sequence_no)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun isLocked(estimateGe360Id: String): Boolean {
        if (estimateGe360Id.isBlank()) return false
        return readableDatabase.rawQuery(
            "SELECT 1 FROM accepted_snapshots WHERE estimate_ge360_id=? LIMIT 1",
            arrayOf(estimateGe360Id)
        ).use { it.moveToFirst() }
    }

    fun acceptedSnapshot(estimateGe360Id: String): String? = readableDatabase.query(
        "accepted_snapshots",
        arrayOf("snapshot_json"),
        "estimate_ge360_id=?",
        arrayOf(estimateGe360Id),
        null,
        null,
        null,
        "1"
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun freezeAccepted(record: EstimateRecord, signer: String = "", note: String = "") {
        require(record.ge360Id.isNotBlank()) { "Preventivo senza ge360Id" }
        require(!isLocked(record.ge360Id)) { "Accordo già congelato: lo snapshot non può essere sovrascritto" }
        val snapshot = snapshotJson(record)
        val values = ContentValues().apply {
            put("estimate_ge360_id", record.ge360Id)
            put("accepted_at", System.currentTimeMillis())
            put("signer", signer.trim())
            put("acceptance_note", note.trim())
            put("snapshot_hash", sha256(snapshot))
            put("snapshot_json", snapshot)
        }
        writableDatabase.insertWithOnConflict(
            "accepted_snapshots",
            null,
            values,
            SQLiteDatabase.CONFLICT_ABORT
        )
        recordEvent(record, "ACCEPTED", "Preventivo accettato e congelato", buildString {
            if (signer.isNotBlank()) append("Accettato da: ").append(signer.trim())
            if (note.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(note.trim())
            }
        }, force = true)
    }

    fun recordEvent(
        record: EstimateRecord,
        type: String,
        title: String,
        detail: String = "",
        force: Boolean = false
    ): Boolean {
        if (record.ge360Id.isBlank()) return false
        val snapshot = snapshotJson(record)
        val hash = sha256(snapshot)
        if (!force) {
            val lastHash = readableDatabase.rawQuery(
                "SELECT snapshot_hash FROM estimate_events WHERE estimate_ge360_id=? ORDER BY created_at DESC, id DESC LIMIT 1",
                arrayOf(record.ge360Id)
            ).use { if (it.moveToFirst()) it.getString(0) else "" }
            if (lastHash == hash) return false
        }
        val v = ContentValues().apply {
            put("estimate_ge360_id", record.ge360Id)
            put("event_type", type)
            put("title", title)
            put("detail", detail)
            put("snapshot_hash", hash)
            put("snapshot_json", snapshot)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("estimate_events", null, v)
        return true
    }

    fun recordSmartSave(record: EstimateRecord, title: String = "Preventivo aggiornato"): Boolean {
        val previous = latestSnapshot(record.ge360Id)
        val detail = if (previous.isNullOrBlank()) {
            "Prima versione registrata · ${record.items.size} voci · totale ${record.finalTotal}"
        } else diffSummary(previous, snapshotJson(record))
        return recordEvent(record, "SAVE", title, detail)
    }

    fun history(estimateGe360Id: String): List<EstimateHistoryEvent> {
        if (estimateGe360Id.isBlank()) return emptyList()
        val out = mutableListOf<EstimateHistoryEvent>()
        readableDatabase.query(
            "estimate_events",
            arrayOf("id", "estimate_ge360_id", "event_type", "title", "detail", "created_at"),
            "estimate_ge360_id=?",
            arrayOf(estimateGe360Id),
            null,
            null,
            "created_at DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += EstimateHistoryEvent(
                    id = c.getLong(0),
                    estimateGe360Id = c.getString(1),
                    type = c.getString(2),
                    title = c.getString(3),
                    detail = c.getString(4),
                    createdAt = c.getLong(5)
                )
            }
        }
        return out
    }

    fun link(childGe360Id: String, parentGe360Id: String, type: String): EstimateRelation {
        require(childGe360Id.isNotBlank() && parentGe360Id.isNotBlank()) { "Relazione preventivo incompleta" }
        val sequence = readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(sequence_no),0)+1 FROM estimate_relations WHERE parent_ge360_id=? AND relation_type=?",
            arrayOf(parentGe360Id, type)
        ).use { if (it.moveToFirst()) it.getInt(0) else 1 }
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict(
            "estimate_relations",
            null,
            ContentValues().apply {
                put("child_ge360_id", childGe360Id)
                put("parent_ge360_id", parentGe360Id)
                put("relation_type", type)
                put("sequence_no", sequence)
                put("created_at", now)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return EstimateRelation(childGe360Id, parentGe360Id, type, sequence, now)
    }

    fun relationForChild(childGe360Id: String): EstimateRelation? = readableDatabase.query(
        "estimate_relations",
        null,
        "child_ge360_id=?",
        arrayOf(childGe360Id),
        null,
        null,
        null,
        "1"
    ).use { c ->
        if (!c.moveToFirst()) null else EstimateRelation(
            childGe360Id = c.getString(c.getColumnIndexOrThrow("child_ge360_id")),
            parentGe360Id = c.getString(c.getColumnIndexOrThrow("parent_ge360_id")),
            type = c.getString(c.getColumnIndexOrThrow("relation_type")),
            sequence = c.getInt(c.getColumnIndexOrThrow("sequence_no")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }

    fun children(parentGe360Id: String): List<EstimateRelation> {
        val out = mutableListOf<EstimateRelation>()
        readableDatabase.query(
            "estimate_relations",
            null,
            "parent_ge360_id=?",
            arrayOf(parentGe360Id),
            null,
            null,
            "sequence_no ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += EstimateRelation(
                    childGe360Id = c.getString(c.getColumnIndexOrThrow("child_ge360_id")),
                    parentGe360Id = c.getString(c.getColumnIndexOrThrow("parent_ge360_id")),
                    type = c.getString(c.getColumnIndexOrThrow("relation_type")),
                    sequence = c.getInt(c.getColumnIndexOrThrow("sequence_no")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return out
    }

    fun rootEstimate(estimateGe360Id: String): String {
        var current = estimateGe360Id
        val visited = mutableSetOf<String>()
        while (current.isNotBlank() && visited.add(current)) {
            val parent = relationForChild(current)?.parentGe360Id ?: return current
            current = parent
        }
        return estimateGe360Id
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("events", JSONArray().apply {
            readableDatabase.query("estimate_events", null, null, null, null, null, "created_at ASC, id ASC").use { c ->
                while (c.moveToNext()) put(JSONObject().apply {
                    put("estimateGe360Id", c.getString(c.getColumnIndexOrThrow("estimate_ge360_id")))
                    put("eventType", c.getString(c.getColumnIndexOrThrow("event_type")))
                    put("title", c.getString(c.getColumnIndexOrThrow("title")))
                    put("detail", c.getString(c.getColumnIndexOrThrow("detail")))
                    put("snapshotHash", c.getString(c.getColumnIndexOrThrow("snapshot_hash")))
                    put("snapshotJson", c.getString(c.getColumnIndexOrThrow("snapshot_json")))
                    put("createdAt", c.getLong(c.getColumnIndexOrThrow("created_at")))
                })
            }
        })
        put("acceptedSnapshots", JSONArray().apply {
            readableDatabase.query("accepted_snapshots", null, null, null, null, null, "accepted_at ASC").use { c ->
                while (c.moveToNext()) put(JSONObject().apply {
                    put("estimateGe360Id", c.getString(c.getColumnIndexOrThrow("estimate_ge360_id")))
                    put("acceptedAt", c.getLong(c.getColumnIndexOrThrow("accepted_at")))
                    put("signer", c.getString(c.getColumnIndexOrThrow("signer")))
                    put("acceptanceNote", c.getString(c.getColumnIndexOrThrow("acceptance_note")))
                    put("snapshotHash", c.getString(c.getColumnIndexOrThrow("snapshot_hash")))
                    put("snapshotJson", c.getString(c.getColumnIndexOrThrow("snapshot_json")))
                })
            }
        })
        put("relations", JSONArray().apply {
            readableDatabase.query("estimate_relations", null, null, null, null, null, "created_at ASC").use { c ->
                while (c.moveToNext()) put(JSONObject().apply {
                    put("childGe360Id", c.getString(c.getColumnIndexOrThrow("child_ge360_id")))
                    put("parentGe360Id", c.getString(c.getColumnIndexOrThrow("parent_ge360_id")))
                    put("relationType", c.getString(c.getColumnIndexOrThrow("relation_type")))
                    put("sequenceNo", c.getInt(c.getColumnIndexOrThrow("sequence_no")))
                    put("createdAt", c.getLong(c.getColumnIndexOrThrow("created_at")))
                })
            }
        })
    }.toString(2)

    fun importJson(json: String, replace: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup cronologia preventivi non compatibile" }
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            if (replace) {
                db.delete("estimate_events", null, null)
                db.delete("accepted_snapshots", null, null)
                db.delete("estimate_relations", null, null)
            }
            val events = root.optJSONArray("events") ?: JSONArray()
            for (i in 0 until events.length()) {
                val row = events.getJSONObject(i)
                val exists = db.rawQuery(
                    "SELECT 1 FROM estimate_events WHERE estimate_ge360_id=? AND event_type=? AND snapshot_hash=? AND created_at=? LIMIT 1",
                    arrayOf(row.optString("estimateGe360Id"), row.optString("eventType"), row.optString("snapshotHash"), row.optLong("createdAt").toString())
                ).use { it.moveToFirst() }
                if (!exists) {
                    db.insertOrThrow("estimate_events", null, ContentValues().apply {
                        put("estimate_ge360_id", row.optString("estimateGe360Id"))
                        put("event_type", row.optString("eventType"))
                        put("title", row.optString("title"))
                        put("detail", row.optString("detail"))
                        put("snapshot_hash", row.optString("snapshotHash"))
                        put("snapshot_json", row.optString("snapshotJson"))
                        put("created_at", row.optLong("createdAt"))
                    })
                    count++
                }
            }
            val accepted = root.optJSONArray("acceptedSnapshots") ?: JSONArray()
            for (i in 0 until accepted.length()) {
                val row = accepted.getJSONObject(i)
                db.insertWithOnConflict("accepted_snapshots", null, ContentValues().apply {
                    put("estimate_ge360_id", row.optString("estimateGe360Id"))
                    put("accepted_at", row.optLong("acceptedAt"))
                    put("signer", row.optString("signer"))
                    put("acceptance_note", row.optString("acceptanceNote"))
                    put("snapshot_hash", row.optString("snapshotHash"))
                    put("snapshot_json", row.optString("snapshotJson"))
                }, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            val relations = root.optJSONArray("relations") ?: JSONArray()
            for (i in 0 until relations.length()) {
                val row = relations.getJSONObject(i)
                db.insertWithOnConflict("estimate_relations", null, ContentValues().apply {
                    put("child_ge360_id", row.optString("childGe360Id"))
                    put("parent_ge360_id", row.optString("parentGe360Id"))
                    put("relation_type", row.optString("relationType"))
                    put("sequence_no", row.optInt("sequenceNo", 1))
                    put("created_at", row.optLong("createdAt"))
                }, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    private fun latestSnapshot(estimateGe360Id: String): String? = readableDatabase.rawQuery(
        "SELECT snapshot_json FROM estimate_events WHERE estimate_ge360_id=? ORDER BY created_at DESC, id DESC LIMIT 1",
        arrayOf(estimateGe360Id)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun diffSummary(oldJson: String, newJson: String): String {
        val old = runCatching { JSONObject(oldJson) }.getOrNull() ?: return "Contenuto aggiornato"
        val new = runCatching { JSONObject(newJson) }.getOrNull() ?: return "Contenuto aggiornato"
        val changes = mutableListOf<String>()
        if (old.optString("status") != new.optString("status")) changes += "stato ${old.optString("status")} → ${new.optString("status")}"
        if (old.optString("title") != new.optString("title")) changes += "titolo modificato"
        val oldTotal = old.optDouble("finalTotal", 0.0)
        val newTotal = new.optDouble("finalTotal", 0.0)
        if (oldTotal != newTotal) changes += "totale $oldTotal → $newTotal"

        val oldItems = itemMap(old.optJSONArray("items"))
        val newItems = itemMap(new.optJSONArray("items"))
        val added = newItems.keys - oldItems.keys
        val removed = oldItems.keys - newItems.keys
        val changed = (oldItems.keys intersect newItems.keys).count { oldItems[it] != newItems[it] }
        if (added.isNotEmpty()) changes += "+${added.size} voci"
        if (removed.isNotEmpty()) changes += "-${removed.size} voci"
        if (changed > 0) changes += "$changed voci modificate"
        return changes.joinToString(" · ").ifBlank { "Aggiornamento metadati" }
    }

    private fun itemMap(items: JSONArray?): Map<String, String> {
        if (items == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (i in 0 until items.length()) {
            val x = items.optJSONObject(i) ?: continue
            val key = x.optString("ge360Id").ifBlank { "${x.optString("room")}|${x.optString("name")}|$i" }
            out[key] = listOf(
                x.optString("room"), x.optString("name"), x.optDouble("quantity"), x.optString("unit"),
                x.optDouble("unitPrice"), x.optBoolean("included"), x.optBoolean("optional")
            ).joinToString("|")
        }
        return out
    }

    companion object {
        const val BACKUP_SCHEMA = "ge360.preventivi.lifecycle.v1"
        fun snapshotJson(record: EstimateRecord): String = JSONObject().apply {
            put("schema", "ge360.estimate.snapshot.v1")
            put("ge360Id", record.ge360Id)
            put("number", record.number)
            put("clientGe360Id", record.clientGe360Id)
            put("clientName", record.clientName)
            put("jobsiteId", record.jobsiteId)
            put("jobsiteCode", record.jobsiteCode)
            put("title", record.title)
            put("workType", record.workType)
            put("status", record.status)
            put("documentType", record.documentType)
            put("validityDays", record.validityDays)
            put("generalDescription", record.generalDescription)
            put("materialsIncluded", record.materialsIncluded)
            put("materialsClient", record.materialsClient)
            put("exclusions", record.exclusions)
            put("finalNotes", record.finalNotes)
            put("discountType", record.discountType)
            put("discountValue", record.discountValue)
            put("surchargeType", record.surchargeType)
            put("surchargeValue", record.surchargeValue)
            put("rounding", record.rounding)
            put("subtotal", record.subtotal)
            put("finalTotal", record.finalTotal)
            put("items", JSONArray().apply {
                record.items.forEach { item ->
                    put(JSONObject().apply {
                        put("ge360Id", item.ge360Id)
                        put("priceListCode", item.priceListCode)
                        put("section", item.section)
                        put("room", item.room)
                        put("category", item.category)
                        put("name", item.name)
                        put("description", item.description)
                        put("quantity", item.quantity)
                        put("unit", item.unit)
                        put("unitPrice", item.unitPrice)
                        put("priceMode", item.priceMode)
                        put("included", item.included)
                        put("optional", item.optional)
                        put("internalNote", item.internalNote)
                        put("total", item.total)
                    })
                }
            })
        }.toString()

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
