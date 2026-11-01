package it.edilmilan.ge360.planimetrie

import java.security.MessageDigest
import org.json.JSONArray
import kotlin.math.abs

/**
 * Conservative, one-face quantities from the canonical Android room/wall/opening records.
 * Does not invent doors, floor levels, structural thicknesses or pricing.
 */
object PlanGeometryQuantitiesV2 {
    data class Measured(
        val quantityM2: Double,
        val grossM2: Double,
        val openingDeductionsM2: Double,
        val fingerprint: String,
        val surface: String,
        val wallGe360Id: String? = null
    )

    private fun value(n: Double): String {
        require(n.isFinite()) { "Misura non finita" }
        return java.lang.Double.doubleToLongBits(n).toString(16)
    }

    /**
     * Stable across export/import: never include local row IDs or volatile updated_at.
     * Includes raw wall notes because [GE360_OPENINGS] is embedded there.
     */
    fun fingerprint(room: FloorPlanRoom, walls: List<FloorPlanWall>): String {
        val fields = mutableListOf<String>()
        fun add(s: String) { fields += "${s.length}:$s" }
        add("GE360-ROOM-GEOMETRY-V2")
        add(room.ge360Id); add(room.floorPlanId); add(room.mode)
        add(room.name); add(room.roomType)
        listOf(room.length, room.width, room.height, room.cutWidth, room.cutHeight,
            room.manualFloor, room.manualCeiling, room.manualWalls, room.manualPerimeter,
            room.drawX, room.drawY, room.drawWidth, room.drawHeight).forEach { add(value(it)) }
        walls.sortedWith(compareBy<FloorPlanWall> { it.sortOrder }.thenBy { it.ge360Id })
            .forEach { wall ->
                add(wall.ge360Id); add(wall.floorPlanId); add(wall.roomId)
                add(wall.sortOrder.toString()); add(wall.direction.toString())
                add(value(wall.length)); add(wall.note)
            }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString("|").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun validatedOpenArea(wall: FloorPlanWall, height: Double): Double {
        val metadataAt = wall.note.indexOf(FloorPlanWallOpenings.META_MARKER)
        val openings = FloorPlanWallOpenings.openings(wall.note)
        if (metadataAt >= 0) {
            val raw = wall.note.substring(
                metadataAt + FloorPlanWallOpenings.META_MARKER.length
            ).trim()
            val parsed = runCatching { JSONArray(raw) }.getOrElse {
                error("Metadati aperture danneggiati: verificare la parete")
            }
            require(parsed.length() == openings.size &&
                openings.map { it.id }.distinct().size == openings.size) {
                "Aperture duplicate o dati non validi"
            }
            for (i in 0 until parsed.length()) {
                val original = parsed.getJSONObject(i)
                val type = original.getString("type")
                require(type in OpeningTypes.ALL) { "Tipo apertura non supportato" }
                val width = original.getDouble("width")
                val openingHeight = original.getDouble("height")
                val offset = original.getDouble("offset")
                val sill = original.optDouble("sillHeight", 0.0)
                require(width.isFinite() && width > 0 && openingHeight.isFinite() &&
                    openingHeight > 0 && offset.isFinite() && offset >= 0 &&
                    sill.isFinite() && sill >= 0) {
                    "Dimensioni di apertura non valide"
                }
            }
        }
        var deduction = 0.0
        openings.forEach { opening ->
            require(opening.width.isFinite() && opening.height.isFinite() &&
                opening.offset.isFinite() && opening.sillHeight.isFinite() &&
                opening.height > 0 && opening.sillHeight >= 0) {
                "Apertura priva di dimensioni attendibili"
            }
            require(FloorPlanWallOpenings.validate(opening, wall.length) == null) {
                "Apertura oltre il limite del muro"
            }
            require(opening.height + opening.sillHeight <= height + 0.001) {
                "Apertura più alta del muro: verificare sul posto"
            }
            require(FloorPlanWallOpenings.overlapConflict(opening, openings) == null) {
                "Aperture sovrapposte sulla stessa parete"
            }
            deduction += opening.width * opening.height
        }
        return deduction
    }

    fun measure(
        room: FloorPlanRoom,
        walls: List<FloorPlanWall>,
        surface: String,
        wallGe360Id: String? = null
    ): Measured {
        require(surface in PlanWorkBindingV1.SURFACES) { "Tipo superficie non supportato" }
        require(walls.all { it.roomId == room.ge360Id && it.floorPlanId == room.floorPlanId }) {
            "Pareti riferite a un altro ambiente"
        }
        val fp = fingerprint(room, walls)
        val single = surface == PlanWorkBindingV1.SINGLE_WALL_GROSS ||
            surface == PlanWorkBindingV1.SINGLE_WALL_NET
        val net = surface == PlanWorkBindingV1.WALLS_NET ||
            surface == PlanWorkBindingV1.SINGLE_WALL_NET
        require(!single || !wallGe360Id.isNullOrBlank()) { "Seleziona una parete precisa" }
        require(single || wallGe360Id.isNullOrBlank()) { "Parete non prevista per questo tipo di superficie" }

        require(listOf(room.length, room.width, room.height, room.cutWidth,
            room.cutHeight, room.manualFloor, room.manualCeiling, room.manualWalls,
            room.manualPerimeter).all { it.isFinite() && it >= 0.0 }) {
            "Misure stanza non valide"
        }
        if (room.mode == FloorPlanRoomModes.L_SHAPE) {
            require(room.length > 0 && room.width > 0 &&
                room.cutWidth <= room.length && room.cutHeight <= room.width) {
                "Dimensioni della stanza a L non coerenti"
            }
        }
        val needsWalls = single || net || room.mode == FloorPlanRoomModes.WALLS ||
            (surface == PlanWorkBindingV1.WALLS && walls.isNotEmpty())
        if (needsWalls) {
            require(walls.isNotEmpty() && FloorPlanWallGeometry.isValidClosedProfile(walls)) {
                "Profilo muri non chiuso o irregolare: correggere il rilievo"
            }
        }
        val selectedWall = if (single) walls.singleOrNull { it.ge360Id == wallGe360Id }
            ?: error("Parete selezionata non trovata") else null
        val gross: Double = when (surface) {
            PlanWorkBindingV1.FLOOR -> {
                if (room.mode == FloorPlanRoomModes.WALLS) {
                    val geometricArea = FloorPlanWallGeometry.area(walls)
                    require(room.manualFloor <= 0.0 ||
                        abs(room.manualFloor - geometricArea) <= 0.01) {
                        "Area manuale diversa dalla pianta quotata: riconciliare il rilievo"
                    }
                    geometricArea
                } else room.floorArea()
            }
            PlanWorkBindingV1.CEILING -> {
                if (room.mode == FloorPlanRoomModes.WALLS && room.manualCeiling <= 0.0)
                    FloorPlanWallGeometry.area(walls)
                else room.ceilingArea()
            }
            PlanWorkBindingV1.WALLS, PlanWorkBindingV1.WALLS_NET -> {
                require(room.height.isFinite() && room.height > 0.0) {
                    "Altezza ambiente assente"
                }
                if (walls.isNotEmpty()) walls.sumOf { it.length * room.height }
                else room.wallArea()
            }
            PlanWorkBindingV1.SINGLE_WALL_GROSS, PlanWorkBindingV1.SINGLE_WALL_NET -> {
                require(room.height.isFinite() && room.height > 0.0) {
                    "Altezza ambiente assente"
                }
                selectedWall!!.length * room.height
            }
            else -> error("Superficie non supportata")
        }
        require(gross.isFinite() && gross > 0.0) { "Superficie non misurabile" }
        val relevantWalls = if (single) listOf(selectedWall!!) else walls
        val deduction = if (net) relevantWalls.sumOf { validatedOpenArea(it, room.height) } else 0.0
        val netArea = gross - deduction
        require(netArea.isFinite() && netArea > 0.005 && deduction <= gross + 0.001) {
            "Le aperture superano la superficie del muro"
        }
        fun round2(x: Double): Double = kotlin.math.round(x * 100.0) / 100.0
        return Measured(
            quantityM2 = round2(netArea),
            grossM2 = round2(gross),
            openingDeductionsM2 = round2(deduction),
            fingerprint = fp,
            surface = surface,
            wallGe360Id = selectedWall?.ge360Id
        )
    }

    fun matches(
        binding: PlanWorkBindingV1, room: FloorPlanRoom, walls: List<FloorPlanWall>
    ): Boolean {
        if (binding.geometryFingerprint.isBlank()) return false
        return runCatching {
            val current = measure(room, walls, binding.surface, binding.wallGe360Id)
            binding.geometryFingerprint == current.fingerprint &&
                abs(binding.quantityM2 - current.quantityM2) <= 0.001 &&
                binding.roomGe360Id == room.ge360Id &&
                binding.planGe360Id == room.floorPlanId
        }.getOrDefault(false)
    }
}
