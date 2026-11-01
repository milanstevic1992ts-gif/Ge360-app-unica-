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
    val createdAt: Long
) {
    fun valid(): Boolean =
        listOf(ge360Id, planGe360Id, roomGe360Id, estimateGe360Id, workCode).all { it.isNotBlank() } &&
        surface in SURFACES && quantityM2.isFinite() && quantityM2 > 0.0 &&
        roomRevisionAt > 0 && createdAt > 0

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

    companion object {
        const val FLOOR = "PAVIMENTO"
        const val CEILING = "SOFFITTO"
        const val WALLS = "PARETI"
        val SURFACES = listOf(FLOOR, CEILING, WALLS)

        fun quantity(room: FloorPlanRoom, surface: String): Double = when (surface) {
            FLOOR -> room.floorArea()
            CEILING -> room.ceilingArea()
            WALLS -> room.wallArea()
            else -> error("Superficie sconosciuta")
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

        fun fromJson(o: JSONObject) = PlanWorkBindingV1(
            ge360Id = o.getString("ge360Id"),
            planGe360Id = o.getString("planGe360Id"),
            roomGe360Id = o.getString("roomGe360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            workCode = o.getString("workCode"),
            surface = o.getString("surface"),
            quantityM2 = o.getDouble("quantityM2"),
            roomRevisionAt = o.getLong("roomRevisionAt"),
            createdAt = o.getLong("createdAt")
        )
    }
}
