package it.edilmilan.ge360.planimetrie

import org.json.JSONObject
import java.util.UUID

/**
 * Immutable planning projection: selected surfaces from the measured floor plan
 * tied to a real estimate UUID and a standard work catalogue code.
 * No quote item, price, invoice, or authoritative geometry is created.
 */
data class PlanWorkBindingV1(
    val ge360Id: String,
    val planGe360Id: String,
    val roomGe360Id: String,
    val estimateGe360Id: String,
    val workCode: String,
    val surface: String,
    val quantityM2: Double,
    val roomRevisionAt: Long,
    val createdAt: Long,
    // Blank on legacy V1 bindings: always requires renewed operator confirmation.
    val geometryFingerprint: String = "",
    val wallGe360Id: String? = null,
    val grossM2: Double = 0.0,
    val openingDeductionsM2: Double = 0.0
) {
    fun valid(): Boolean =
        listOf(ge360Id, planGe360Id, roomGe360Id, estimateGe360Id, workCode).all { it.isNotBlank() } &&
        surface in SURFACES && quantityM2.isFinite() && quantityM2 > 0.0 &&
        roomRevisionAt > 0 && createdAt > 0 &&
        grossM2.isFinite() && openingDeductionsM2.isFinite() &&
        grossM2 >= 0.0 && openingDeductionsM2 >= 0.0 &&
        (geometryFingerprint.isBlank() ||
            (grossM2 > 0.0 && grossM2 + 0.02 >= quantityM2 &&
                kotlin.math.abs(grossM2 - openingDeductionsM2 - quantityM2) <= 0.02)) &&
        (geometryFingerprint.isBlank() ||
            (geometryFingerprint.length == 64 && geometryFingerprint.all {
                it in '0'..'9' || it in 'a'..'f'
            })) &&
        ((surface == SINGLE_WALL_GROSS || surface == SINGLE_WALL_NET) ==
            !wallGe360Id.isNullOrBlank() || geometryFingerprint.isBlank())

    fun toJson() = JSONObject()
        .put("ge360Id", ge360Id)
        .put("planGe360Id", planGe360Id)
        .put("roomGe360Id", roomGe360Id)
        .put("estimateGe360Id", estimateGe360Id)
        .put("workCode", workCode)
        .put("surface", surface)
        .put("quantityM2", quantityM2)
        .put("roomRevisionAt", roomRevisionAt)
        .put("createdAt", createdAt)
        .put("geometryFingerprint", geometryFingerprint)
        .put("wallGe360Id", wallGe360Id.orEmpty())
        .put("grossM2", grossM2)
        .put("openingDeductionsM2", openingDeductionsM2)

    companion object {
        const val FLOOR = "PAVIMENTO"
        const val CEILING = "SOFFITTO"
        const val WALLS = "PARETI"
        const val WALLS_NET = "PARETI_NETTE"
        const val SINGLE_WALL_GROSS = "PARETE_SINGOLA_LORDA"
        const val SINGLE_WALL_NET = "PARETE_SINGOLA_NETTA"
        val SURFACES = listOf(FLOOR, CEILING, WALLS, WALLS_NET,
            SINGLE_WALL_GROSS, SINGLE_WALL_NET)

        fun quantity(room: FloorPlanRoom, surface: String): Double = when (surface) {
            FLOOR -> room.floorArea()
            CEILING -> room.ceilingArea()
            WALLS -> room.wallArea()
            else -> error("Usare il motore geometrico V2 per superfici nette o pareti singole")
        }

        fun fromRoom(room: FloorPlanRoom, estimateId: String, workCode: String,
                     surface: String): PlanWorkBindingV1 = PlanWorkBindingV1(
            ge360Id = UUID.randomUUID().toString(),
            planGe360Id = room.floorPlanId,
            roomGe360Id = room.ge360Id,
            estimateGe360Id = estimateId,
            workCode = workCode,
            surface = surface,
            quantityM2 = quantity(room, surface),
            roomRevisionAt = room.updatedAt,
            createdAt = System.currentTimeMillis()
        )

        fun fromMeasured(
            room: FloorPlanRoom,
            estimateId: String,
            workCode: String,
            measured: PlanGeometryQuantitiesV2.Measured
        ): PlanWorkBindingV1 = PlanWorkBindingV1(
            ge360Id = UUID.randomUUID().toString(),
            planGe360Id = room.floorPlanId,
            roomGe360Id = room.ge360Id,
            estimateGe360Id = estimateId,
            workCode = workCode,
            surface = measured.surface,
            quantityM2 = measured.quantityM2,
            roomRevisionAt = room.updatedAt,
            createdAt = System.currentTimeMillis(),
            geometryFingerprint = measured.fingerprint,
            wallGe360Id = measured.wallGe360Id,
            grossM2 = measured.grossM2,
            openingDeductionsM2 = measured.openingDeductionsM2
        )

        fun fromJson(o: JSONObject) = PlanWorkBindingV1(
            ge360Id = o.getString("ge360Id"),
            planGe360Id = o.getString("planGe360Id"),
            roomGe360Id = o.getString("roomGe360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            workCode = o.getString("workCode"),
            surface = o.getString("surface"),
            quantityM2 = o.getDouble("quantityM2"),
            roomRevisionAt = o.getLong("roomRevisionAt"),
            createdAt = o.getLong("createdAt"),
            geometryFingerprint = o.optString("geometryFingerprint", ""),
            wallGe360Id = o.optString("wallGe360Id", "").ifBlank { null },
            grossM2 = o.optDouble("grossM2", 0.0),
            openingDeductionsM2 = o.optDouble("openingDeductionsM2", 0.0)
        )
    }
}
