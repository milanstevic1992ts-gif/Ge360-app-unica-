package it.edilmilan.ge360.agenda

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.clienti.ClientsActivity
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.giornata.DailyWorkActivity
import it.edilmilan.ge360.giornata.DailyStore
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A daily operational lens on existing owners, not a second task database.
 * Original client facts, jobsite milestones, Home reminders and workflows are
 * read-only; only AgendaStore entries may be completed here.
 */
class OperationalDayActivity : AppCompatActivity() {
    private val store by lazy { AgendaStore(applicationContext) }
    private lateinit var contentArea: LinearLayout
    private var sourceState = AgendaSourceResult(emptyList(), emptyList())
    private var sourceLoading = false
    private var generation = 0
    private var mode = "Oggi"
    private var agendaRows: List<AgendaEntry> = emptyList()
    private var agendaError: String? = null
    private var workPulse = "Ore e squadra: dati non ancora disponibili"
    private val timeFormat = SimpleDateFormat("dd/MM · HH:mm", Locale.ITALY)
    private val dayFormat = SimpleDateFormat("dd/MM", Locale.ITALY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(screen())
        refreshLocal()
    }

    override fun onResume() {
        super.onResume()
        refreshLocal()
        reloadWorkPulse()
        reloadSources()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }

    private fun screen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ge360Brand.Background)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(42))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "‹  GE360 · CENTRO OPERATIVO"
            textSize = 12f
            setTextColor(Ge360Brand.PrimaryDark)
            contentDescription = "Torna alla schermata precedente"
            isClickable = true
            isFocusable = true
            setPadding(0, 0, 0, dp(15))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "La mia giornata"
            textSize = 27f
            setTextColor(Ge360Brand.ForestDark)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Impegni, cantieri e cose da ricordare. Ogni dato resta nel suo modulo."
            textSize = 13f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(4), 0, dp(12))
        })
        root.addView(buttonRow(listOf(
            "＋ Nota" to { create(AgendaEntry.NOTE) },
            "＋ Attività" to { create(AgendaEntry.TASK) },
            "＋ Appuntamento" to { create(AgendaEntry.EVENT) }
        )))
        root.addView(buttonRow(listOf(
            "Ore / squadra" to { startActivity(Intent(this, DailyWorkActivity::class.java)) },
            "Apri Agenda" to { startActivity(Intent(this, AgendaActivity::class.java)) }
        )))
        root.addView(buttonRow(listOf(
            "Settimana cantieri" to { startActivity(Intent(this, WeeklySitePlannerActivity::class.java)) }
        )))
        root.addView(buttonRow(listOf(
            "Oggi" to { mode = "Oggi"; render() },
            "7 giorni" to { mode = "7 giorni"; render() },
            "Tutto" to { mode = "Tutto"; render() }
        )))

        contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(contentArea)
        return scroll
    }

    private fun buttonRow(actions: List<Pair<String, () -> Unit>>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            actions.forEach { (label, action) ->
                addView(MaterialButton(this@OperationalDayActivity).apply {
                    text = label
                    textSize = 11f
                    isAllCaps = false
                    minHeight = dp(48)
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(3) })
            }
        }

    private fun reloadSources() {
        val request = ++generation
        sourceLoading = true
        render()
        thread(name = "ge360-operational-day-projection", isDaemon = true) {
            val result = runCatching { AgendaSourceProjection.read(applicationContext) }
                .getOrElse { AgendaSourceResult(emptyList(), listOf("Fonti operative non leggibili")) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                sourceState = result
                sourceLoading = false
                render()
            }
        }
    }

    private fun reloadWorkPulse() {
        workPulse = runCatching {
            val daily = DailyStore(applicationContext)
            try {
                val today = LocalDate.now()
                val timers = buildList {
                    daily.runningSession()?.let { add(it.ge360Id) }
                    addAll(daily.runningPersonnelSessions().map { it.ge360Id })
                }.distinct().size
                val planned = daily.assignments(today, today).size
                val reports = daily.reports(1000).count { it.workDate == today.toString() }
                timers.toString() + " timer attivi · " + planned +
                    " assegnazioni squadra · " + reports + " rapportini oggi"
            } finally { daily.close() }
        }.getOrElse { "Ore e squadra: apri il registro per verificare i dati" }
        if (::contentArea.isInitialized) render()
    }

    private fun refreshLocal() {
        runCatching { store.list() }.onSuccess {
            agendaRows = it
            agendaError = null
        }.onFailure {
            agendaRows = emptyList()
            agendaError = "Archivio Agenda non leggibile: nessun dato è stato cancellato."
        }
        if (::contentArea.isInitialized) render()
    }

    private fun render() {
        if (!::contentArea.isInitialized) return
        contentArea.removeAllViews()
        val snapshot = AgendaDayPlan.build(agendaRows, sourceState.deadlines)
        val today = snapshot.dueNow
        val urgent = (snapshot.overdue + snapshot.today).count { it.urgent }
        contentArea.addView(sectionTitle("ORE E SQUADRA", workPulse))
        contentArea.addView(sectionTitle(
            "RIEPILOGO",
            today.toString() + " impegni in scadenza o scaduti · " +
                urgent + " prioritari · " + snapshot.unplanned.size + " senza data"
        ))
        if (sourceLoading) contentArea.addView(hint("Sto leggendo le scadenze dei moduli…"))
        if (agendaError != null) contentArea.addView(hint(agendaError.orEmpty()))
        sourceState.warnings.forEach { warning -> contentArea.addView(hint(warning)) }

        group("DA RECUPERARE", snapshot.overdue, "#FFF1E9", if (mode == "Tutto") 100 else 12)
        group("OGGI", snapshot.today, "#EEF5FF", if (mode == "Tutto") 100 else 12)
        if (mode != "Oggi") group("PROSSIMI SETTE GIORNI", snapshot.upcoming, "#F1F8F2", if (mode == "Tutto") 100 else 20)
        if (mode == "Tutto") group("PIÙ AVANTI", snapshot.later, "#F6F5F1", 100)
        group("DA PIANIFICARE", snapshot.unplanned, "#F6F5F1", if (mode == "Tutto") 100 else 6)

        if (snapshot.total == 0 && !sourceLoading && agendaError == null && sourceState.warnings.isEmpty()) {
            contentArea.addView(hint("Nessun impegno aperto. Puoi annotare qualcosa con + Nota oppure creare un appuntamento."))
        }
        if (mode != "Tutto") contentArea.addView(MaterialButton(this).apply {
            text = "Vedi anche gli impegni futuri e senza data"
            isAllCaps = false
            setOnClickListener { mode = "Tutto"; render() }
        })
        val recentNotes = agendaRows.filter { it.kind == AgendaEntry.NOTE }
            .sortedByDescending { it.updatedAt }.take(3)
        if (recentNotes.isNotEmpty()) {
            contentArea.addView(sectionTitle("APPUNTI RECENTI", "Le note restano indipendenti dalle attività."))
            recentNotes.forEach { note ->
                contentArea.addView(card(
                    title = if (note.scope == AgendaEntry.PRIVATE) "Nota privata" else note.title,
                    subtitle = "Apri o modifica l'appunto",
                    shade = "#F6F5F1",
                    onOpen = { openAgendaEntry(note.id) }
                ))
            }
        }
    }

    private fun group(title: String, rows: List<AgendaDayItem>, background: String, limit: Int) {
        if (rows.isEmpty()) return
        contentArea.addView(sectionTitle(title + " · " + rows.size, when (title) {
            "DA RECUPERARE" -> "Appuntamenti scaduti e azioni non ancora chiuse"
            "OGGI" -> "Azioni previste per la giornata"
            "DA PIANIFICARE" -> "Attività senza scadenza, non automaticamente urgenti"
            else -> "Impegni programmati"
        }))
        rows.take(limit).forEach { item ->
            contentArea.addView(card(
                title = if (item.entry?.scope == AgendaEntry.PRIVATE) "Impegno privato" else item.title,
                subtitle = buildString {
                    if (item.urgent) append("Priorità alta · ")
                    if (item.at > 0) {
                        append((if (item.dateOnly) dayFormat else timeFormat).format(Date(item.at)))
                        append(if (item.dateOnly) " · data senza ora · " else " · ")
                    }
                    else append("Senza data · ")
                    append(item.description)
                    append(if (item.entry != null) " · Modificabile" else " · Fonte originale")
                },
                shade = background,
                onOpen = {
                    if (item.entry != null) openAgendaEntry(item.entry.id)
                    else item.source?.let(::openSource)
                },
                onDone = item.entry?.let { entry -> { complete(entry) } }
            ))
        }
        if (rows.size > limit) contentArea.addView(hint(
            "Altri " + (rows.size - limit) + " elementi non mostrati. Apri il modulo originale o restringi il periodo."
        ))
    }

    private fun card(
        title: String, subtitle: String, shade: String,
        onOpen: () -> Unit, onDone: (() -> Unit)? = null
    ): View {
        val panel = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            strokeWidth = dp(1)
            strokeColor = Ge360Brand.Border
            setCardBackgroundColor(android.graphics.Color.parseColor(shade))
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpen() }
        }
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(9), dp(10))
            addView(LinearLayout(this@OperationalDayActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@OperationalDayActivity).apply {
                    text = title
                    textSize = 14f
                    setTextColor(Ge360Brand.ForestDark)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@OperationalDayActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (onDone != null) addView(MaterialButton(this@OperationalDayActivity).apply {
                text = "Fatto"
                textSize = 11f
                isAllCaps = false
                setOnClickListener { onDone() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        })
        return panel.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
        }
    }

    private fun complete(entry: AgendaEntry) {
        val execute = {
            runCatching { store.setCompleted(entry.id, true) }
                .onFailure { toast(it.message ?: "Impossibile completare l'attività") }
            refreshLocal()
        }
        if (entry.repeat != AgendaEntry.REPEAT_NONE) {
            AlertDialog.Builder(this)
                .setTitle("Concludere questa ricorrenza?")
                .setMessage("GE360 riprogrammerà la successiva, mantenendo la stessa attività.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Concludi") { _, _ -> execute() }
                .show()
        } else execute()
    }

    private fun create(kind: String) {
        startActivity(Intent(this, AgendaActivity::class.java)
            .putExtra(AgendaActivity.EXTRA_CREATE_KIND, kind))
    }

    private fun openAgendaEntry(uuid: String) {
        startActivity(Intent(this, AgendaActivity::class.java)
            .putExtra(AgendaActivity.EXTRA_EDIT_ENTRY_ID, uuid))
    }

    private fun openSource(source: AgendaSourceDeadline) {
        val intent = when {
            source.sourceType == AgendaSourceDeadline.CLIENT_FACT ->
                Intent(this, ClientsActivity::class.java)
                    .putExtra(Ge360Contracts.EXTRA_CLIENT_ID, source.clientGe360Id)
            source.jobsiteGe360Id.isNotBlank() ->
                Intent(Ge360Contracts.ACTION_OPEN_JOBSITE).apply {
                    setPackage(packageName)
                    putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, source.jobsiteGe360Id)
                }
            else -> Intent().setClassName(packageName, "it.edilmilan.ge360.MainActivity")
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else toast("Scheda di origine non disponibile in questa versione")
    }

    private fun sectionTitle(title: String, subtitle: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(13), 0, dp(7))
            addView(TextView(this@OperationalDayActivity).apply {
                text = title
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.PrimaryDark)
            })
            addView(TextView(this@OperationalDayActivity).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(2), 0, 0)
            })
        }

    private fun hint(value: String): View = TextView(this).apply {
        text = value
        textSize = 12.5f
        setTextColor(Ge360Brand.TextSecondary)
        setPadding(dp(10), dp(12), dp(10), dp(12))
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
