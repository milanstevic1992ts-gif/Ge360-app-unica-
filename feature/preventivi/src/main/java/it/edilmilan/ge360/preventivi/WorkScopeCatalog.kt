package it.edilmilan.ge360.preventivi

import kotlin.math.round

/**
 * GE360 Preventivi: regole di destinazione indipendenti dal motore Rilievi.
 *
 * Un rilievo può fornire l'ID di un muro/stanza e le sue misure; il preventivo
 * conserva la propria lavorazione e una fotografia delle quantità. Nessun
 * riferimento al backend di rilievo è obbligatorio per creare un preventivo.
 */
enum class WorkTargetKind(val label: String) {
    SINGLE_WALL("Singola parete"),
    ROOM_WALLS("Tutte le pareti dell'ambiente"),
    ROOM_CEILING("Soffitto dell'ambiente"),
    ROOM_FLOOR("Pavimento dell'ambiente"),
    ROOM_PERIMETER("Perimetro dell'ambiente"),
    SELECTED_EDGE("Tratto lineare selezionato"),
    SINGLE_ELEMENT("Elemento / pezzo"),
    ROOM("Intero ambiente"),
    PROPERTY("Intero immobile"),
    MANUAL("Quantità manuale")
}

data class WorkTargetReference(
    val kind: WorkTargetKind,
    /** UUID esterno del muro, stanza o altro oggetto, se disponibile. */
    val sourceEntityId: String? = null,
    /** UUID del rilievo: sempre facoltativo. */
    val surveyId: String? = null
)

data class WorkMeasurements(
    val singleWallArea: Double? = null,
    val allRoomWallsArea: Double? = null,
    val roomCeilingArea: Double? = null,
    val roomFloorArea: Double? = null,
    val roomPerimeter: Double? = null,
    val selectedEdgeLength: Double? = null,
    val wholePropertyArea: Double? = null,
    val elementCount: Double? = null,
    val manualQuantity: Double? = null
)

data class WorkScopeRule(
    val code: String,
    val unit: String,
    val allowedTargets: Set<WorkTargetKind>,
    val suggestedTarget: WorkTargetKind
)

object WorkScopeCatalog {
    private val wallAreaCodes = setOf(
        4, 28, 29, 37, 44, 45, 46, 48, 51, 52, 53, 54,
        64, 65, 70, 72, 75, 82, 85
    )
    private val wallCeilingCodes = setOf(7, 8, 9, 10, 11, 49, 71)
    private val ceilingCodes = setOf(34, 66, 67)
    private val floorCodes = setOf(
        6, 12, 13, 36, 58, 59, 60, 62, 63, 73, 74, 76, 77, 83
    )
    private val wallFloorCodes = setOf(14, 15, 16, 20, 78)
    private val perimeterCodes = setOf(18, 19, 55, 56, 57)
    private val edgeCodes = setOf(5, 17, 47, 50, 68, 79, 81, 84, 87)
    private val elementCodes = setOf(24, 25, 26, 27, 30, 31, 61, 69, 86)

    private fun capNumber(code: String): Int? {
        if (!code.startsWith(StandardWorkCatalog.PREFIX)) return null
        return code.removePrefix(StandardWorkCatalog.PREFIX).toIntOrNull()
    }

    fun ruleFor(code: String): WorkScopeRule? {
        val entry = StandardWorkCatalog.byCode(code) ?: return null
        val number = capNumber(code) ?: return null
        val targets: Set<WorkTargetKind> = when {
            number in elementCodes -> setOf(WorkTargetKind.SINGLE_ELEMENT, WorkTargetKind.MANUAL)
            number in perimeterCodes -> setOf(WorkTargetKind.ROOM_PERIMETER, WorkTargetKind.SELECTED_EDGE, WorkTargetKind.MANUAL)
            number in edgeCodes -> setOf(WorkTargetKind.SELECTED_EDGE, WorkTargetKind.MANUAL)
            number in wallFloorCodes -> setOf(WorkTargetKind.SINGLE_WALL, WorkTargetKind.ROOM_WALLS, WorkTargetKind.ROOM_FLOOR, WorkTargetKind.MANUAL)
            number in floorCodes -> setOf(WorkTargetKind.ROOM_FLOOR, WorkTargetKind.MANUAL)
            number in ceilingCodes -> setOf(WorkTargetKind.ROOM_CEILING, WorkTargetKind.MANUAL)
            number in wallCeilingCodes -> setOf(WorkTargetKind.SINGLE_WALL, WorkTargetKind.ROOM_WALLS, WorkTargetKind.ROOM_CEILING, WorkTargetKind.MANUAL)
            number in wallAreaCodes -> setOf(WorkTargetKind.SINGLE_WALL, WorkTargetKind.ROOM_WALLS, WorkTargetKind.MANUAL)
            entry.unit == "corpo" || entry.unit == "forfait" -> setOf(WorkTargetKind.ROOM, WorkTargetKind.PROPERTY, WorkTargetKind.MANUAL)
            entry.unit == "cad." -> setOf(WorkTargetKind.SINGLE_ELEMENT, WorkTargetKind.MANUAL)
            else -> setOf(WorkTargetKind.MANUAL) // nessuna ipotesi geometrica non verificata
        }
        return WorkScopeRule(code, entry.unit, targets, targets.first())
    }

    /**
     * Restituisce null se non ci sono misure verificabili o il target non è
     * compatibile. Non converte automaticamente un dato mancante in 1 m².
     */
    fun suggestedQuantity(code: String, target: WorkTargetKind, measurements: WorkMeasurements): Double? {
        val rule = ruleFor(code) ?: return null
        if (target !in rule.allowedTargets) return null
        if (target == WorkTargetKind.ROOM || target == WorkTargetKind.PROPERTY) {
            return if (rule.unit == "corpo" || rule.unit == "forfait") 1.0 else null
        }
        val value = when (target) {
            WorkTargetKind.SINGLE_WALL -> measurements.singleWallArea
            WorkTargetKind.ROOM_WALLS -> measurements.allRoomWallsArea
            WorkTargetKind.ROOM_CEILING -> measurements.roomCeilingArea
            WorkTargetKind.ROOM_FLOOR -> measurements.roomFloorArea
            WorkTargetKind.ROOM_PERIMETER -> measurements.roomPerimeter
            WorkTargetKind.SELECTED_EDGE -> measurements.selectedEdgeLength
            WorkTargetKind.SINGLE_ELEMENT -> measurements.elementCount
            WorkTargetKind.MANUAL -> measurements.manualQuantity
            else -> null
        } ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return round(value * 100.0) / 100.0
    }
}
