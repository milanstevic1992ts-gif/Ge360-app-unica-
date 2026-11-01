package it.edilmilan.ge360.planimetrie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression cases prepared only. Not executed; Android JSON-bearing cases
 * require a real org.json implementation in the selected test environment.
 */
class PlanGeometryQuantitiesV2Test {
    private fun room(mode: String = FloorPlanRoomModes.WALLS) = FloorPlanRoom(
        ge360Id = "room-01",
        floorPlanId = "plan-01",
        name = "Cucina",
        mode = mode,
        length = 4.0,
        width = 3.0,
        height = 2.8,
        manualFloor = if (mode == FloorPlanRoomModes.WALLS) 12.0 else 0.0,
        updatedAt = 1000L
    )

    private fun walls(): List<FloorPlanWall> = listOf(
        FloorPlanWall(ge360Id = "w1", floorPlanId = "plan-01", roomId = "room-01",
            length = 4.0, direction = WallDirections.EAST, sortOrder = 0),
        FloorPlanWall(ge360Id = "w2", floorPlanId = "plan-01", roomId = "room-01",
            length = 3.0, direction = WallDirections.SOUTH, sortOrder = 1),
        FloorPlanWall(ge360Id = "w3", floorPlanId = "plan-01", roomId = "room-01",
            length = 4.0, direction = WallDirections.WEST, sortOrder = 2),
        FloorPlanWall(ge360Id = "w4", floorPlanId = "plan-01", roomId = "room-01",
            length = 3.0, direction = WallDirections.NORTH, sortOrder = 3)
    )

    @Test fun geometrySignatureChangesWhenDoorOrWindowMetadataChanges() {
        val original = PlanGeometryQuantitiesV2.fingerprint(room(), walls())
        val revised = walls().toMutableList().apply {
            this[0] = this[0].copy(note =
                "[GE360_OPENINGS][{\"id\":\"door-01\",\"type\":\"Porta\",\"offset\":1.0,\"width\":0.8,\"height\":2.1}]")
        }
        assertNotEquals(original, PlanGeometryQuantitiesV2.fingerprint(room(), revised))
    }

    @Test fun fingerprintIgnoresVolatileDatabaseIdsAndUpdatedTime() {
        val original = PlanGeometryQuantitiesV2.fingerprint(room(), walls())
        val reread = walls().map { it.copy(id = 97, updatedAt = 900000L) }
        assertEquals(original, PlanGeometryQuantitiesV2.fingerprint(room(), reread))
    }

    @Test fun immutableSelectionNeedsMatchingCurrentGeometry() {
        val base = room()
        val original = PlanGeometryQuantitiesV2.measure(base, walls(), PlanWorkBindingV1.FLOOR)
        val link = PlanWorkBindingV1.fromMeasured(base, "estimate-01", "CAP-03", original)
        assertTrue(PlanGeometryQuantitiesV2.matches(link, base, walls()))
        assertFalse(PlanGeometryQuantitiesV2.matches(link, base,
            walls().mapIndexed { i, w -> if (i == 0) w.copy(length = 4.1) else w }))
        assertFalse(PlanGeometryQuantitiesV2.matches(
            link.copy(geometryFingerprint = ""), base, walls()
        ))
    }

    @Test fun singleWallGrossUsesOneFaceNotTwo() {
        val result = PlanGeometryQuantitiesV2.measure(
            room(), walls(), PlanWorkBindingV1.SINGLE_WALL_GROSS, "w1"
        )
        assertEquals(11.2, result.quantityM2, 0.001)
        assertEquals("w1", result.wallGe360Id)
        assertEquals(0.0, result.openingDeductionsM2, 0.001)
    }

    @Test fun invalidOpenProfileCannotBeCertified() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometryQuantitiesV2.measure(
                room(), walls().dropLast(1), PlanWorkBindingV1.FLOOR
            )
        }
    }

    @Test fun materialFloorAreaCannotSilentlyDisagreeWithClosedPolygon() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanGeometryQuantitiesV2.measure(
                room().copy(manualFloor = 10.0), walls(), PlanWorkBindingV1.FLOOR
            )
        }
    }
}
