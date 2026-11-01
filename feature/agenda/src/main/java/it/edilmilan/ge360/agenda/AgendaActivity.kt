package it.edilmilan.ge360.agenda

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Primo incremento: note, attivita' e appuntamenti locali.
 * Nessuna notifica o integrazione calendario esterno e' ancora attivata.
 */
class AgendaActivity : AppCompatActivity() {
    private val store by lazy { AgendaStore(applicationContext) }
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private var filter = "Oggi"
    private val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        refresh()
        if (savedInstanceState == null && intent.getStringExtra(EXTRA_CREATE_KIND) in
            setOf(AgendaEntry.NOTE, AgendaEntry.TASK, AgendaEntry.EVENT)) {
            edit(AgendaEntry(kind = intent.getStringExtra(EXTRA_CREATE_KIND)!!))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(18), dp(15), dp(10))
            setBackgroundColor(Ge360Brand.Background)
        }
        root.addView(TextView(this).apply {
            text = "‹  GE360 · ORGANIZZAZIONE"
            textSize = 12f
            setTextColor(Ge360Brand.PrimaryDark)
            setPadding(0, 0, 0, dp(12))
            setOnClickListener { finish() }
            contentDescription = "Torna indietro"
        })
        root.addView(TextView(this).apply {
            text = "Note e Agenda"
            textSize = 27f
            setTextColor(Ge360Brand.ForestDark)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Appunti liberi, cose da fare e appuntamenti, anche offline."
            textSize = 13f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(4), 0, dp(13))
        })

        val creation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "＋ Nota" to AgendaEntry.NOTE,
            "＋ Attivita'" to AgendaEntry.TASK,
            "＋ Appuntamento" to AgendaEntry.EVENT
        ).forEach { (title, kind) ->
            creation.addView(MaterialButton(this).apply {
                text = title
                textSize = 11f
                isAllCaps = false
                setOnClickListener { edit(AgendaEntry(kind = kind)) }
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(3) })
        }
        root.addView(creation)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Oggi", "Attivita'", "Note", "Agenda").forEach { mode ->
            tabs.addView(MaterialButton(this).apply {
                text = mode
                textSize = 11f
                isAllCaps = false
                setOnClickListener { filter = mode; refresh() }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        root.addView(tabs)

        search = EditText(this).apply {
            hint = "Cerca appunti, materiali, clienti..."
            isSingleLine = true
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(36))
        }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refresh() {
        if (!::list.isInitialized || !::search.isInitialized) return
        list.removeAllViews()
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val endOfToday = calendar.timeInMillis + 24 * 60 * 60 * 1000L
        val query = search.text.toString().trim()
        val items = runCatching { store.list() }.getOrElse {
            message("Archivio non leggibile. Nessun dato e' stato cancellato.")
            return
        }.filter { entry ->
            (query.isEmpty() || listOf(entry.title, entry.body).any { it.contains(query, true) }) &&
                when (filter) {
                    "Note" -> entry.kind == AgendaEntry.NOTE
                    "Attivita'" -> entry.kind == AgendaEntry.TASK
                    "Agenda" -> entry.kind == AgendaEntry.EVENT
                    else -> !entry.completed && entry.dueAt > 0 && entry.dueAt < endOfToday
                }
        }
        list.addView(TextView(this).apply {
            text = when (filter) {
                "Oggi" -> "IN SCADENZA · " + items.size
                "Attivita'" -> "ATTIVITA' · " + items.size
                "Note" -> "APPUNTI · " + items.size
                else -> "APPUNTAMENTI · " + items.size
            }
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
            setPadding(0, 0, 0, dp(8))
        })
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (filter == "Oggi") "Nessuna scadenza oggi. Gli appunti senza data restano nella scheda Note."
                       else "Ancora nessun elemento. Usa uno dei pulsanti + in alto."
                textSize = 14f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(dp(13), dp(18), dp(13), dp(18))
            })
        }
        items.forEach { entry ->
            list.addView(entryCard(entry, now), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) })
        }
    }

    private fun entryCard(entry: AgendaEntry, now: Long): View = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        setCardBackgroundColor(Ge360Brand.Surface)
        strokeColor = Ge360Brand.Border
        strokeWidth = dp(1)
        isClickable = true
        isFocusable = true
        setOnClickListener { actions(entry) }
        val inner = LinearLayout(this@AgendaActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        inner.addView(TextView(this@AgendaActivity).apply {
            text = entry.title + if (entry.completed) "  ✓" else ""
            textSize = 16f
            setTextColor(Ge360Brand.ForestDark)
            setTypeface(typeface, Typeface.BOLD)
        })
        if (entry.body.isNotBlank()) inner.addView(TextView(this@AgendaActivity).apply {
            text = entry.body
            maxLines = 3
            textSize = 13f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(3), 0, dp(7))
        })
        val meta = mutableListOf(
            when (entry.kind) {
                AgendaEntry.NOTE -> "Nota"
                AgendaEntry.TASK -> "Attivita'"
                else -> "Appuntamento"
            }
        )
        if (entry.dueAt > 0) meta.add(formatter.format(Date(entry.dueAt)))
        if (entry.dueAt in 1 until now && !entry.completed) meta.add("IN RITARDO")
        if (entry.priority == AgendaEntry.HIGH) meta.add("Priorita' alta")
        if (entry.scope == AgendaEntry.PRIVATE) meta.add("Privato")
        if (entry.clientGe360Id.isNotEmpty()) meta.add("Cliente collegato")
        if (entry.jobsiteGe360Id.isNotEmpty()) meta.add("Cantiere collegato")
        inner.addView(TextView(this@AgendaActivity).apply {
            text = meta.joinToString("  ·  ")
            textSize = 11f
            setTextColor(Ge360Brand.PrimaryDark)
        })
        addView(inner)
    }

    private fun actions(entry: AgendaEntry) {
        val options = mutableListOf("Modifica")
        if (entry.kind != AgendaEntry.NOTE) options += if (entry.completed) "Riapri" else "Completa"
        if (entry.kind == AgendaEntry.NOTE) options += "Trasforma in attivita'"
        options += "Elimina"
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(options.toTypedArray()) { _, index ->
                when (options[index]) {
                    "Modifica" -> edit(entry)
                    "Completa" -> runAction { store.setCompleted(entry.id, true) }
                    "Riapri" -> runAction { store.setCompleted(entry.id, false) }
                    "Trasforma in attivita'" -> runAction {
                        store.promoteNote(entry.id)
                        filter = "Attivita'"
                    }
                    "Elimina" -> AlertDialog.Builder(this)
                        .setTitle("Eliminare questo elemento?")
                        .setMessage("Questa operazione non si puo' annullare. La nota originale non viene eliminata convertendola in attivita'.")
                        .setNegativeButton("Annulla", null)
                        .setPositiveButton("Elimina") { _, _ -> runAction { store.delete(entry.id) } }
                        .show()
                }
            }.show()
    }

    private fun runAction(action: () -> Unit) {
        runCatching(action).onFailure { message(it.message ?: "Impossibile completare l'operazione") }
        refresh()
    }

    private fun edit(entry: AgendaEntry) {
        var dueAt = entry.dueAt
        var clientId = entry.clientGe360Id
        var jobsiteId = entry.jobsiteGe360Id
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(8), dp(17), dp(10))
        }
        val title = EditText(this).apply {
            hint = "Titolo o cosa ricordare"
            isSingleLine = true
            setText(entry.title)
        }
        form.addView(title)
        val body = EditText(this).apply {
            hint = "Dettagli, misure, cose da comprare, checklist..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
            setText(entry.body)
        }
        form.addView(body)
        val dateButton = MaterialButton(this).apply {
            text = if (dueAt == 0L) "＋ Imposta data e ora" else formatter.format(Date(dueAt))
            isAllCaps = false
        }
        dateButton.setOnClickListener {
            val c = Calendar.getInstance().apply { if (dueAt > 0) timeInMillis = dueAt }
            DatePickerDialog(this, { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, day, 9, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimePickerDialog(this, { _, hour, minute ->
                    selected.set(Calendar.HOUR_OF_DAY, hour)
                    selected.set(Calendar.MINUTE, minute)
                    dueAt = selected.timeInMillis
                    dateButton.text = formatter.format(Date(dueAt))
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }
        form.addView(dateButton)
        form.addView(MaterialButton(this).apply {
            text = "Rimuovi data"
            isAllCaps = false
            setOnClickListener { dueAt = 0; dateButton.text = "＋ Imposta data e ora" }
        })
        val high = CheckBox(this).apply { text = "Priorita' alta"; isChecked = entry.priority == AgendaEntry.HIGH }
        val private = CheckBox(this).apply { text = "Privato (non inviare al Data Hub)"; isChecked = entry.scope == AgendaEntry.PRIVATE }
        form.addView(high)
        form.addView(private)
        val clientButton = MaterialButton(this).apply { text = if (clientId.isEmpty()) "Collega cliente (facoltativo)" else "Cliente collegato ✓" }
        clientButton.setOnClickListener {
            runCatching {
                val clients = ClientStore(this)
                val options = try { clients.search().filter { it.ge360Id.isNotBlank() } } finally { clients.close() }
                val labels = arrayOf("Nessuno") + options.map { it.fullName() }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Collega cliente")
                    .setItems(labels) { _, idx ->
                        clientId = if (idx == 0) "" else options[idx - 1].ge360Id
                        clientButton.text = if (idx == 0) "Collega cliente (facoltativo)" else labels[idx]
                    }.show()
            }.onFailure { message("Clienti non disponibili") }
        }
        form.addView(clientButton)
        val jobButton = MaterialButton(this).apply { text = if (jobsiteId.isEmpty()) "Collega cantiere (facoltativo)" else "Cantiere collegato ✓" }
        jobButton.setOnClickListener {
            runCatching {
                val jobs = JobsiteStore(this)
                val options = try { jobs.list().filter { it.ge360Id.isNotBlank() } } finally { jobs.close() }
                val labels = arrayOf("Nessuno") + options.map { it.displayTitle() }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Collega cantiere")
                    .setItems(labels) { _, idx ->
                        jobsiteId = if (idx == 0) "" else options[idx - 1].ge360Id
                        jobButton.text = if (idx == 0) "Collega cantiere (facoltativo)" else labels[idx]
                    }.show()
            }.onFailure { message("Cantieri non disponibili") }
        }
        form.addView(jobButton)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (entry.title.isBlank()) "Nuovo elemento" else "Modifica elemento")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val textTitle = title.text.toString().trim().ifEmpty {
                    body.text.toString().trim().lineSequence().firstOrNull().orEmpty().take(80)
                }
                if (textTitle.isBlank()) { title.error = "Inserisci un testo"; return@setOnClickListener }
                if (entry.kind == AgendaEntry.EVENT && dueAt == 0L) {
                    message("Scegli data e ora dell'appuntamento")
                    return@setOnClickListener
                }
                runCatching {
                    store.save(entry.copy(
                        title = textTitle, body = body.text.toString().trim(),
                        dueAt = dueAt, priority = if (high.isChecked) AgendaEntry.HIGH else AgendaEntry.NORMAL,
                        scope = if (private.isChecked) AgendaEntry.PRIVATE else AgendaEntry.WORK,
                        clientGe360Id = clientId, jobsiteGe360Id = jobsiteId
                    ))
                }.onSuccess { dialog.dismiss(); refresh() }
                    .onFailure { message(it.message ?: "Salvataggio non riuscito") }
            }
        }
        dialog.show()
    }

    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    companion object {
        const val EXTRA_CREATE_KIND = "ge360.agenda.create_kind"
    }
}
