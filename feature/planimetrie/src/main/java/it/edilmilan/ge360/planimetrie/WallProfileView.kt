package it.edilmilan.ge360.planimetrie

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WallProfileView(context: Context) : View(context) {
    var walls: List<FloorPlanWall> = emptyList()
        set(value) {
            field = value.sortedBy { it.sortOrder }
            invalidate()
        }

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECE8E2")
        strokeWidth = 1f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F1EC")
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#202327")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
    }
    private val openStroke = Paint(stroke).apply { color = Color.parseColor("#B85700") }
    private val dimension = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5E646A")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B85700")
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        if (walls.isEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#777C82")
                textSize = 28f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Aggiungi la prima parete", width / 2f, height / 2f, p)
            return
        }

        val points = FloorPlanWallGeometry.points(walls)
        val bounds = FloorPlanWallGeometry.bounds(walls)
        val spanW = max(bounds.width, 1.0)
        val spanH = max(bounds.height, 1.0)
        val pad = 62f
        val scale = min(
            (width - pad * 2f) / spanW.toFloat(),
            (height - pad * 2f) / spanH.toFloat()
        ).coerceIn(22f, 105f)
        val ox = pad - bounds.minX.toFloat() * scale
        val oy = pad - bounds.minY.toFloat() * scale

        drawGrid(canvas, ox, oy, scale)

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = ox + point.x.toFloat() * scale
            val y = oy + point.y.toFloat() * scale
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (FloorPlanWallGeometry.isValidClosedProfile(walls)) {
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        } else {
            canvas.drawPath(path, openStroke)
        }

        walls.sortedBy { it.sortOrder }.forEachIndexed { index, wall ->
            val a = points[index]
            val b = points[index + 1]
            val ax = ox + a.x.toFloat() * scale
            val ay = oy + a.y.toFloat() * scale
            val bx = ox + b.x.toFloat() * scale
            val by = oy + b.y.toFloat() * scale

            FloorPlanOpeningRenderer.draw(canvas, wall, ax, ay, bx, by, scale)

            val horizontal = abs(ax - bx) >= abs(ay - by)
            val tx = (ax + bx) / 2f + if (horizontal) 0f else 24f
            val ty = (ay + by) / 2f + if (horizontal) -12f else 8f
            canvas.drawText("${fmt(wall.length)} m", tx, ty, dimension)
        }

        points.forEach { point ->
            canvas.drawCircle(ox + point.x.toFloat() * scale, oy + point.y.toFloat() * scale, 6f, pointPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, ox: Float, oy: Float, scale: Float) {
        var x = ox % scale
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), grid)
            x += scale
        }
        var y = oy % scale
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
            y += scale
        }
    }
}
