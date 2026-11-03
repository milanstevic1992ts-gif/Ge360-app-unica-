package it.edilmilan.ge360.core.common.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Componenti condivisi delle schermate GE360.
 *
 * Regola: se una schermata ha bisogno di una card, una riga, un campo o uno
 * stato vuoto, lo prende da qui. Se quello che serve non c'e', si aggiunge qui
 * una volta per tutte, non dentro la schermata.
 *
 * Sono tutte funzioni che costruiscono viste native, senza XML: la stessa scelta
 * del resto dell'app, cosi' la migrazione e' un copia-incolla e non una
 * riscrittura.
 */

// ---------------------------------------------------------------------------
// Contenitori di schermata
// ---------------------------------------------------------------------------

/**
 * Colonna radice di una schermata, con margini laterali e fondo coerenti.
 *
 * Sostituisce il `LinearLayout` con `setPadding(dp(14), dp(12), dp(14), dp(10))`
 * ricopiato in ogni Activity con valori sempre un po' diversi.
 */
fun Context.geScreen(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(Ge360Brand.Background)
    setPadding(dp(GeSpace.LG), dp(GeSpace.MD), dp(GeSpace.LG), dp(GeSpace.SM))
}

/** Etichetta maiuscoletta che apre una sezione. */
fun Context.geSectionLabel(label: String): TextView = ge360Eyebrow(label).apply {
    setPadding(dp(GeSpace.XS), dp(GeSpace.MD), dp(GeSpace.XS), dp(GeSpace.XS))
}

/** Card di sezione con titolo opzionale e contenuto libero. */
fun Context.geSectionCard(
    title: String? = null,
    subtitle: String? = null,
    fill: Int = Ge360Brand.Surface,
    content: (LinearLayout) -> Unit = {}
): MaterialCardView {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(GeSpace.LG), dp(GeSpace.MD), dp(GeSpace.LG), dp(GeSpace.MD))
    }
    if (!title.isNullOrBlank()) {
        body.addView(TextView(this).apply {
            text = title
            applyGe360Title(GeType.H2)
        })
    }
    if (!subtitle.isNullOrBlank()) {
        body.addView(TextView(this).apply {
            text = subtitle
            applyGe360Subtitle(GeType.CAPTION)
            setPadding(0, dp(GeSpace.XS), 0, 0)
        })
    }
    content(body)
    return MaterialCardView(this).apply {
        applyGe360Card(fill = fill)
        addView(body)
    }
}

/** Riga sottile di separazione. Da usare con parsimonia: le card bastano quasi sempre. */
fun Context.geDivider(): View = View(this).apply {
    setBackgroundColor(Ge360Brand.Border)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(GeSpace.SM)
        bottomMargin = dp(GeSpace.SM)
    }
}

// ---------------------------------------------------------------------------
// Riquadri numerici (dashboard)
// ---------------------------------------------------------------------------

/**
 * Riquadro con un numero grande, usato nelle dashboard.
 *
 * [onValue] riceve le due TextView (valore e dettaglio) per aggiornarle dopo,
 * senza ricostruire la card a ogni refresh.
 */
fun Context.geStatTile(
    label: String,
    accent: Int = Ge360Brand.Accent,
    onClick: (() -> Unit)? = null,
    onValue: (value: TextView, detail: TextView) -> Unit
): MaterialCardView {
    val valueView = TextView(this).apply {
        textSize = GeType.DISPLAY
        setTextColor(Ge360Brand.ForestDark)
        setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }
    val detailView = TextView(this).apply {
        applyGe360Subtitle(GeType.CAPTION)
        setPadding(0, dp(GeSpace.XS), 0, 0)
    }
    onValue(valueView, detailView)

    return MaterialCardView(this).apply {
        applyGe360Card()
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        addView(LinearLayout(this@geStatTile).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(GeSpace.MD), dp(GeSpace.MD), dp(GeSpace.MD), dp(GeSpace.MD))
            addView(TextView(this@geStatTile).apply {
                text = label.uppercase()
                textSize = GeType.MICRO
                letterSpacing = 0.06f
                setTextColor(accent)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(GeSpace.XS))
            })
            addView(valueView)
            addView(detailView)
        })
    }
}

/** Riga orizzontale di riquadri numerici a larghezza uguale. */
fun Context.geStatRow(vararg tiles: View): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    tiles.forEachIndexed { index, tile ->
        addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = dp(GeSpace.SM)
        })
    }
}

// ---------------------------------------------------------------------------
// Filtri
// ---------------------------------------------------------------------------

/**
 * Una riga di filtri a pillola, scorrevole in orizzontale.
 *
 * E' essa stessa una View: si aggiunge al layout direttamente, senza passare da
 * una proprieta' intermedia.
 *
 * Prima lo stato attivo era indicato aggiungendo "✓ " davanti al testo del
 * bottone: funzionava ma spostava il testo e non si vedeva da lontano. Qui il
 * filtro attivo e' pieno, gli altri sono in secondario.
 */
class GeFilterRow internal constructor(context: Context) : HorizontalScrollView(context) {

    private val buttons = linkedMapOf<String, MaterialButton>()

    internal fun register(value: String, button: MaterialButton) {
        buttons[value] = button
    }

    /** Evidenzia il filtro attivo. */
    fun select(value: String) {
        buttons.forEach { (option, button) ->
            if (option == value) button.applyGe360Primary() else button.applyGe360Secondary()
        }
    }
}

/** Costruisce una [GeFilterRow] con le opzioni date. */
fun Context.geFilterRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
): GeFilterRow {
    val strip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(GeSpace.XS), 0, dp(GeSpace.XS), 0)
    }
    val row = GeFilterRow(this).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(GeTouch.MINIMUM)
        ).apply { topMargin = dp(GeSpace.SM) }
    }
    options.forEach { option ->
        val button = MaterialButton(this).apply {
            text = option
            textSize = GeType.CAPTION
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(GeSpace.LG), 0, dp(GeSpace.LG), 0)
            cornerRadius = dp(GeRadius.PILL)
            setOnClickListener {
                // La riga si aggiorna da sola: chi la usa non deve ricordarsi
                // di richiamare select() dentro la callback.
                row.select(option)
                onSelect(option)
            }
        }
        row.register(option, button)
        strip.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(GeTouch.MINIMUM - 8)).apply {
                marginEnd = dp(GeSpace.SM)
            }
        )
    }
    row.addView(strip)
    row.select(selected)
    return row
}

// ---------------------------------------------------------------------------
// Campi di input
// ---------------------------------------------------------------------------

/** Etichetta sopra un campo di un form. */
fun Context.geFieldLabel(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = GeType.CAPTION
    setTextColor(Ge360Brand.TextSecondary)
    setTypeface(typeface, Typeface.BOLD)
    setPadding(dp(GeSpace.XS), dp(GeSpace.MD), dp(GeSpace.XS), dp(GeSpace.XS))
}

/**
 * Campo di testo con il bordo del design system.
 *
 * Gli EditText di sistema prendono il colore di accento dal tema e su fondo
 * scuro diventavano quasi invisibili: qui fondo, bordo e colore del testo
 * arrivano dalla palette.
 */
fun Context.geInput(
    hint: String = "",
    singleLine: Boolean = true,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
    initial: String = ""
): EditText = EditText(this).apply {
    this.hint = hint
    isSingleLine = singleLine
    this.inputType = inputType
    setText(initial)
    textSize = GeType.BODY
    setTextColor(Ge360Brand.Text)
    setHintTextColor(Ge360Brand.TextSecondary)
    background = brandRounded(
        fill = Ge360Brand.Surface,
        stroke = Ge360Brand.BorderStrong,
        radiusDp = GeRadius.CONTROL
    )
    setPadding(dp(GeSpace.MD), dp(GeSpace.SM), dp(GeSpace.MD), dp(GeSpace.SM))
    if (!singleLine) gravity = Gravity.TOP
}

/** Campo di ricerca, con la lente come prefisso testuale. */
fun Context.geSearchInput(hint: String): EditText = geInput(hint = hint).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(GeTouch.MINIMUM)
    ).apply { topMargin = dp(GeSpace.SM) }
}

// ---------------------------------------------------------------------------
// Stati della schermata
// ---------------------------------------------------------------------------

/**
 * Stato vuoto / errore.
 *
 * Prima una lista vuota era semplicemente vuota, senza dire se non c'erano dati
 * o se il caricamento era andato male.
 */
fun Context.geEmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setPadding(dp(GeSpace.XXL), dp(GeSpace.XXL), dp(GeSpace.XXL), dp(GeSpace.XXL))

    addView(TextView(this@geEmptyState).apply {
        this.text = title
        applyGe360Title(GeType.H2)
        gravity = Gravity.CENTER
    })
    addView(TextView(this@geEmptyState).apply {
        this.text = message
        applyGe360Subtitle(GeType.BODY_SM)
        gravity = Gravity.CENTER
        setPadding(0, dp(GeSpace.SM), 0, 0)
    })
    if (actionLabel != null && onAction != null) {
        addView(MaterialButton(this@geEmptyState).apply {
            this.text = actionLabel
            applyGe360Primary()
            setOnClickListener { onAction() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(GeTouch.MINIMUM)
            ).apply { topMargin = dp(GeSpace.XL) }
        })
    }
}

/**
 * Contenitore che alterna lista, stato vuoto e messaggio di errore.
 *
 * Tiene le tre viste sovrapposte e mostra quella giusta, cosi' la schermata non
 * deve aggiungere e togliere viste dal layout a ogni refresh.
 */
class GeListContainer(context: Context) : FrameLayout(context) {

    val list: RecyclerView = RecyclerView(context).apply {
        clipToPadding = false
        setPadding(0, context.dp(GeSpace.XS), 0, context.dp(GeSpace.XXL))
    }

    private var emptyView: View? = null

    init {
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Mostra la lista. */
    fun showList() {
        list.visibility = View.VISIBLE
        emptyView?.visibility = View.GONE
    }

    /** Sostituisce la lista con un messaggio. */
    fun showMessage(view: View) {
        emptyView?.let { removeView(it) }
        emptyView = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        list.visibility = View.GONE
        view.visibility = View.VISIBLE
    }
}

/** Barra di azioni in fondo alla schermata, bersagli comodi anche con i guanti. */
fun Context.geActionBar(vararg actions: MaterialButton): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    setPadding(0, dp(GeSpace.SM), 0, 0)
    actions.forEachIndexed { index, action ->
        addView(action, LinearLayout.LayoutParams(0, dp(GeTouch.COMFORTABLE), 1f).apply {
            if (index > 0) marginStart = dp(GeSpace.SM)
        })
    }
}

/** Bottone primario pronto all'uso. */
fun Context.gePrimaryButton(label: String, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        applyGe360Primary()
        setOnClickListener { onClick() }
    }

/** Bottone secondario pronto all'uso. */
fun Context.geSecondaryButton(label: String, onClick: () -> Unit): MaterialButton =
    MaterialButton(this).apply {
        text = label
        applyGe360Secondary()
        setOnClickListener { onClick() }
    }
