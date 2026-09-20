package it.edilmilan.ge360.preventivi

import android.content.Context
import it.edilmilan.ge360.core.common.BackupMerge
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ManualRoomMeasures(
    val enabled: Boolean = false,
    val floor: Double = 0.0,
    val ceiling: Double = 0.0,
    val walls: Double = 0.0,
    val perimeter: Double = 0.0
)

data class SavedWorkTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "Lavorazione personale",
    val description: String = "",
    val unit: String = "corpo",
    val suggestedPrice: Double = 0.0,
    val references: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

object EstimatePresentationModes {
    const val DETAILED = "Dettagliato"
    const val SYNTHETIC = "Sintetico"
    const val BODY = "A corpo"
    val ALL = listOf(DETAILED, SYNTHETIC, BODY)
}

/**
 * Personalizzazioni nate dall'uso reale dell'app.
 *
 * - le misure manuali sono specifiche del singolo ambiente;
 * - lavorazioni preferite e checklist predefinite sono riutilizzabili e
 *   possono essere esportate in JSON per essere versionate nella repository;
 * - la modalità documento è una preferenza del singolo preventivo.
 *
 * Nessuna di queste impostazioni modifica il capitolato standard originale.
 */
class PreventiviCustomizationStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun manualMeasures(roomId: String): ManualRoomMeasures {
        val root = objectValue(KEY_MEASURES)
        val o = root.optJSONObject(roomId) ?: return ManualRoomMeasures()
        return ManualRoomMeasures(
            enabled = o.optBoolean("enabled", false),
            floor = o.optDouble("floor", 0.0),
            ceiling = o.optDouble("ceiling", 0.0),
            walls = o.optDouble("walls", 0.0),
            perimeter = o.optDouble("perimeter", 0.0)
        )
    }

    fun saveManualMeasures(roomId: String, value: ManualRoomMeasures) {
        val root = objectValue(KEY_MEASURES)
        root.put(roomId, JSONObject().apply {
            put("enabled", value.enabled)
            put("floor", value.floor.coerceAtLeast(0.0))
            put("ceiling", value.ceiling.coerceAtLeast(0.0))
            put("walls", value.walls.coerceAtLeast(0.0))
            put("perimeter", value.perimeter.coerceAtLeast(0.0))
        })
        prefs.edit().putString(KEY_MEASURES, root.toString()).apply()
    }

    fun copyManualMeasures(sourceRoomId: String, targetRoomId: String) {
        saveManualMeasures(targetRoomId, manualMeasures(sourceRoomId))
    }

    fun deleteManualMeasures(roomId: String) {
        val root = objectValue(KEY_MEASURES)
        root.remove(roomId)
        prefs.edit().putString(KEY_MEASURES, root.toString()).apply()
    }

    /**
     * Se la modalità manuale è attiva, i valori inseriti sostituiscono il
     * rettangolo L×W×H. Un valore lasciato a zero continua a usare il calcolo
     * geometrico standard. Porte e finestre NON vengono sottratte.
     */
    fun quantity(room: EstimateRoom, basis: QuantityBasis): Double {
        val manual = manualMeasures(room.ge360Id)
        if (!manual.enabled) return RoomWorkCatalog.quantity(room, basis)
        val floor = manual.floor.takeIf { it > 0 } ?: room.floorArea()
        val ceiling = manual.ceiling.takeIf { it > 0 } ?: room.ceilingArea()
        val walls = manual.walls.takeIf { it > 0 } ?: room.wallArea()
        val perimeter = manual.perimeter.takeIf { it > 0 } ?: room.perimeter()
        return when (basis) {
            QuantityBasis.BODY -> 1.0
            QuantityBasis.FLOOR -> floor
            QuantityBasis.CEILING -> ceiling
            QuantityBasis.WALLS -> walls
            QuantityBasis.WALLS_CEILING -> walls + ceiling
            QuantityBasis.WALLS_FLOOR -> walls + floor
            QuantityBasis.PERIMETER -> perimeter
            QuantityBasis.MANUAL -> 1.0
        }.let { if (it <= 0.0) 1.0 else it }
    }

    fun manualMeasuresSummary(room: EstimateRoom): String {
        val m = manualMeasures(room.ge360Id)
        if (!m.enabled) return room.measuresSummary()
        val floor = m.floor.takeIf { it > 0 } ?: room.floorArea()
        val ceiling = m.ceiling.takeIf { it > 0 } ?: room.ceilingArea()
        val walls = m.walls.takeIf { it > 0 } ?: room.wallArea()
        val perimeter = m.perimeter.takeIf { it > 0 } ?: room.perimeter()
        return "Manuale · pavimento ${fmt(floor)} m² · soffitto ${fmt(ceiling)} m² · pareti ${fmt(walls)} m² · perimetro ${fmt(perimeter)} ml"
    }

    fun savedWorks(): List<SavedWorkTemplate> {
        val array = arrayValue(KEY_WORKS)
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(workFromJson(o))
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun saveWork(work: SavedWorkTemplate) {
        val rows = savedWorks().toMutableList()
        val same = rows.indexOfFirst { it.id == work.id || (it.name.equals(work.name, true) && it.unit == work.unit) }
        val normalized = work.copy(updatedAt = System.currentTimeMillis())
        if (same >= 0) rows[same] = normalized else rows += normalized
        saveWorks(rows)
    }

    fun deleteWork(id: String) {
        saveWorks(savedWorks().filterNot { it.id == id })
    }

    fun checklistDefault(roomType: String): Set<String>? {
        val root = objectValue(KEY_CHECKLISTS)
        if (!root.has(roomType)) return null
        val a = root.optJSONArray(roomType) ?: return emptySet()
        return buildSet { for (i in 0 until a.length()) add(a.optString(i)) }
    }

    fun saveChecklistDefault(roomType: String, codes: Set<String>) {
        val root = objectValue(KEY_CHECKLISTS)
        root.put(roomType, JSONArray(codes.toList()))
        prefs.edit().putString(KEY_CHECKLISTS, root.toString()).apply()
    }

    fun clearChecklistDefault(roomType: String) {
        val root = objectValue(KEY_CHECKLISTS)
        root.remove(roomType)
        prefs.edit().putString(KEY_CHECKLISTS, root.toString()).apply()
    }

    fun presentationMode(estimateGe360Id: String): String {
        val root = objectValue(KEY_PRESENTATION)
        return root.optString(estimateGe360Id, EstimatePresentationModes.DETAILED)
            .takeIf { it in EstimatePresentationModes.ALL } ?: EstimatePresentationModes.DETAILED
    }

    fun setPresentationMode(estimateGe360Id: String, mode: String) {
        if (estimateGe360Id.isBlank() || mode !in EstimatePresentationModes.ALL) return
        val root = objectValue(KEY_PRESENTATION)
        root.put(estimateGe360Id, mode)
        prefs.edit().putString(KEY_PRESENTATION, root.toString()).apply()
    }

    fun copyPresentationMode(sourceEstimateId: String, targetEstimateId: String) {
        setPresentationMode(targetEstimateId, presentationMode(sourceEstimateId))
    }

    /** Backup completo: include anche dati specifici di ambienti e preventivi. */
    fun exportFullJson(): String = JSONObject().apply {
        put("schema", FULL_BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("manualMeasures", objectValue(KEY_MEASURES))
        put("savedWorks", arrayValue(KEY_WORKS))
        put("checklistDefaults", objectValue(KEY_CHECKLISTS))
        put("presentationModes", objectValue(KEY_PRESENTATION))
    }.toString(2)

    fun importFullJson(json: String, replace: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == FULL_BACKUP_SCHEMA) { "Backup completo personalizzazioni non compatibile" }
        val measures = mergeObjects(objectValue(KEY_MEASURES), root.optJSONObject("manualMeasures"), replace)
        val checklists = mergeObjects(objectValue(KEY_CHECKLISTS), root.optJSONObject("checklistDefaults"), replace)
        val presentation = mergeObjects(objectValue(KEY_PRESENTATION), root.optJSONObject("presentationModes"), replace)
        val incomingWorks = root.optJSONArray("savedWorks") ?: JSONArray()
        val currentWorks = mutableListOf<JSONObject>()
        val works = arrayValue(KEY_WORKS)
        for (i in 0 until works.length()) {
            val item = works.optJSONObject(i) ?: continue
            currentWorks += item
        }
        val restoredWorks = mutableListOf<JSONObject>()
        for (i in 0 until incomingWorks.length()) {
            val item = incomingWorks.optJSONObject(i) ?: continue
            restoredWorks += item
        }
        val keyedCurrent = currentWorks.mapIndexed { index, item ->
            item.optString("id").ifBlank { "existing:$index" } to item
        }
        val keyedRestored = restoredWorks.mapIndexed { index, item ->
            item.optString("id").ifBlank { "incoming:$index" } to item
        }
        val mergedWorks = BackupMerge.byKey(keyedCurrent, keyedRestored, replace) { it.first }.map { it.second }
        prefs.edit()
            .putString(KEY_MEASURES, measures.toString())
            .putString(KEY_WORKS, JSONArray(mergedWorks).toString())
            .putString(KEY_CHECKLISTS, checklists.toString())
            .putString(KEY_PRESENTATION, presentation.toString())
            .commit()
        return measures.length() + incomingWorks.length() + checklists.length() + presentation.length()
    }

    /** Backup portabile: solo ciò che deve diventare il nuovo standard personale. */
    fun exportReusableJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        put("exportedAt", System.currentTimeMillis())
        put("savedWorks", JSONArray().apply { savedWorks().forEach { put(workToJson(it)) } })
        put("checklistDefaults", objectValue(KEY_CHECKLISTS))
    }.toString(2)

    /** Restituisce Pair(lavorazioni importate, checklist importate). */
    fun importReusableJson(json: String, replace: Boolean = false): Pair<Int, Int> {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) { "Backup personalizzazioni non compatibile" }

        if (replace) {
            prefs.edit().remove(KEY_WORKS).remove(KEY_CHECKLISTS).apply()
        }

        var worksCount = 0
        val incomingWorks = root.optJSONArray("savedWorks") ?: JSONArray()
        for (i in 0 until incomingWorks.length()) {
            val o = incomingWorks.optJSONObject(i) ?: continue
            saveWork(workFromJson(o))
            worksCount++
        }

        var checklistCount = 0
        val incomingDefaults = root.optJSONObject("checklistDefaults") ?: JSONObject()
        val existingDefaults = if (replace) JSONObject() else objectValue(KEY_CHECKLISTS)
        val keys = incomingDefaults.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            existingDefaults.put(key, incomingDefaults.optJSONArray(key) ?: JSONArray())
            checklistCount++
        }
        prefs.edit().putString(KEY_CHECKLISTS, existingDefaults.toString()).apply()
        return worksCount to checklistCount
    }

    private fun saveWorks(rows: List<SavedWorkTemplate>) {
        prefs.edit().putString(KEY_WORKS, JSONArray().apply { rows.forEach { put(workToJson(it)) } }.toString()).apply()
    }

    private fun workToJson(w: SavedWorkTemplate) = JSONObject().apply {
        put("id", w.id)
        put("name", w.name)
        put("category", w.category)
        put("description", w.description)
        put("unit", w.unit)
        put("suggestedPrice", w.suggestedPrice)
        put("references", JSONArray(w.references))
        put("updatedAt", w.updatedAt)
    }

    private fun workFromJson(o: JSONObject): SavedWorkTemplate {
        val refs = mutableListOf<String>()
        o.optJSONArray("references")?.let { a -> for (i in 0 until a.length()) refs += a.optString(i) }
        return SavedWorkTemplate(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "Lavorazione"),
            category = o.optString("category", "Lavorazione personale"),
            description = o.optString("description", ""),
            unit = o.optString("unit", "corpo"),
            suggestedPrice = o.optDouble("suggestedPrice", 0.0),
            references = refs.distinct(),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun objectValue(key: String): JSONObject =
        runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun arrayValue(key: String): JSONArray =
        runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrDefault(JSONArray())

    private fun mergeObjects(current: JSONObject, incoming: JSONObject?, replace: Boolean): JSONObject {
        val result = if (replace) JSONObject() else JSONObject(current.toString())
        val source = incoming ?: JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, source.opt(key))
        }
        return result
    }

    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.ITALY, "%.2f", v)

    companion object {
        const val BACKUP_SCHEMA = "ge360.preventivi.customizations.v1"
        const val FULL_BACKUP_SCHEMA = "ge360.preventivi.customizations-full.v1"
        private const val PREFS = "ge360_preventivi_customizations"
        private const val KEY_MEASURES = "manual_room_measures"
        private const val KEY_WORKS = "saved_works"
        private const val KEY_CHECKLISTS = "checklist_defaults"
        private const val KEY_PRESENTATION = "presentation_modes"
    }
}

data class ItemCostProfile(
    val materials: Double = 0.0,
    val labor: Double = 0.0,
    val collaborators: Double = 0.0,
    val other: Double = 0.0,
    val estimatedHours: Double = 0.0
) {
    fun total(): Double = materials + labor + collaborators + other
}

/** I costi interni viaggiano nel backup del preventivo tramite internalNote. */
object EstimateItemCosting {
    private const val COST_MARKER = "[GE360_COST_META]"
    private const val ROOM_MARKER = "[GE360_ROOM_META]"

    fun read(item: EstimateItem): ItemCostProfile {
        val note = item.internalNote
        val start = note.indexOf(COST_MARKER)
        if (start < 0) return ItemCostProfile()
        val end = note.indexOf(ROOM_MARKER).let { if (it >= 0 && it > start) it else note.length }
        val raw = note.substring(start + COST_MARKER.length, end).trim()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return ItemCostProfile()
        return ItemCostProfile(
            materials = o.optDouble("materials", 0.0),
            labor = o.optDouble("labor", 0.0),
            collaborators = o.optDouble("collaborators", 0.0),
            other = o.optDouble("other", 0.0),
            estimatedHours = o.optDouble("estimatedHours", 0.0)
        )
    }

    fun write(item: EstimateItem, profile: ItemCostProfile) {
        val note = item.internalNote
        val roomIndex = note.indexOf(ROOM_MARKER)
        val roomPart = if (roomIndex >= 0) note.substring(roomIndex) else ""
        val beforeRoom = if (roomIndex >= 0) note.substring(0, roomIndex) else note
        val clean = beforeRoom.substringBefore(COST_MARKER).trimEnd()
        item.internalNote = buildString {
            if (clean.isNotBlank()) append(clean).append('\n')
            if (profile.total() > 0.0 || profile.estimatedHours > 0.0) {
                append(COST_MARKER)
                append(JSONObject().apply {
                    put("materials", profile.materials.coerceAtLeast(0.0))
                    put("labor", profile.labor.coerceAtLeast(0.0))
                    put("collaborators", profile.collaborators.coerceAtLeast(0.0))
                    put("other", profile.other.coerceAtLeast(0.0))
                    put("estimatedHours", profile.estimatedHours.coerceAtLeast(0.0))
                })
                if (roomPart.isNotBlank()) append('\n')
            }
            append(roomPart)
        }
    }

    fun totalCost(record: EstimateRecord): Double = record.items.sumOf { read(it).total() }
    fun totalHours(record: EstimateRecord): Double = record.items.sumOf { read(it).estimatedHours }
    fun margin(record: EstimateRecord): Double = recalculateEstimate(record).finalTotal - totalCost(record)
}
