package it.edilmilan.ge360.agenda

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.attrezzi.WorkPreparationActivity
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.PersonnelAssignment
import it.edilmilan.ge360.giornata.PersonnelRecord
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Weekly field planner. Canonical assignments are stored ONLY by DailyStore. */
class WeeklySitePlannerActivity : AppCompatActivity() {
    private var monday = WeeklySitePlanner.mondayOf(LocalDate.now())
    private val contextJobsiteId by lazy {
        intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
    }
    private var snapshot: WeeklyPlannerSnapshot? = null
    private lateinit var items: LinearLayout
    private var requestNumber = 0
    private val shortDate = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ITALY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        requestNumber++
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(12))
            setBackgroundColor(Ge360Brand.Background)
        }
        root.addView(TextView(this).apply {
            text = "‹  GE360 · ORGANIZZAZIONE"
            textSize = 12f
            setTextColor(Ge360Brand.PrimaryDark)
            isClickable = true
            isFocusable = true
            setPadding(0, 0, 0, dp(13))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Settimana cantieri"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
        })
        root.addView(TextView(this).apply {
            text = "Squadra, appuntamenti, lavorazioni e materiali. Senza duplicare le informazioni."
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(4), 0, dp(10))
        })
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "‹" to { move(-1L) },
            "Questa settimana" to { monday = WeeklySitePlanner.mondayOf(LocalDate.now()); reload() },
            "›" to { move(1L) }
        ).forEach { (text, callback) ->
            nav.addView(MaterialButton(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 12f
                setOnClickListener { callback() }
            }, LinearLayout.LayoutParams(0, dp(47), if (text == "Questa settimana") 2f else 1f))
        }
        root.addView(nav)
        items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(ScrollView(this).apply {
            clipToPadding = false
            addView(items)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun move(weeks: Long) {
        monday = monday.plusWeeks(weeks)
        reload()
    }

    private fun reload() {
        val request = ++requestNumber
        val anchor = monday
        snapshot = null
        items.removeAllViews()
        items.addView(hint("Caricamento pianificazione e materiali…"))
        thread(name = "ge360-weekly-planner", isDaemon = true) {
            val result = runCatching { WeeklySitePlanner.load(applicationContext, anchor) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != requestNumber) return@runOnUiThread
                result.onSuccess { snapshot = it; render(it) }
                    .onFailure {
                        items.removeAllViews()
                        items.addView(hint("Pianificazione non leggibile. Non è stato modificato alcun dato."))
                    }
            }
        }
    }

    private fun render(model: WeeklyPlannerSnapshot) {
        items.removeAllViews()
        items.addView(header("DAL " + monday.format(shortDate) + " AL " +
            monday.plusDays(6).format(shortDate)))
        val warnings = model.warnings
        val plannedHours = model.days.flatMap { it.assignments }.sumOf { it.plannedMinutes } / 60.0
        items.addView(info(
            model.assignmentCount.toString() + " assegnazioni · " +
                String.format(Locale.ITALY, "%.1f", plannedHours) + " ore previste",
            model.appointmentCount.toString() + " appuntamenti · " +
                model.openIssues + " cantieri da verificare · " +
                model.conflicts.size + " sovrapposizioni"
        ))
        if (model.people.isEmpty()) items.addView(hint(
            "Nessun collaboratore attivo registrato. Aggiungilo nella sezione Collaboratori/Giornata."
        ))
        if (model.jobs.isEmpty()) items.addView(hint(
            "Nessun cantiere operativo. Le note e gli appuntamenti restano comunque accessibili."
        ))
        warnings.forEach { items.addView(hint("Attenzione: " + it)) }

        model.days.forEach { day -> renderDay(day, model) }

        items.addView(header("PREPARAZIONE CANTIERI"))
        val relevantJobs = model.jobs.sortedWith(
            compareByDescending<WeeklyJobHealth> { it.needsReview }
                .thenBy { it.job.displayTitle() }
        )
        relevantJobs.forEach { state ->
            val details = buildString {
                append(state.openWork).append(" lavorazioni aperte")
                if (state.blockedWork > 0) append(" · ").append(state.blockedWork).append(" bloccate")
                if (state.resources == null) append(" · materiali non disponibili")
                else {
                    append(" · ").append(state.resources.missing).append(" materiali mancanti")
                    append(" · ").append(state.resources.openRequests).append(" richieste aperte")
                    append(" · ").append(state.resources.toBring).append(" da preparare")
                }
            }
            items.addView(rowCard(
                state.job.displayTitle(),
                details,
                if (state.needsReview) 0xFFFFF2E9.toInt() else Ge360Brand.Surface,
                onOpen = { openMaterials(state.job) }
            ))
        }
        items.addView(hint(
            "Le mancanze materiali non hanno una data di consegna implicita. Apri Preparazione lavoro per aggiornare la fonte."
        ))
    }

    private fun renderDay(day: WeeklyPlannerDay, model: WeeklyPlannerSnapshot) {
        items.addView(header(day.date.format(shortDate).replaceFirstChar { it.uppercase() }))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(MaterialButton(this).apply {
            text = "＋ Assegna squadra"
            isAllCaps = false
            textSize = 11f
            isEnabled = model.people.isNotEmpty() && model.jobs.isNotEmpty()
            setOnClickListener { assignmentDialog(day.date, model) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(MaterialButton(this).apply {
            text = "＋ Sopralluogo"
            isAllCaps = false
            textSize = 11f
            setOnClickListener { createVisit(day.date) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        items.addView(actions)

        if (day.assignments.isEmpty() && day.agenda.isEmpty() && day.deadlines.isEmpty()) {
            items.addView(hint("Giornata libera: nessun impegno pianificato."))
        }
        day.conflicts.forEach { problem ->
            val person = model.people.firstOrNull { it.ge360Id == problem.personId }?.name
                ?: "Collaboratore"
            items.addView(hint(
                "⚠ " + person + ": " + (problem.plannedMinutes / 60.0) +
                    " ore su " + problem.differentJobs + " cantieri. Verifica le sovrapposizioni."
            ))
        }
        day.assignments.forEach { assignment ->
            items.addView(rowCard(
                assignment.personnelName + " · " + assignment.jobsiteTitle,
                String.format(Locale.ITALY, "%.1f h", assignment.plannedMinutes / 60.0) +
                    " previste · " + assignment.note.ifBlank { "tocca per modificare" },
                Ge360Brand.Surface, onOpen = {
                    assignmentDialog(day.date, model, assignment)
                }
            ))
        }
        day.agenda.sortedBy { it.at }.forEach { occurrence ->
            val entry = occurrence.entry
            items.addView(rowCard(
                if (entry.scope == AgendaEntry.PRIVATE) "Impegno privato" else entry.title,
                (if (entry.kind == AgendaEntry.EVENT) "Appuntamento" else "Attività") +
                    " · " + SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(occurrence.at)),
                Ge360Brand.Surface, onOpen = {
                    startActivity(Intent(this, AgendaActivity::class.java)
                        .putExtra(AgendaActivity.EXTRA_EDIT_ENTRY_ID, entry.id))
                }
            ))
        }
        day.deadlines.forEach { source ->
            items.addView(rowCard(source.title,
                source.subtitle + " · scadenza senza orario", Ge360Brand.Surface,
                onOpen = { openSource(source) }
            ))
        }
    }

    private fun assignmentDialog(date: LocalDate, model: WeeklyPlannerSnapshot, existing: PersonnelAssignment? = null) {
        val people = model.people.filter { it.ge360Id.isNotBlank() }
        val jobs = model.jobs.map { it.job }.filter { it.ge360Id.isNotBlank() }
        if (people.isEmpty() || jobs.isEmpty()) {
            toast("Servono almeno una persona attiva e un cantiere valido")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(hint(date.format(shortDate)))
        val personSpinner = Spinner(this)
        personSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            people.map { it.name })
        val jobSpinner = Spinner(this)
        jobSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            jobs.map { it.code + " · " + it.displayTitle() })
        val personIdx = people.indexOfFirst { it.ge360Id == existing?.personnelId }
        val defaultJob = existing?.jobsiteId ?: contextJobsiteId
        val jobIdx = jobs.indexOfFirst { it.ge360Id == defaultJob }
        if (personIdx >= 0) personSpinner.setSelection(personIdx)
        if (jobIdx >= 0) jobSpinner.setSelection(jobIdx)
        if (existing != null) {
            personSpinner.isEnabled = false
            jobSpinner.isEnabled = false
        }
        box.addView(hint("Collaboratore"))
        box.addView(personSpinner)
        box.addView(hint("Cantiere"))
        box.addView(jobSpinner)
        val hours = EditText(this).apply {
            hint = "Ore previste (es. 8 oppure 4,5)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (existing == null) "8" else String.format(Locale.ITALY, "%.2f", existing.plannedMinutes / 60.0))
        }
        box.addView(hours)
        val note = EditText(this).apply {
            hint = "Lavorazione o indicazione (facoltativo)"
            minLines = 2
            setText(existing?.note.orEmpty())
        }
        box.addView(note)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Assegna squadra" else "Modifica ore / istruzioni")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val person = people[personSpinner.selectedItemPosition.coerceIn(0, people.lastIndex)]
                val job = jobs[jobSpinner.selectedItemPosition.coerceIn(0, jobs.lastIndex)]
                val parsedHours = hours.text.toString().replace(',', '.').toDoubleOrNull()
                if (parsedHours == null || parsedHours !in 0.25..12.0) {
                    hours.error = "Inserisci fra 0,25 e 12 ore"
                    return@setOnClickListener
                }
                val minutes = (parsedHours * 60).roundToInt()
                val plan = (existing ?: PersonnelAssignment()).copy(
                    personnelId = person.ge360Id,
                    personnelName = person.name,
                    jobsiteId = job.ge360Id,
                    jobsiteCode = job.code,
                    jobsiteTitle = job.displayTitle(),
                    workDate = date.toString(),
                    plannedMinutes = minutes,
                    hourlyRate = person.hourlyRate,
                    note = note.text.toString().trim(),
                    status = PersonnelAssignment.STATUS_PLANNED
                )
                // Read fresh data, rather than relying on a possibly stale week snapshot.
                val store = DailyStore(applicationContext)
                val currentDay: List<PersonnelAssignment>
                val duplicate: PersonnelAssignment?
                try {
                    currentDay = store.assignments(date, date)
                    duplicate = store.assignmentFor(plan.personnelId, plan.jobsiteId, plan.workDate)
                } finally { store.close() }
                if (existing == null && duplicate != null) {
                    toast("Assegnazione già presente: apri quella riga per modificarla.")
                    return@setOnClickListener
                }
                val proposed = currentDay.filter { it.ge360Id != existing?.ge360Id } + plan
                val conflict = WeeklySitePlanner.findConflicts(proposed)
                    .firstOrNull { it.personId == person.ge360Id }
                val savePlan = {
                    val writer = DailyStore(applicationContext)
                    runCatching {
                        writer.saveAssignment(plan)
                    }.onSuccess {
                        dialog.dismiss()
                        reload()
                    }.onFailure { toast(it.message ?: "Salvataggio non riuscito") }
                    writer.close()
                }
                if (conflict != null) {
                    AlertDialog.Builder(this@WeeklySitePlannerActivity)
                        .setTitle("Possibile sovrapposizione")
                        .setMessage(person.name + " risulta pianificato per " +
                            String.format(Locale.ITALY, "%.1f", conflict.plannedMinutes / 60.0) +
                            " ore su " + conflict.differentJobs +
                            " cantieri nello stesso giorno. Confermi l'assegnazione?")
                        .setNegativeButton("Rivedi", null)
                        .setPositiveButton("Conferma comunque") { _, _ -> savePlan() }
                        .show()
                } else savePlan()
            }
        }
        dialog.show()
    }

    private fun createVisit(date: LocalDate) {
        val at = date.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        startActivity(Intent(this, AgendaActivity::class.java).apply {
            putExtra(AgendaActivity.EXTRA_CREATE_KIND, AgendaEntry.EVENT)
            putExtra(AgendaActivity.EXTRA_INITIAL_DUE_AT, at)
            if (contextJobsiteId.isNotBlank()) putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, contextJobsiteId)
        })
    }

    private fun openMaterials(job: JobsiteRecord) {
        startActivity(Intent(this, WorkPreparationActivity::class.java)
            .putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, job.ge360Id))
    }

    private fun openSource(source: AgendaSourceDeadline) {
        val destination = if (source.sourceType == AgendaSourceDeadline.CLIENT_FACT) {
            Intent().setClassName(packageName, "it.edilmilan.ge360.clienti.ClientsActivity")
                .putExtra(Ge360Contracts.EXTRA_CLIENT_ID, source.clientGe360Id)
        } else if (source.jobsiteGe360Id.isNotBlank()) {
            Intent(Ge360Contracts.ACTION_OPEN_JOBSITE).apply {
                setPackage(packageName)
                putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, source.jobsiteGe360Id)
            }
        } else {
            Intent().setClassName(packageName, "it.edilmilan.ge360.MainActivity")
        }
        if (destination.resolveActivity(packageManager) != null) startActivity(destination)
        else toast("Scheda originale non disponibile")
    }

    private fun rowCard(title: String, detail: String, background: Int, onOpen: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeColor = Ge360Brand.Border
            strokeWidth = dp(1)
            setCardBackgroundColor(background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpen() }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            addView(TextView(this@WeeklySitePlannerActivity).apply {
                text = title
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
            })
            addView(TextView(this@WeeklySitePlannerActivity).apply {
                text = detail
                textSize = 12f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(4), 0, 0)
            })
        })
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        return card
    }

    private fun header(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ge360Brand.PrimaryDark)
        setPadding(0, dp(12), 0, dp(7))
    }

    private fun info(title: String, detail: String): View = TextView(this).apply {
        text = title + "\n" + detail
        textSize = 13f
        setTextColor(Ge360Brand.ForestDark)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        setBackgroundColor(Ge360Brand.Surface)
    }

    private fun hint(label: String): View = TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(Ge360Brand.TextSecondary)
        setPadding(dp(10), dp(9), dp(10), dp(9))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
