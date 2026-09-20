package it.edilmilan.ge360.planimetrie

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

data class FloorPlanWall(
    var id: Long = 0,
    var ge360Id: String = UUID.randomUUID().toString(),
    var floorPlanId: String = "",
    var roomId: String = "",
    var length: Double = 0.0,
    var direction: Int = WallDirections.EAST,
    var note: String = "",
    var sortOrder: Int = 0,
    var updatedAt: Long = System.currentTimeMillis()
)

data class WallPoint(val x: Double, val y: Double)

data class WallBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double
) {
    val width: Double get() = (maxX - minX).coerceAtLeast(0.0)
    val height: Double get() = (maxY - minY).coerceAtLeast(0.0)
}

object WallDirections {
    const val EAST = 0
    const val SOUTH = 90
    const val WEST = 180
    const val NORTH = 270

    val ALL = listOf(EAST, SOUTH, WEST, NORTH)
    val LABELS = listOf("Destra →", "Giù ↓", "Sinistra ←", "Su ↑")

    fun normalized(value: Int): Int = when (((value % 360) + 360) % 360) {
        in 45..134 -> SOUTH
        in 135..224 -> WEST
        in 225..314 -> NORTH
        else -> EAST
    }

    fun label(value: Int): String = when (normalized(value)) {
        EAST -> "Destra →"
        SOUTH -> "Giù ↓"
        WEST -> "Sinistra ←"
        else -> "Su ↑"
    }

    fun fromLabel(label: String): Int = when {
        label.contains("Giù", true) -> SOUTH
        label.contains("Sinistra", true) -> WEST
        label.contains("Su", true) -> NORTH
        else -> EAST
    }

    fun dx(direction: Int, length: Double): Double = when (normalized(direction)) {
        EAST -> length
        WEST -> -length
        else -> 0.0
    }

    fun dy(direction: Int, length: Double): Double = when (normalized(direction)) {
        SOUTH -> length
        NORTH -> -length
        else -> 0.0
    }
}

object FloorPlanWallGeometry {
    private const val EPS = 0.01

    fun points(walls: List<FloorPlanWall>): List<WallPoint> {
        val out = mutableListOf(WallPoint(0.0, 0.0))
        var x = 0.0
        var y = 0.0
        walls.sortedBy { it.sortOrder }.forEach { wall ->
            x += WallDirections.dx(wall.direction, wall.length.coerceAtLeast(0.0))
            y += WallDirections.dy(wall.direction, wall.length.coerceAtLeast(0.0))
            out += WallPoint(round3(x), round3(y))
        }
        return out
    }

    fun isClosed(walls: List<FloorPlanWall>): Boolean {
        if (walls.size < 4) return false
        val end = points(walls).lastOrNull() ?: return false
        return abs(end.x) <= EPS && abs(end.y) <= EPS
    }

    fun hasSelfIntersections(walls: List<FloorPlanWall>): Boolean {
        if (walls.size < 3) return false
        val pts = points(walls)
        val count = walls.size
        for (i in 0 until count) {
            val a1 = pts[i]
            val a2 = pts[i + 1]
            for (j in i + 1 until count) {
                val adjacent = j == i + 1 || (isClosed(walls) && i == 0 && j == count - 1)
                if (adjacent) continue
                val b1 = pts[j]
                val b2 = pts[j + 1]
                if (segmentsIntersect(a1, a2, b1, b2)) return true
            }
        }
        return false
    }

    fun isValidClosedProfile(walls: List<FloorPlanWall>): Boolean =
        isClosed(walls) && !hasSelfIntersections(walls) && rawArea(walls) > EPS

    fun perimeter(walls: List<FloorPlanWall>): Double =
        round2Wall(walls.sumOf { it.length.coerceAtLeast(0.0) })

    fun area(walls: List<FloorPlanWall>): Double {
        if (!isValidClosedProfile(walls)) return 0.0
        return round2Wall(rawArea(walls))
    }

    private fun rawArea(walls: List<FloorPlanWall>): Double {
        if (!isClosed(walls)) return 0.0
        val pts = points(walls)
        if (pts.size < 4) return 0.0
        var sum = 0.0
        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }

    fun bounds(walls: List<FloorPlanWall>): WallBounds {
        val pts = points(walls)
        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        pts.forEach {
            minX = min(minX, it.x)
            minY = min(minY, it.y)
            maxX = max(maxX, it.x)
            maxY = max(maxY, it.y)
        }
        return WallBounds(minX, minY, maxX, maxY)
    }

    fun closureSegments(walls: List<FloorPlanWall>, floorPlanId: String, roomId: String): List<FloorPlanWall> {
        if (walls.isEmpty() || isClosed(walls)) return emptyList()
        val end = points(walls).last()
        val out = mutableListOf<FloorPlanWall>()
        var order = (walls.maxOfOrNull { it.sortOrder } ?: -1) + 1

        if (abs(end.x) > EPS) {
            out += FloorPlanWall(
                floorPlanId = floorPlanId,
                roomId = roomId,
                length = abs(end.x),
                direction = if (end.x > 0) WallDirections.WEST else WallDirections.EAST,
                note = "Chiusura automatica",
                sortOrder = order++
            )
        }
        if (abs(end.y) > EPS) {
            out += FloorPlanWall(
                floorPlanId = floorPlanId,
                roomId = roomId,
                length = abs(end.y),
                direction = if (end.y > 0) WallDirections.NORTH else WallDirections.SOUTH,
                note = "Chiusura automatica",
                sortOrder = order
            )
        }
        return out
    }

    fun suggestedNextDirection(walls: List<FloorPlanWall>): Int {
        val last = walls.maxByOrNull { it.sortOrder } ?: return WallDirections.EAST
        return when (WallDirections.normalized(last.direction)) {
            WallDirections.EAST -> WallDirections.SOUTH
            WallDirections.SOUTH -> WallDirections.WEST
            WallDirections.WEST -> WallDirections.NORTH
            else -> WallDirections.EAST
        }
    }

    fun description(walls: List<FloorPlanWall>): String = when {
        walls.isEmpty() -> "Nessuna parete quotata"
        hasSelfIntersections(walls) -> "${walls.size} pareti · profilo incrociato · correggi i muri"
        isValidClosedProfile(walls) -> "${walls.size} pareti · chiusa · ${fmt(area(walls))} m² · ${fmt(perimeter(walls))} ml"
        isClosed(walls) -> "${walls.size} pareti · chiusa ma non valida · controlla il profilo"
        else -> "${walls.size} pareti · profilo aperto · ${fmt(perimeter(walls))} ml"
    }

    private fun segmentsIntersect(a1: WallPoint, a2: WallPoint, b1: WallPoint, b2: WallPoint): Boolean {
        val aHorizontal = abs(a1.y - a2.y) <= EPS
        val bHorizontal = abs(b1.y - b2.y) <= EPS

        if (aHorizontal && bHorizontal) {
            if (abs(a1.y - b1.y) > EPS) return false
            return rangesTouch(a1.x, a2.x, b1.x, b2.x)
        }
        if (!aHorizontal && !bHorizontal) {
            if (abs(a1.x - b1.x) > EPS) return false
            return rangesTouch(a1.y, a2.y, b1.y, b2.y)
        }

        val h1 = if (aHorizontal) a1 else b1
        val h2 = if (aHorizontal) a2 else b2
        val v1 = if (aHorizontal) b1 else a1
        val v2 = if (aHorizontal) b2 else a2
        val hxMin = min(h1.x, h2.x) - EPS
        val hxMax = max(h1.x, h2.x) + EPS
        val vyMin = min(v1.y, v2.y) - EPS
        val vyMax = max(v1.y, v2.y) + EPS
        return v1.x in hxMin..hxMax && h1.y in vyMin..vyMax
    }

    private fun rangesTouch(a1: Double, a2: Double, b1: Double, b2: Double): Boolean {
        val left = max(min(a1, a2), min(b1, b2))
        val right = min(max(a1, a2), max(b1, b2))
        return right >= left - EPS
    }
}

private fun round2Wall(value: Double): Double = round(value * 100.0) / 100.0
private fun round3(value: Double): Double = round(value * 1000.0) / 1000.0
