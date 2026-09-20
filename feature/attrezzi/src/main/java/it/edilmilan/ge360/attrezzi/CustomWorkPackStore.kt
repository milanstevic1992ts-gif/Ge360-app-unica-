package it.edilmilan.ge360.attrezzi

import android.content.Context
import it.edilmilan.ge360.core.common.BackupMerge
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomWorkPack(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val kind: ResourceKind,
    val items: List<WorkResourceSpec>,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toWorkPack(): WorkPack = WorkPack(
        id = "custom:$id",
        icon = if (kind == ResourceKind.TOOL) "🔧" else "📦",
        title = title,
        description = if (kind == ResourceKind.TOOL) "Pacchetto attrezzi personalizzato" else "Pacchetto materiali personalizzato",
        items = items
    )
}

class CustomWorkPackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(): List<CustomWorkPack> {
        val raw = prefs.getString(KEY_PACKS, "[]").orEmpty()
        return runCatching {
            val rows = JSONArray(raw)
            buildList {
                for (i in 0 until rows.length()) add(fromJson(rows.getJSONObject(i)))
            }.sortedWith(compareBy<CustomWorkPack> { it.kind.name }.thenBy { it.title.lowercase() })
        }.getOrDefault(emptyList())
    }

    fun save(pack: CustomWorkPack) {
        val rows = list().toMutableList()
        val normalized = pack.copy(title = pack.title.trim(), updatedAt = System.currentTimeMillis())
        val index = rows.indexOfFirst { it.id == normalized.id }
        if (index >= 0) rows[index] = normalized else rows += normalized
        persist(rows)
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("packs", JSONArray().apply { list().forEach { put(toJson(it)) } })
    }.toString(2)

    fun importJson(json: String, replace: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup pacchetti personalizzati non compatibile" }
        val incoming = buildList {
            val rows = root.optJSONArray("packs") ?: JSONArray()
            for (i in 0 until rows.length()) add(fromJson(rows.getJSONObject(i)))
        }
        val merged = BackupMerge.byKey(list(), incoming, replace, CustomWorkPack::id)
        persist(merged)
        return incoming.size
    }

    private fun persist(rows: List<CustomWorkPack>) {
        val json = JSONArray().apply { rows.forEach { put(toJson(it)) } }
        prefs.edit().putString(KEY_PACKS, json.toString()).apply()
    }

    private fun toJson(pack: CustomWorkPack): JSONObject = JSONObject()
        .put("id", pack.id)
        .put("title", pack.title)
        .put("kind", pack.kind.name)
        .put("updatedAt", pack.updatedAt)
        .put("items", JSONArray().apply {
            pack.items.forEach { item ->
                put(JSONObject()
                    .put("kind", item.kind.name)
                    .put("name", item.name)
                    .put("category", item.category)
                    .put("unit", item.unit)
                    .put("quantity", item.quantity)
                    .put("note", item.note))
            }
        })

    private fun fromJson(o: JSONObject): CustomWorkPack {
        val kind = runCatching { ResourceKind.valueOf(o.optString("kind", "TOOL")) }.getOrDefault(ResourceKind.TOOL)
        val items = buildList {
            val rows = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                val itemKind = runCatching { ResourceKind.valueOf(item.optString("kind", kind.name)) }.getOrDefault(kind)
                add(WorkResourceSpec(
                    kind = itemKind,
                    name = item.optString("name"),
                    category = item.optString("category", "Altro"),
                    unit = item.optString("unit", "pz"),
                    quantity = item.optDouble("quantity", 1.0),
                    note = item.optString("note")
                ))
            }
        }
        return CustomWorkPack(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = o.optString("title", "Pacchetto personalizzato"),
            kind = kind,
            items = items,
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    companion object {
        const val BACKUP_SCHEMA = "ge360.attrezzi.custom-packs.v1"
        private const val PREFS_NAME = "ge360_attrezzi_custom_packs"
        private const val KEY_PACKS = "packs_json"
    }
}
