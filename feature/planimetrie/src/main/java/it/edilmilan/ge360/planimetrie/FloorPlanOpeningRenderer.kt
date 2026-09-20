package it.edilmilan.ge360.planimetrie

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.hypot

/** Disegna le aperture sopra il segmento muro senza alterare alcuna quantità. */
object FloorPlanOpeningRenderer {
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.SQUARE
    }
    private val doorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B85700")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E7185")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.SQUARE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50565C")
        textSize = 16f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun draw(canvas: Canvas, wall: FloorPlanWall, ax: Float, ay: Float, bx: Float, by: Float, scale: Float) {
        if (wall.length <= 0.0) return
        val rows = FloorPlanWallOpenings.openings(wall.note)
        if (rows.isEmpty()) return
        val vx = bx - ax
        val vy = by - ay
        val pixelLength = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        if (pixelLength <= 0.1f) return
        val ux = vx / pixelLength
        val uy = vy / pixelLength
        val nx = -uy
        val ny = ux

        rows.forEach { opening ->
            if (FloorPlanWallOpenings.validate(opening, wall.length) != null) return@forEach
            val startPx = (opening.offset * scale).toFloat()
            val widthPx = (opening.width * scale).toFloat()
            val sx = ax + ux * startPx
            val sy = ay + uy * startPx
            val ex = sx + ux * widthPx
            val ey = sy + uy * widthPx

            canvas.drawLine(sx, sy, ex, ey, gapPaint)
            when (opening.type) {
                OpeningTypes.WINDOW -> drawWindow(canvas, sx, sy, ex, ey, nx, ny)
                else -> drawDoor(canvas, opening, sx, sy, ex, ey, ux, uy, nx, ny, widthPx)
            }

            if (scale >= 38f) {
                val cx = (sx + ex) / 2f + nx * 13f
                val cy = (sy + ey) / 2f + ny * 13f + 5f
                canvas.drawText(if (opening.type == OpeningTypes.WINDOW) "F" else "P", cx, cy, labelPaint)
            }
        }
    }

    private fun drawWindow(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float, nx: Float, ny: Float) {
        val d = 3.5f
        canvas.drawLine(sx + nx * d, sy + ny * d, ex + nx * d, ey + ny * d, windowPaint)
        canvas.drawLine(sx - nx * d, sy - ny * d, ex - nx * d, ey - ny * d, windowPaint)
    }

    private fun drawDoor(
        canvas: Canvas,
        opening: FloorPlanOpening,
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
        ux: Float,
        uy: Float,
        nx: Float,
        ny: Float,
        widthPx: Float
    ) {
        val hingeAtEnd = opening.swing == DoorSwings.RIGHT
        val hx = if (hingeAtEnd) ex else sx
        val hy = if (hingeAtEnd) ey else sy
        val leaf = widthPx.coerceAtMost(70f)
        canvas.drawCircle(hx, hy, 4f, Paint(doorPaint).apply { style = Paint.Style.FILL })

        if (opening.swing == DoorSwings.NONE) {
            // Simbolo porta neutro: piccole spallette ai due estremi.
            canvas.drawLine(sx - nx * 7f, sy - ny * 7f, sx + nx * 7f, sy + ny * 7f, doorPaint)
            canvas.drawLine(ex - nx * 7f, ey - ny * 7f, ex + nx * 7f, ey + ny * 7f, doorPaint)
            return
        }

        // Anta schematica a 90° rispetto alla parete. Il verso SX/DX sceglie la cerniera.
        val sign = if (hingeAtEnd) -1f else 1f
        val lx = hx + nx * leaf + ux * 2f * sign
        val ly = hy + ny * leaf + uy * 2f * sign
        canvas.drawLine(hx, hy, lx, ly, doorPaint)
    }
}
