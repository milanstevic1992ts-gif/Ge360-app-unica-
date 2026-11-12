package it.edilmilan.ge360.planimetrie

import it.edilmilan.ge360.preventivi.EstimateItem
import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.QuantityBasis
import it.edilmilan.ge360.preventivi.RoomTypes
import it.edilmilan.ge360.preventivi.RoomWorkCatalog
import it.edilmilan.ge360.preventivi.RoomWorkTemplate
import org.json.JSONObject
import java.util.UUID
import kotlin.math.round

/** Unica sorgente delle misure: il rilievo nativo. Nessuna stanza duplicata in Preventivi. */
object RilievoQuoteEngine {
    const val CODE_PREFIX = "RILIEVO-QUOTE/"
    const val META = "[GE360_RILIEVO_QUOTE_V1]"
    const val SCOPE_ROOM = "room"
    const val SCOPE_WALLS = "walls"
    const val SCOPE_FLOOR = "floor"
    const val SCOPE_CEILING = "ceiling"
    const val WALL_PREFIX = "wall:"

    data class Target(val id: String, val title: String, val detail: String)

    fun roomType(type: String): String = when {
        type.contains("bagno", true) -> RoomTypes.BATHROOM
        type.contains("cucina", true) -> RoomTypes.KITCHEN
        type.contains("terraz", true) || type.contains("balcon", true) -> RoomTypes.BALCONY
        type.contains("stanza", true) || type.contains("camera", true) ||
            type.contains("salotto", true) || type.contains("soggiorn", true) ||
            type.contains("corridoio", true) -> RoomTypes.ROOM
        else -> RoomTypes.OTHER
    }

    fun targets(room: FloorPlanRoom, walls: List<FloorPlanWall>): List<Target> =
        listOf(
            Target(SCOPE_ROOM, "Tutta la stanza", "Lavori generali sul locale"),
            Target(SCOPE_WALLS, "Tutte le pareti", "Pareti complessive · ${room.wallArea().pretty()} m² lordi"),
            Target(SCOPE_CEILING, "Soffitto", "${room.ceilingArea().pretty()} m²"),
            Target(SCOPE_FLOOR, "Pavimento", "${room.floorArea().pretty()} m²")
        ) + walls.sortedBy { it.sortOrder }.mapIndexed { index, wall ->
            Target(WALL_PREFIX + wall.ge360Id,
                FloorPlanWallOpenings.userNote(wall.note).trim().ifBlank { "Parete ${index + 1}" },
                "Lunghezza ${wall.length.pretty()} m · altezza ${room.height.pretty()} m")
        }

    fun itemCode(planId: String, roomId: String, scope: String, workCode: String): String =
        "$CODE_PREFIX$planId/$roomId/$scope/$workCode"

    fun belongs(item: EstimateItem, planId: String, roomId: String, scope: String): Boolean =
        item.priceListCode.startsWith("$CODE_PREFIX$planId/$roomId/$scope/")

    fun workCode(item: EstimateItem, planId: String, roomId: String, scope: String): String? =
        if (belongs(item, planId, roomId, scope))
            item.priceListCode.substringAfterLast('/') else null

    fun snapshot(room: FloorPlanRoom, walls: List<FloorPlanWall>): String =
        PlanGeometryQuantitiesV2.fingerprint(room, walls)

    fun previousFingerprint(item: EstimateItem): String? {
        val index = item.internalNote.indexOf(META)
        if (index < 0) return null
        return runCatching {
            JSONObject(item.internalNote.substring(index + META.length).lineSequence().first())
                .optString("geometry")
        }.getOrNull()
    }

    fun overlap(
        estimate: EstimateRecord, planId: String, roomId: String, scope: String, workCode: String
    ): Boolean {
        val base = "$CODE_PREFIX$planId/$roomId/"
        return estimate.items.any { item ->
            if (!item.priceListCode.startsWith(base)) return@any false
            val rest = item.priceListCode.removePrefix(base)
            val otherScope = rest.substringBeforeLast('/', "")
            val code = rest.substringAfterLast('/')
            code == workCode && otherScope != scope && (
                otherScope == SCOPE_ROOM || scope == SCOPE_ROOM ||
                    (otherScope == SCOPE_WALLS && scope.startsWith(WALL_PREFIX)) ||
                    (scope == SCOPE_WALLS && otherScope.startsWith(WALL_PREFIX))
            )
        }
    }

    fun suggestedQuantity(
        room: FloorPlanRoom, walls: List<FloorPlanWall>,
        targetId: String, template: RoomWorkTemplate
    ): Double {
        val unit = template.unit
        if (unit == "corpo" || unit == "forfait") return 1.0
        if (unit == "ml") return when {
            targetId.startsWith(WALL_PREFIX) ->
                walls.firstOrNull { WALL_PREFIX + it.ge360Id == targetId }?.length ?: 0.0
            targetId == SCOPE_FLOOR || targetId == SCOPE_ROOM -> room.perimeter()
            else -> 0.0
        }
        if (unit != "m²") return 0.0
        val surface = when {
            targetId == SCOPE_FLOOR -> PlanWorkBindingV1.FLOOR
            targetId == SCOPE_CEILING -> PlanWorkBindingV1.CEILING
            targetId == SCOPE_WALLS -> if (walls.isNotEmpty())
                PlanWorkBindingV1.WALLS_NET else PlanWorkBindingV1.WALLS
            targetId.startsWith(WALL_PREFIX) -> PlanWorkBindingV1.SINGLE_WALL_NET
            targetId == SCOPE_ROOM -> when (template.basis) {
                QuantityBasis.FLOOR -> PlanWorkBindingV1.FLOOR
                QuantityBasis.CEILING -> PlanWorkBindingV1.CEILING
                QuantityBasis.WALLS, QuantityBasis.WALLS_CEILING -> PlanWorkBindingV1.WALLS
                else -> return 0.0
            }
            else -> return 0.0
        }
        val wall = if (targetId.startsWith(WALL_PREFIX))
            targetId.removePrefix(WALL_PREFIX) else null
        // Fallimento conservativo: niente superfici inventate da profili aperti.
        val measured = runCatching {
            PlanGeometryQuantitiesV2.measure(room, walls, surface, wall).quantityM2
        }.getOrDefault(0.0)
        if (measured <= 0.0) return 0.0
        return if (targetId == SCOPE_ROOM && template.basis == QuantityBasis.WALLS_CEILING) {
            round((measured + room.ceilingArea()) * 100.0) / 100.0
        } else measured
    }

    fun item(
        plan: FloorPlanRecord, room: FloorPlanRoom, walls: List<FloorPlanWall>,
        target: Target, template: RoomWorkTemplate
    ): EstimateItem {
        val quantity = suggestedQuantity(room, walls, target.id, template)
        return EstimateItem(
            ge360Id = UUID.randomUUID().toString(),
            priceListCode = itemCode(plan.ge360Id, room.ge360Id, target.id, template.code),
            section = "Rilievo · ${room.name}",
            room = room.name,
            category = template.category,
            name = "${template.title} · ${target.title}",
            description = template.description +
                "\nPosizione: ${room.name} / ${target.title}. Quantità da verificare con il rilievo.",
            quantity = quantity,
            unit = template.unit,
            unitPrice = 0.0,
            priceMode = if (template.unit in listOf("corpo", "forfait")) "body" else "unit",
            included = true,
            optional = false,
            internalNote = META + JSONObject().apply {
                put("plan", plan.ge360Id)
                put("room", room.ge360Id)
                put("surface", target.id)
                put("work", template.code)
                put("geometry", snapshot(room, walls))
            }.toString()
        )
    }

    private fun Double.pretty() = if (this % 1.0 == 0.0) toInt().toString()
        else String.format(java.util.Locale.ITALY, "%.2f", this)
}
