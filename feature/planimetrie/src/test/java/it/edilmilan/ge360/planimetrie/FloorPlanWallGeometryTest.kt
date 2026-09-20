package it.edilmilan.ge360.planimetrie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorPlanWallGeometryTest {

    @Test
    fun rectangleCalculatesAreaPerimeterAndBounds() {
        val walls = listOf(
            wall(4.0, WallDirections.EAST, 0),
            wall(3.0, WallDirections.SOUTH, 1),
            wall(4.0, WallDirections.WEST, 2),
            wall(3.0, WallDirections.NORTH, 3)
        )

        assertTrue(FloorPlanWallGeometry.isClosed(walls))
        assertTrue(FloorPlanWallGeometry.isValidClosedProfile(walls))
        assertEquals(12.0, FloorPlanWallGeometry.area(walls), 0.001)
        assertEquals(14.0, FloorPlanWallGeometry.perimeter(walls), 0.001)
        val bounds = FloorPlanWallGeometry.bounds(walls)
        assertEquals(4.0, bounds.width, 0.001)
        assertEquals(3.0, bounds.height, 0.001)
    }

    @Test
    fun lShapeUsesRealPolygonArea() {
        val walls = listOf(
            wall(4.0, WallDirections.EAST, 0),
            wall(2.0, WallDirections.SOUTH, 1),
            wall(1.0, WallDirections.WEST, 2),
            wall(1.0, WallDirections.SOUTH, 3),
            wall(3.0, WallDirections.WEST, 4),
            wall(3.0, WallDirections.NORTH, 5)
        )

        assertTrue(FloorPlanWallGeometry.isValidClosedProfile(walls))
        assertEquals(11.0, FloorPlanWallGeometry.area(walls), 0.001)
        assertEquals(14.0, FloorPlanWallGeometry.perimeter(walls), 0.001)
    }

    @Test
    fun closureAddsOrthogonalSegmentsBackToOrigin() {
        val open = listOf(
            wall(4.0, WallDirections.EAST, 0),
            wall(3.0, WallDirections.SOUTH, 1)
        )

        val closing = FloorPlanWallGeometry.closureSegments(open, "plan-1", "room-1")
        val closed = open + closing

        assertEquals(2, closing.size)
        assertEquals(WallDirections.WEST, closing[0].direction)
        assertEquals(4.0, closing[0].length, 0.001)
        assertEquals(WallDirections.NORTH, closing[1].direction)
        assertEquals(3.0, closing[1].length, 0.001)
        assertTrue(FloorPlanWallGeometry.isValidClosedProfile(closed))
        assertEquals(12.0, FloorPlanWallGeometry.area(closed), 0.001)
    }

    @Test
    fun selfIntersectingProfileIsRejected() {
        val crossed = listOf(
            wall(4.0, WallDirections.EAST, 0),
            wall(4.0, WallDirections.SOUTH, 1),
            wall(3.0, WallDirections.WEST, 2),
            wall(3.0, WallDirections.NORTH, 3),
            wall(2.0, WallDirections.EAST, 4),
            wall(2.0, WallDirections.SOUTH, 5),
            wall(3.0, WallDirections.WEST, 6),
            wall(3.0, WallDirections.NORTH, 7)
        )

        assertTrue(FloorPlanWallGeometry.isClosed(crossed))
        assertTrue(FloorPlanWallGeometry.hasSelfIntersections(crossed))
        assertFalse(FloorPlanWallGeometry.isValidClosedProfile(crossed))
        assertEquals(0.0, FloorPlanWallGeometry.area(crossed), 0.001)
    }

    private fun wall(length: Double, direction: Int, order: Int) = FloorPlanWall(
        floorPlanId = "plan-1",
        roomId = "room-1",
        length = length,
        direction = direction,
        sortOrder = order
    )
}
