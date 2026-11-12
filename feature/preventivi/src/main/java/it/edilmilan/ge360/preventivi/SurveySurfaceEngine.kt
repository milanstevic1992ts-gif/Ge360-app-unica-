package it.edilmilan.ge360.preventivi

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.round

/** Parete con identità stabile: rinominarla non sposta i lavori altrove. */
data class SurveyWallV1(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Parete",
    var width: Double = 0.0,
    var height: Double = 0.0,
    var openingsArea: Double = 0.0,
    var note: String = ""
) {
    fun grossArea(): Double = SurveySurfaceEngine.round2(width.coerceAtLeast(0.0) * height.coerceAtLeast(0.0))
    fun netArea(): Double = SurveySurfaceEngine.round2((grossArea() - openingsArea.coerceAtLeast(0.0)).coerceAtLeast(0.0))

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("width", width)
        put("height", height); put("openingsArea", openingsArea); put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject): SurveyWallV1 {
            fun n(key: String) = o.optDouble(key, 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
            return SurveyWallV1(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", "Parete").ifBlank { "Parete" },
                width = n("width"), height = n("height"),
                openingsArea = n("openingsArea"), note = o.optString("note")
            )
        }
    }
}

/**
 * Una stessa lavorazione può essere applicata a pareti diverse con quantità e
 * prezzi distinti, ma non contemporaneamente a tutto l'ambiente (anti-doppio
 * conteggio). Il campo manualQuantity nullo indica "proposta geometrica".
 */
data class SurveyScopedWorkV1(
    val id: String = UUID.randomUUID().toString(),
    var workCode: String,
    var scopeId: String,
    var manualQuantity: Double? = null,
    var unitPrice: Double = 0.0,
    var optional: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("workCode", workCode); put("scopeId", scopeId)
        if (manualQuantity != null) put("manualQuantity", manualQuantity)
        put("unitPrice", unitPrice); put("optional", optional)
    }

    companion object {
        fun fromJson(o: JSONObject): SurveyScopedWorkV1? {
            val code = o.optString("workCode").trim()
            val scope = o.optString("scopeId").trim()
            if (code.isBlank() || scope.isBlank()) return null
            val q = if (o.has("manualQuantity") && !o.isNull("manualQuantity")) {
                o.optDouble("manualQuantity", -1.0).takeIf { it.isFinite() && it >= 0.0 }
            } else null
            val p = o.optDouble("unitPrice", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            return SurveyScopedWorkV1(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                workCode = code, scopeId = scope,
                manualQuantity = q, unitPrice = p, optional = o.optBoolean("optional", false)
            )
        }
    }
}

data class SurveyWorkScope(val id: String, val title: String, val kind: String)

/**
 * Il legame stabile è (room UUID, superficie UUID/costante, codice lavoro).
 *
 * Ambito "ambiente" usa i workCodes storici: i vecchi sopralluoghi continuano
 * a funzionare, senza migrazioni SQLite. Nuovi ambiti sono registrati in JSON
 * e sono sempre righe di preventivo separate.
 */
object SurveySurfaceEngine {
    const val ROOM = "room"
    const val FLOOR = "floor"
    const val CEILING = "ceiling"
    const val ITEM_PREFIX = "SURFACE/"
    const val META_MARKER = "[GE360_SURFACE_SCOPE_V1]"

    fun round2(value: Double): Double = round(value * 100.0) / 100.0

    fun scopes(room: SmartSurveyRoomV1): List<SurveyWorkScope> = listOf(
        SurveyWorkScope(ROOM, "Tutto l'ambiente", "room"),
        SurveyWorkScope(FLOOR, "Pavimento", "floor"),
        SurveyWorkScope(CEILING, "Soffitto", "ceiling")
    ) + room.wallsList.map { SurveyWorkScope(it.id, it.name, "wall") }

    fun scope(room: SmartSurveyRoomV1, id: String): SurveyWorkScope? =
        scopes(room).firstOrNull { it.id == id }

    fun quantity(room: SmartSurveyRoomV1, assignment: SurveyScopedWorkV1, template: RoomWorkTemplate): Double {
        assignment.manualQuantity?.let { return round2(it) }
        val raw = when (assignment.scopeId) {
            FLOOR -> when (template.unit) {
                "m²" -> room.floor()
                "ml" -> room.perimeter()
                "corpo", "forfait" -> 1.0
                else -> 0.0
            }
            CEILING -> when (template.unit) {
                "m²" -> room.ceiling()
                "corpo", "forfait" -> 1.0
                else -> 0.0
            }
            else -> {
                val wall = room.wallsList.firstOrNull { it.id == assignment.scopeId }
                    ?: return 0.0
                when (template.unit) {
                    "m²" -> wall.netArea()
                    "ml" -> wall.width
                    "corpo", "forfait" -> 1.0
                    else -> 0.0
                }
            }
        }
        return if (raw.isFinite() && raw > 0.0) round2(raw) else 0.0
    }

    fun overlappingCodes(room: SmartSurveyRoomV1, target: String, codes: Set<String>): Set<String> =
        if (target == ROOM) {
            codes.filter { code -> room.scopedWorks.any { it.workCode == code } }.toSet()
        } else {
            codes.filter { it in room.workCodes }.toSet()
        }

    /**
     * Non si cambia nulla finché non è stata confermata esplicitamente
     * l'eventuale sostituzione delle assegnazioni "ambiente" con "superficie".
     */
    fun applySelection(
        room: SmartSurveyRoomV1,
        scopeId: String,
        eligibleCodes: Set<String>,
        selectedCodes: Set<String>,
        replaceOverlaps: Boolean = false
    ) {
        require(scope(room, scopeId) != null) { "Superficie non presente nel locale" }
        val allowed = RoomWorkCatalog.forType(room.type).map { it.code }.toSet()
        require(eligibleCodes.all { it in allowed }) { "Checklist non compatibile con l'ambiente" }
        require(selectedCodes.all { it in eligibleCodes }) { "Selezione fuori dalla checklist" }
        val conflicts = overlappingCodes(room, scopeId, selectedCodes)
        require(conflicts.isEmpty() || replaceOverlaps) {
            "Lavorazioni già assegnate a tutto l'ambiente o a superfici distinte: conferma la sostituzione"
        }

        if (scopeId == ROOM) {
            room.workCodes.removeAll(eligibleCodes)
            room.workCodes.addAll(selectedCodes)
            if (replaceOverlaps) room.scopedWorks.removeAll { it.workCode in conflicts }
        } else {
            room.scopedWorks.removeAll { it.scopeId == scopeId && it.workCode in eligibleCodes && it.workCode !in selectedCodes }
            selectedCodes.forEach { code ->
                if (room.scopedWorks.none { it.scopeId == scopeId && it.workCode == code }) {
                    room.scopedWorks += SurveyScopedWorkV1(workCode = code, scopeId = scopeId)
                }
            }
            if (replaceOverlaps) {
                room.workCodes.removeAll(conflicts)
                room.quantities.keys.removeAll(conflicts)
                room.unitPrices.keys.removeAll(conflicts)
                room.options.removeAll(conflicts)
            }
        }
    }

    /** Se si cancella una parete, le sue assegnazioni vanno rimosse insieme. */
    fun removeWall(room: SmartSurveyRoomV1, wallId: String) {
        require(room.wallsList.any { it.id == wallId }) { "Parete non presente" }
        room.scopedWorks.removeAll { it.scopeId == wallId }
        room.wallsList.removeAll { it.id == wallId }
    }

    fun hasWorks(room: SmartSurveyRoomV1): Boolean =
        room.workCodes.isNotEmpty() || room.scopedWorks.isNotEmpty() || room.manualWorks.isNotEmpty()

    /**
     * Validazione strutturale indipendente dai testi di avviso, per non
     * convertire in preventivo righe orfane o assegnate due volte.
     */
    fun hasFatalConflicts(room: SmartSurveyRoomV1): Boolean {
        val wallIds = room.wallsList.map { it.id }
        if (wallIds.size != wallIds.toSet().size) return true
        if (wallIds.any { it.isBlank() || it in setOf(ROOM, FLOOR, CEILING) }) return true
        val codes = RoomWorkCatalog.forType(room.type).map { it.code }.toSet()
        val keys = mutableSetOf<Pair<String, String>>()
        return room.scopedWorks.any { work ->
            scope(room, work.scopeId) == null || work.scopeId == ROOM ||
                work.workCode !in codes || work.workCode in room.workCodes ||
                !keys.add(work.scopeId to work.workCode)
        }
    }

    fun checks(room: SmartSurveyRoomV1): List<String> = buildList {
        val codes = RoomWorkCatalog.forType(room.type).associateBy { it.code }
        val wallIds = room.wallsList.map { it.id }
        if (wallIds.size != wallIds.distinct().size) add("${room.name}: identificativi di parete duplicati")
        if (wallIds.any { it.isBlank() || it in setOf(ROOM, FLOOR, CEILING) })
            add("${room.name}: codice identificativo parete riservato o mancante")
        val measuredWalls = room.wallsList.sumOf { it.netArea() }
        if (room.walls() > 0.0 && measuredWalls > room.walls() + 0.25) {
            add("${room.name}: somma delle pareti censite " + round2(measuredWalls) +
                " m² superiore alla superficie totale dichiarata " + round2(room.walls()) + " m²: verifica")
        }
        room.wallsList.forEach { wall ->
            if (wall.width <= 0.0 || wall.height <= 0.0) add("${room.name} · ${wall.name}: misure parete incomplete")
            if (wall.openingsArea > wall.grossArea())
                add("${room.name} · ${wall.name}: aperture maggiori della superficie lorda")
        }
        val uniqueAssignments = mutableSetOf<Pair<String, String>>()
        room.scopedWorks.forEach { assignment ->
            val label = scope(room, assignment.scopeId)?.title
            if (label == null) {
                add("${room.name}: assegnazione ${assignment.workCode} collegata a superficie inesistente")
                return@forEach
            }
            if (assignment.scopeId == ROOM) {
                add("${room.name}: usa la checklist ambiente, non una superficie di tipo room")
                return@forEach
            }
            if (!uniqueAssignments.add(assignment.scopeId to assignment.workCode))
                add("${room.name} · ${label}: lavorazione ${assignment.workCode} duplicata")
            if (assignment.workCode in room.workCodes) {
                add("${room.name} · ${label}: lavorazione ${assignment.workCode} presente anche su tutto l'ambiente (doppio conteggio)")
            }
            val template = codes[assignment.workCode]
            if (template == null) {
                add("${room.name} · ${label}: lavorazione non valida ${assignment.workCode}")
                return@forEach
            }
            if (quantity(room, assignment, template) <= 0.0)
                add("${room.name} · ${label} · ${template.title}: quantità da misurare")
            if (room.pricingMode != RoomPricing.BODY && assignment.unitPrice <= 0.0)
                add("${room.name} · ${label} · ${template.title}: prezzo mancante")
        }
    }

    fun itemCode(roomId: String, assignment: SurveyScopedWorkV1): String =
        ITEM_PREFIX + roomId + "/" + assignment.scopeId + "/" + assignment.workCode

    fun metadata(scope: SurveyWorkScope): String =
        META_MARKER + JSONObject()
            .put("scopeId", scope.id)
            .put("scopeTitle", scope.title)
            .put("scopeKind", scope.kind)
            .toString()

    fun parseMetadata(note: String): JSONObject? {
        val at = note.indexOf(META_MARKER)
        if (at < 0) return null
        return runCatching { JSONObject(note.substring(at + META_MARKER.length).substringBefore('\n')) }.getOrNull()
    }

    fun wallsToJson(walls: List<SurveyWallV1>) = JSONArray().apply {
        walls.forEach { put(it.toJson()) }
    }
    fun assignmentsToJson(assignments: List<SurveyScopedWorkV1>) = JSONArray().apply {
        assignments.forEach { put(it.toJson()) }
    }
}
