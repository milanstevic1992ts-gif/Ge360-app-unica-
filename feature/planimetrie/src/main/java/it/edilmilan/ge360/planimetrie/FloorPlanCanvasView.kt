package it.edilmilan.ge360.planimetrie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloorPlanCanvasView(context: Context) : View(context) {
    var rooms: MutableList<FloorPlanRoom> = mutableListOf()
        set(value) {
            field = value
            invalidate()
        }
    var wallProfiles: Map<String, List<FloorPlanWall>> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }
    var onRoomMoved: ((FloorPlanRoom) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.SurfaceWarm; style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.Text; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val manualStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ge360Brand.Accent; style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.Text; textSize = 30f; isFakeBoldText = true }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.TextSecondary; textSize = 22f }
    private val dimensionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.TextSecondary; textSize = 17f; textAlign = Paint.Align.CENTER }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.Border; strokeWidth = 1f }
    private val hitRects = linkedMapOf<String, RectF>()

    private var scale = 50f
    private var originX = 30f
    private var originY = 30f
    private var selected: FloorPlanRoom? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Ge360Brand.Surface)
        if (rooms.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ge360Brand.TextSecondary; textSize = 30f; textAlign = Paint.Align.CENTER }
            canvas.drawText("Aggiungi il primo ambiente", width / 2f, height / 2f, p)
            return
        }

        val worldW = max(rooms.maxOfOrNull { it.drawX + it.footprintWidth() } ?: 1.0, 1.0)
        val worldH = max(rooms.maxOfOrNull { it.drawY + it.footprintHeight() } ?: 1.0, 1.0)
        val pad = 34f
        scale = min((width - pad * 2f) / worldW.toFloat(), (height - pad * 2f) / worldH.toFloat()).coerceIn(18f, 95f)
        originX = pad
        originY = pad

        drawGrid(canvas, worldW, worldH)
        hitRects.clear()
        rooms.forEach { drawRoom(canvas, it) }
    }

    private fun drawGrid(canvas: Canvas, worldW: Double, worldH: Double) {
        val maxX = originX + worldW.toFloat() * scale
        val maxY = originY + worldH.toFloat() * scale
        var x = originX
        while (x <= maxX) { canvas.drawLine(x, originY, x, maxY, gridPaint); x += scale }
        var y = originY
        while (y <= maxY) { canvas.drawLine(originX, y, maxX, y, gridPaint); y += scale }
    }

    private fun drawRoom(canvas: Canvas, room: FloorPlanRoom) {
        val x = originX + room.drawX.toFloat() * scale
        val y = originY + room.drawY.toFloat() * scale
        val w = room.footprintWidth().toFloat() * scale
        val h = room.footprintHeight().toFloat() * scale
        val rect = RectF(x, y, x + w, y + h)
        hitRects[room.ge360Id] = rect

        val profile = wallProfiles[room.ge360Id].orEmpty().sortedBy { it.sortOrder }
        when {
            room.mode == FloorPlanRoomModes.WALLS && profile.isNotEmpty() -> drawWallRoom(canvas, room, profile, x, y)
            room.mode == FloorPlanRoomModes.L_SHAPE && room.cutWidth > 0.0 && room.cutHeight > 0.0 && room.length > 0.0 && room.width > 0.0 -> {
                val cw = room.cutWidth.coerceAtMost(room.length).toFloat() * scale
                val ch = room.cutHeight.coerceAtMost(room.width).toFloat() * scale
                val path = Path().apply {
                    moveTo(x, y)
                    lineTo(x + w, y)
                    lineTo(x + w, y + h - ch)
                    lineTo(x + w - cw, y + h - ch)
                    lineTo(x + w - cw, y + h)
                    lineTo(x, y + h)
                    close()
                }
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, strokePaint)
            }
            else -> {
                canvas.drawRect(rect, fillPaint)
                canvas.drawRect(rect, if (room.mode == FloorPlanRoomModes.MANUAL) manualStroke else strokePaint)
            }
        }

        val tx = x + 12f
        val ty = y + 34f
        canvas.drawText(room.name, tx, ty, textPaint)
        canvas.drawText("${fmt(room.floorArea())} m²", tx, ty + 27f, subTextPaint)
        when (room.mode) {
            FloorPlanRoomModes.MANUAL -> canvas.drawText("schema manuale", tx, ty + 52f, subTextPaint)
            FloorPlanRoomModes.WALLS -> canvas.drawText("${profile.size} pareti quotate", tx, ty + 52f, subTextPaint)
        }
    }

    private fun drawWallRoom(canvas: Canvas, room: FloorPlanRoom, walls: List<FloorPlanWall>, x: Float, y: Float) {
        val points = FloorPlanWallGeometry.points(walls)
        val bounds = FloorPlanWallGeometry.bounds(walls)
        val path = Path()
        points.forEachIndexed { index, point ->
            val px = x + (point.x - bounds.minX).toFloat() * scale
            val py = y + (point.y - bounds.minY).toFloat() * scale
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        if (FloorPlanWallGeometry.isValidClosedProfile(walls)) {
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        } else {
            canvas.drawPath(path, manualStroke)
        }

        walls.forEachIndexed { index, wall ->
            val a = points[index]
            val b = points[index + 1]
            val ax = x + (a.x - bounds.minX).toFloat() * scale
            val ay = y + (a.y - bounds.minY).toFloat() * scale
            val bx = x + (b.x - bounds.minX).toFloat() * scale
            val by = y + (b.y - bounds.minY).toFloat() * scale

            FloorPlanOpeningRenderer.draw(canvas, wall, ax, ay, bx, by, scale)

            if (scale >= 28f) {
                val horizontal = abs(ax - bx) >= abs(ay - by)
                canvas.drawText(
                    "${fmt(wall.length)}m",
                    (ax + bx) / 2f + if (horizontal) 0f else 16f,
                    (ay + by) / 2f + if (horizontal) -7f else 5f,
                    dimensionPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = rooms.asReversed().firstOrNull { hitRects[it.ge360Id]?.contains(event.x, event.y) == true }
                if (selected != null) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = event.x
                    lastY = event.y
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val room = selected ?: return false
                val dx = (event.x - lastX) / scale
                val dy = (event.y - lastY) / scale
                room.drawX = (room.drawX + dx).coerceAtLeast(0.0)
                room.drawY = (room.drawY + dy).coerceAtLeast(0.0)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selected?.let { room ->
                    if (event.actionMasked == MotionEvent.ACTION_UP) snapRoom(room)
                    onRoomMoved?.invoke(room)
                }
                selected = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun snapRoom(room: FloorPlanRoom) {
        val others = rooms.filterNot { it.ge360Id == room.ge360Id }
        if (others.isEmpty()) return

        val selectedSegments = worldSegments(room)
        val otherSegments = others.flatMap(::worldSegments)
        var bestDx: Double? = null
        var bestDy: Double? = null

        selectedSegments.forEach { mine ->
            otherSegments.forEach { other ->
                if (mine.horizontal && other.horizontal && overlap(mine.x1, mine.x2, other.x1, other.x2) >= MIN_OVERLAP) {
                    val dy = other.y1 - mine.y1
                    if (abs(dy) <= SNAP_DISTANCE && (bestDy == null || abs(dy) < abs(bestDy!!))) bestDy = dy
                } else if (!mine.horizontal && !other.horizontal && overlap(mine.y1, mine.y2, other.y1, other.y2) >= MIN_OVERLAP) {
                    val dx = other.x1 - mine.x1
                    if (abs(dx) <= SNAP_DISTANCE && (bestDx == null || abs(dx) < abs(bestDx!!))) bestDx = dx
                }
            }
        }

        if (bestDx == null) bestDx = fallbackSnapX(room, others)
        if (bestDy == null) bestDy = fallbackSnapY(room, others)

        bestDx?.let { room.drawX = (room.drawX + it).coerceAtLeast(0.0) }
        bestDy?.let { room.drawY = (room.drawY + it).coerceAtLeast(0.0) }
    }

    private fun worldSegments(room: FloorPlanRoom): List<WorldSegment> {
        val walls = wallProfiles[room.ge360Id].orEmpty().sortedBy { it.sortOrder }
        if (room.mode == FloorPlanRoomModes.WALLS && walls.isNotEmpty()) {
            val points = FloorPlanWallGeometry.points(walls)
            val bounds = FloorPlanWallGeometry.bounds(walls)
            return walls.indices.map { index ->
                val a = points[index]
                val b = points[index + 1]
                val x1 = room.drawX + (a.x - bounds.minX)
                val y1 = room.drawY + (a.y - bounds.minY)
                val x2 = room.drawX + (b.x - bounds.minX)
                val y2 = room.drawY + (b.y - bounds.minY)
                WorldSegment(x1, y1, x2, y2, abs(y1 - y2) <= 0.01)
            }
        }

        val l = room.drawX
        val t = room.drawY
        val r = l + room.footprintWidth()
        val b = t + room.footprintHeight()
        return listOf(
            WorldSegment(l, t, r, t, true),
            WorldSegment(r, t, r, b, false),
            WorldSegment(r, b, l, b, true),
            WorldSegment(l, b, l, t, false)
        )
    }

    private fun fallbackSnapX(room: FloorPlanRoom, others: List<FloorPlanRoom>): Double? {
        val w = room.footprintWidth()
        val candidates = mutableListOf<Double>()
        others.forEach { other ->
            val ol = other.drawX
            val or = other.drawX + other.footprintWidth()
            candidates += ol - room.drawX
            candidates += or - room.drawX
            candidates += ol - (room.drawX + w)
            candidates += or - (room.drawX + w)
        }
        return candidates.filter { abs(it) <= SNAP_DISTANCE }.minByOrNull { abs(it) }
    }

    private fun fallbackSnapY(room: FloorPlanRoom, others: List<FloorPlanRoom>): Double? {
        val h = room.footprintHeight()
        val candidates = mutableListOf<Double>()
        others.forEach { other ->
            val ot = other.drawY
            val ob = other.drawY + other.footprintHeight()
            candidates += ot - room.drawY
            candidates += ob - room.drawY
            candidates += ot - (room.drawY + h)
            candidates += ob - (room.drawY + h)
        }
        return candidates.filter { abs(it) <= SNAP_DISTANCE }.minByOrNull { abs(it) }
    }

    private fun overlap(a1: Double, a2: Double, b1: Double, b2: Double): Double {
        val amin = min(a1, a2)
        val amax = max(a1, a2)
        val bmin = min(b1, b2)
        val bmax = max(b1, b2)
        return (min(amax, bmax) - max(amin, bmin)).coerceAtLeast(0.0)
    }

    private data class WorldSegment(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val horizontal: Boolean
    )

    companion object {
        private const val SNAP_DISTANCE = 0.20
        private const val MIN_OVERLAP = 0.15
    }
}
