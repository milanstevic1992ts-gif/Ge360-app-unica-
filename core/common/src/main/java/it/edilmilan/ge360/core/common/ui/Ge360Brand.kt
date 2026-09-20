package it.edilmilan.ge360.core.common.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Design system condiviso EDIL MILAN STEVIC / GE360.
 *
 * La palette riprende il marchio aziendale: arancione cantiere, verde scuro,
 * fondo caldo e blu tecnico. Tenerla qui evita colori diversi tra i moduli.
 */
object Ge360Brand {
    val Primary = Color.rgb(228, 154, 18)          // #E49A12
    val PrimaryDark = Color.rgb(184, 116, 0)       // #B87400
    val PrimarySoft = Color.rgb(255, 244, 224)     // #FFF4E0
    val Forest = Color.rgb(56, 66, 52)             // #384234
    val ForestDark = Color.rgb(40, 48, 40)         // #283028
    val ForestSoft = Color.rgb(239, 243, 236)       // #EFF3EC
    val TechnicalBlue = Color.rgb(43, 132, 198)    // #2B84C6
    val Background = Color.rgb(246, 245, 242)       // #F6F5F2
    val Surface = Color.WHITE
    val SurfaceWarm = Color.rgb(251, 248, 241)      // #FBF8F1
    val Text = Color.rgb(31, 36, 33)                // #1F2421
    val TextSecondary = Color.rgb(107, 115, 108)    // #6B736C
    val Border = Color.rgb(228, 225, 217)           // #E4E1D9
    val BorderStrong = Color.rgb(211, 205, 194)     // #D3CDC2
    val Success = Color.rgb(39, 122, 73)            // #277A49
    val SuccessSoft = Color.rgb(237, 248, 241)      // #EDF8F1
    val Warning = Color.rgb(184, 116, 0)            // #B87400
    val WarningSoft = Color.rgb(255, 244, 224)      // #FFF4E0
    val Danger = Color.rgb(180, 35, 24)             // #B42318
    val DangerSoft = Color.rgb(255, 240, 238)       // #FFF0EE
}

fun Context.brandRounded(
    fill: Int = Ge360Brand.Surface,
    stroke: Int = Ge360Brand.Border,
    radiusDp: Int = 18,
    strokeDp: Int = 1
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radiusDp).toFloat()
    setColor(fill)
    if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
}

fun MaterialCardView.applyGe360Card(
    fill: Int = Ge360Brand.Surface,
    stroke: Int = Ge360Brand.Border,
    radiusDp: Int = 18
) {
    radius = context.dp(radiusDp).toFloat()
    cardElevation = 0f
    strokeWidth = context.dp(1)
    strokeColor = stroke
    setCardBackgroundColor(fill)
}

fun MaterialButton.applyGe360Primary() {
    backgroundTintList = ColorStateList.valueOf(Ge360Brand.PrimaryDark)
    setTextColor(Color.WHITE)
    cornerRadius = context.dp(14)
    insetTop = 0
    insetBottom = 0
    isAllCaps = false
    setTypeface(typeface, Typeface.BOLD)
}

fun MaterialButton.applyGe360Secondary() {
    backgroundTintList = ColorStateList.valueOf(Ge360Brand.ForestSoft)
    strokeColor = ColorStateList.valueOf(Ge360Brand.BorderStrong)
    strokeWidth = context.dp(1)
    setTextColor(Ge360Brand.Forest)
    cornerRadius = context.dp(14)
    insetTop = 0
    insetBottom = 0
    isAllCaps = false
    setTypeface(typeface, Typeface.BOLD)
}

fun TextView.applyGe360Title(sizeSp: Float = 22f) {
    textSize = sizeSp
    setTextColor(Ge360Brand.ForestDark)
    setTypeface(typeface, Typeface.BOLD)
}

fun TextView.applyGe360Subtitle(sizeSp: Float = 13f) {
    textSize = sizeSp
    setTextColor(Ge360Brand.TextSecondary)
}

/** Piccola etichetta di sezione coerente in tutta l'app. */
fun Context.ge360Eyebrow(label: String): TextView = TextView(this).apply {
    text = label.uppercase()
    textSize = 11f
    letterSpacing = 0.08f
    setTextColor(Ge360Brand.PrimaryDark)
    setTypeface(typeface, Typeface.BOLD)
}

/** Riga decorativa sottile, usata negli header principali senza aggiungere dipendenze. */
fun Context.ge360AccentLine(widthDp: Int = 44): View = View(this).apply {
    background = brandRounded(fill = Ge360Brand.Primary, stroke = Ge360Brand.Primary, radiusDp = 3, strokeDp = 0)
    layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(4)).apply {
        topMargin = dp(7)
        bottomMargin = dp(4)
    }
}

/** Badge leggero per stato/contesto. */
fun Context.ge360Badge(
    textValue: String,
    backgroundColor: Int = Ge360Brand.PrimarySoft,
    textColor: Int = Ge360Brand.PrimaryDark
): TextView = TextView(this).apply {
    text = textValue
    textSize = 10.5f
    gravity = Gravity.CENTER
    setTextColor(textColor)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(10), dp(6), dp(10), dp(6))
    background = brandRounded(
        fill = backgroundColor,
        stroke = backgroundColor,
        radiusDp = 12,
        strokeDp = 0
    )
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
