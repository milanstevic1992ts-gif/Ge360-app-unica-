package it.edilmilan.ge360.centrocantiere

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.text.NumberFormat
import java.util.Locale

/** Grafico a ciambella leggero, senza dipendenze esterne. */
data class DonutSlice(
    val label: String,
    val value: Double,
    val color: Int,
    val detail: String = ""
)

class CostDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 59, 50)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 119, 112)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT
    }

    private var slices: List<DonutSlice> = emptyList()
    private var centerValue: String = "€ 0"
    private var centerLabel: String = "TOTALE"

    fun setData(rows: List<DonutSlice>, total: Double, label: String = "TOTALE") {
        slices = rows.filter { it.value > 0.0001 }
        centerValue = NumberFormat.getCurrencyInstance(Locale.ITALY).format(total)
        centerLabel = label
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (260 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val stroke = size * 0.16f
        val pad = stroke / 2f + size * 0.035f
        val oval = RectF(pad, pad, w - pad, h - pad)
        arcPaint.strokeWidth = stroke

        val total = slices.sumOf { it.value }
        if (total <= 0.0001) {
            arcPaint.color = Color.rgb(224, 226, 221)
            canvas.drawArc(oval, -90f, 360f, false, arcPaint)
        } else {
            var start = -90f
            slices.forEach { slice ->
                val sweep = (slice.value / total * 360.0).toFloat()
                arcPaint.color = slice.color
                canvas.drawArc(oval, start, sweep, false, arcPaint)
                start += sweep
            }
        }

        centerPaint.textSize = size * 0.092f
        subtitlePaint.textSize = size * 0.042f
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawText(centerValue, cx, cy - size * 0.005f, centerPaint)
        canvas.drawText(centerLabel, cx, cy + size * 0.085f, subtitlePaint)
    }
}
