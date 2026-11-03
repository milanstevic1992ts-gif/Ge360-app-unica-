package it.edilmilan.ge360.core.common.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.R

/**
 * I valori colore del tema attivo.
 *
 * Non va costruita a mano: [Ge360Brand] la risolve dalle risorse
 * (`values/ge360_palette.xml` e `values-night/ge360_palette.xml`).
 */
data class Ge360Palette(
    val primary: Int,
    val primaryDark: Int,
    val primarySoft: Int,
    val onPrimary: Int,
    val accent: Int,
    val forest: Int,
    val forestDark: Int,
    val forestSoft: Int,
    val technicalBlue: Int,
    val background: Int,
    val surface: Int,
    val surfaceWarm: Int,
    val text: Int,
    val textSecondary: Int,
    val border: Int,
    val borderStrong: Int,
    val success: Int,
    val successSoft: Int,
    val warning: Int,
    val warningSoft: Int,
    val danger: Int,
    val dangerSoft: Int,
    val inverse: Int,
    val onInverse: Int
) {
    companion object {
        /**
         * Palette chiara scritta a mano.
         *
         * Serve da rete di sicurezza: se [Ge360Brand.sync] non viene mai chiamata
         * l'app resta identica a come era prima del tema scuro, invece di
         * mostrare colori sbagliati.
         */
        val LIGHT_FALLBACK = Ge360Palette(
            primary = Color.rgb(228, 154, 18),
            primaryDark = Color.rgb(162, 102, 0),
            primarySoft = Color.rgb(255, 244, 224),
            onPrimary = Color.WHITE,
            accent = Color.rgb(154, 97, 0),
            forest = Color.rgb(56, 66, 52),
            forestDark = Color.rgb(40, 48, 40),
            forestSoft = Color.rgb(239, 243, 236),
            technicalBlue = Color.rgb(43, 132, 198),
            background = Color.rgb(246, 245, 242),
            surface = Color.WHITE,
            surfaceWarm = Color.rgb(251, 248, 241),
            text = Color.rgb(31, 36, 33),
            textSecondary = Color.rgb(103, 111, 104),
            border = Color.rgb(228, 225, 217),
            borderStrong = Color.rgb(211, 205, 194),
            success = Color.rgb(39, 122, 73),
            successSoft = Color.rgb(237, 248, 241),
            warning = Color.rgb(154, 97, 0),
            warningSoft = Color.rgb(255, 244, 224),
            danger = Color.rgb(180, 35, 24),
            dangerSoft = Color.rgb(255, 240, 238),
            inverse = Color.rgb(40, 48, 40),
            onInverse = Color.rgb(246, 245, 242)
        )

        internal fun resolve(context: Context): Ge360Palette {
            fun c(id: Int) = ContextCompat.getColor(context, id)
            return Ge360Palette(
                primary = c(R.color.ge360_primary),
                primaryDark = c(R.color.ge360_primary_dark),
                primarySoft = c(R.color.ge360_primary_soft),
                onPrimary = c(R.color.ge360_on_primary),
                accent = c(R.color.ge360_accent),
                forest = c(R.color.ge360_forest),
                forestDark = c(R.color.ge360_forest_dark),
                forestSoft = c(R.color.ge360_forest_soft),
                technicalBlue = c(R.color.ge360_technical_blue),
                background = c(R.color.ge360_background),
                surface = c(R.color.ge360_surface),
                surfaceWarm = c(R.color.ge360_surface_warm),
                text = c(R.color.ge360_text),
                textSecondary = c(R.color.ge360_text_secondary),
                border = c(R.color.ge360_border),
                borderStrong = c(R.color.ge360_border_strong),
                success = c(R.color.ge360_success),
                successSoft = c(R.color.ge360_success_soft),
                warning = c(R.color.ge360_warning),
                warningSoft = c(R.color.ge360_warning_soft),
                danger = c(R.color.ge360_danger),
                dangerSoft = c(R.color.ge360_danger_soft),
                inverse = c(R.color.ge360_inverse),
                onInverse = c(R.color.ge360_on_inverse)
            )
        }
    }
}

/**
 * Design system condiviso EDIL MILAN STEVIC / GE360.
 *
 * La palette riprende il marchio aziendale: arancione cantiere, verde scuro,
 * fondo caldo e blu tecnico. Tenerla qui evita colori diversi tra i moduli.
 *
 * I colori sono proprieta' calcolate, non costanti: cambiano da soli quando il
 * telefono passa da tema chiaro a scuro. Chi li legge non deve fare nulla, basta
 * che [sync] venga chiamata all'avvio (lo fa `Ge360Application`) e a ogni
 * creazione di Activity (lo fa [BaseGeActivity]).
 */
object Ge360Brand {

    @Volatile
    private var palette: Ge360Palette = Ge360Palette.LIGHT_FALLBACK

    @Volatile
    private var resolvedForNight: Boolean? = null

    /**
     * Allinea la palette al tema corrente del [context].
     *
     * Costa una lettura di risorse solo quando il tema e' effettivamente
     * cambiato, quindi puo' essere chiamata liberamente in `onCreate`.
     */
    fun sync(context: Context) {
        val night = context.isGe360NightMode()
        if (resolvedForNight == night) return
        runCatching { Ge360Palette.resolve(context) }
            .onSuccess {
                palette = it
                resolvedForNight = night
            }
    }

    /** La palette attiva, se serve passarla in giro come oggetto. */
    val colors: Ge360Palette get() = palette

    val Primary: Int get() = palette.primary
    val PrimaryDark: Int get() = palette.primaryDark
    val PrimarySoft: Int get() = palette.primarySoft

    /** Testo/icona sopra un riempimento [PrimaryDark]. */
    val OnPrimary: Int get() = palette.onPrimary

    /** Ocra da usare come TESTO o icona sopra [Surface]/[Background]. */
    val Accent: Int get() = palette.accent

    val Forest: Int get() = palette.forest
    val ForestDark: Int get() = palette.forestDark
    val ForestSoft: Int get() = palette.forestSoft
    val TechnicalBlue: Int get() = palette.technicalBlue
    val Background: Int get() = palette.background
    val Surface: Int get() = palette.surface
    val SurfaceWarm: Int get() = palette.surfaceWarm
    val Text: Int get() = palette.text
    val TextSecondary: Int get() = palette.textSecondary
    val Border: Int get() = palette.border
    val BorderStrong: Int get() = palette.borderStrong
    val Success: Int get() = palette.success
    val SuccessSoft: Int get() = palette.successSoft
    val Warning: Int get() = palette.warning
    val WarningSoft: Int get() = palette.warningSoft
    val Danger: Int get() = palette.danger
    val DangerSoft: Int get() = palette.dangerSoft

    /**
     * Riempimento di una card che spicca invertendo il fondo della pagina.
     *
     * In tema chiaro e' scura, in tema scuro e' chiara. Il testo sopra va in
     * [OnInverse], non in [OnPrimary]: quest'ultimo e' bianco fisso e su una
     * card chiara sparirebbe.
     */
    val Inverse: Int get() = palette.inverse

    /** Testo e icone sopra [Inverse]. */
    val OnInverse: Int get() = palette.onInverse
}

/** True quando il telefono sta usando il tema scuro. */
fun Context.isGe360NightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.brandRounded(
    fill: Int = Ge360Brand.Surface,
    stroke: Int = Ge360Brand.Border,
    radiusDp: Int = GeRadius.CARD,
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
    radiusDp: Int = GeRadius.CARD
) {
    radius = context.dp(radiusDp).toFloat()
    cardElevation = 0f
    strokeWidth = context.dp(1)
    strokeColor = stroke
    setCardBackgroundColor(fill)
}

fun MaterialButton.applyGe360Primary() {
    backgroundTintList = ColorStateList.valueOf(Ge360Brand.PrimaryDark)
    setTextColor(Ge360Brand.OnPrimary)
    strokeWidth = 0
    cornerRadius = context.dp(GeRadius.CONTROL)
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
    cornerRadius = context.dp(GeRadius.CONTROL)
    insetTop = 0
    insetBottom = 0
    isAllCaps = false
    setTypeface(typeface, Typeface.BOLD)
}

/** Bottone distruttivo: usato per eliminazioni, mai per azioni normali. */
fun MaterialButton.applyGe360Danger() {
    backgroundTintList = ColorStateList.valueOf(Ge360Brand.DangerSoft)
    strokeColor = ColorStateList.valueOf(Ge360Brand.Danger)
    strokeWidth = context.dp(1)
    setTextColor(Ge360Brand.Danger)
    cornerRadius = context.dp(GeRadius.CONTROL)
    insetTop = 0
    insetBottom = 0
    isAllCaps = false
    setTypeface(typeface, Typeface.BOLD)
}

fun TextView.applyGe360Title(sizeSp: Float = GeType.TITLE) {
    textSize = sizeSp
    setTextColor(Ge360Brand.ForestDark)
    setTypeface(typeface, Typeface.BOLD)
}

fun TextView.applyGe360Subtitle(sizeSp: Float = GeType.BODY_SM) {
    textSize = sizeSp
    setTextColor(Ge360Brand.TextSecondary)
}

/** Piccola etichetta di sezione coerente in tutta l'app. */
fun Context.ge360Eyebrow(label: String): TextView = TextView(this).apply {
    text = label.uppercase()
    textSize = GeType.EYEBROW
    letterSpacing = 0.08f
    setTextColor(Ge360Brand.Accent)
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
    textColor: Int = Ge360Brand.Accent
): TextView = TextView(this).apply {
    text = textValue
    textSize = GeType.MICRO
    gravity = Gravity.CENTER
    setTextColor(textColor)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(GeSpace.SM + 2), dp(GeSpace.XS + 2), dp(GeSpace.SM + 2), dp(GeSpace.XS + 2))
    background = brandRounded(
        fill = backgroundColor,
        stroke = backgroundColor,
        radiusDp = GeRadius.PILL,
        strokeDp = 0
    )
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
