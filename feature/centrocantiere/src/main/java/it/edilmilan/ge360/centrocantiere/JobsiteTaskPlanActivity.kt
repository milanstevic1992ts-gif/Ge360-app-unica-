package it.edilmilan.ge360.centrocantiere

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Operator-defined duration + finish-to-start precedence for canonical checklist.
 * Does not update task statuses or contractual deadlines.
 */
class JobsiteTaskPlanActivity : AppCompatActivity() {
    private lateinit var workflows: JobsiteWorkflowStore
    private lateinit var sites: JobsiteStore
    private lateinit var job: JobsiteRecord
    private lateinit var body: LinearLayout
    private var selectedTaskId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workflows = JobsiteWorkflowStore(applicationContext)
        sites = JobsiteStore(applicationContext)
        val id = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val requested = sites.getByGe360Id(id)
        if (requested == null) {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        job = requested
        selectedTaskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        setContentView(screen())
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) render()
    }

    override fun onDestroy() {
        if (::workflows.isInitialized) workflows.close()
        if (::sites.isInitialized) sites.close()
        super.onDestroy()
    }

    private fun screen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7F9"))
            setPadding(dp(14), dp(11), dp(14), dp(10))
        }
        root.addView(button("‹  Torna al cantiere") { finish() })
        root.addView(label("Piano lavorazioni", 25f, "#17191C", true))
        root.addView(label(job.displayTitle(), 13f, "#626A71"))
        root.addView(label(
            "Inserisci giorni di lavoro e attività da completare prima. " +
                "GE360 usa la checklist originale, senza duplicare le lavorazioni.",
            12f, "#626A71"
        ).apply { setPadding(0, dp(6), 0, dp(7)) })
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(5), 0, dp(25))
        }
        root.addView(ScrollView(this).apply {
            clipToPadding = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        body.removeAllViews()
        val result = runCatching {
            val tasks = workflows.tasks(job.ge360Id)
            val plans = workflows.taskPlans(job.ge360Id)
            Triple(tasks, plans, TaskScheduleEngine.project(
                tasks, plans, LocalDate.now(), job.targetEndDate
            ))
        }
        if (result.isFailure) {
            body.addView(label(
                "Piano non leggibile. Nessun dato è stato modificato. " +
                    "Apri la checklist originale e riprova.",
                13f, "#A83D25"
            ))
            return
        }
        val (tasks, plans, report) = result.getOrThrow()
        body.addView(card().apply {
            addView(label(
                report.scheduled.toString() + "/" + report.active +
                    " attività con date teoriche",
                18f, "#17191C", true
            ))
            addView(label(
                "Durate inserite: " + report.totalPlannedWorkdays +
                    " giorni-lavorazione (non equivale ai giorni di cantiere)",
                12f, "#626A71"
            ))
            if (report.possibleFinish != null) {
                body.addView(label("", 1f, "#626A71"))
                addView(label(
                    "Fine teorica del piano: " + report.possibleFinish,
                    16f, "#237B47", true
                ))
                if (report.targetEnd != null) {
                    val diff = ChronoUnit.DAYS.between(report.targetEnd, report.possibleFinish)
                    val explanation = when {
                        diff > 0 -> "+" + diff + " giorni di calendario dopo la fine prevista"
                        diff < 0 -> (-diff).toString() + " giorni prima della fine prevista"
                        else -> "Coincide con la fine prevista"
                    }
                    addView(label(explanation, 12f, "#626A71"))
                }
            } else {
                addView(label(
                    "Fine non calcolabile finché restano durate o dipendenze irrisolte.",
                    12f, "#A85A00"
                ))
            }
            addView(label(
                "Simulazione lun–ven: attività indipendenti possono sovrapporsi; " +
                    "non sono assegnate squadre, attrezzi o festività.",
                11f, "#626A71"
            ))
        })
        report.problems.forEach { issue ->
            body.addView(label("• " + issue, 12f, "#8A5322").apply {
                setPadding(dp(5), dp(4), dp(5), dp(4))
            })
        }
        if (tasks.isEmpty()) {
            body.addView(card().apply {
                addView(label("Nessuna lavorazione nella checklist.", 14f, "#626A71"))
                addView(label(
                    "Apri Cronologia / lavori dal Centro Cantiere e aggiungi prima le attività.",
                    12f, "#626A71"
                ))
            })
            return
        }
        body.addView(label("DURATE E PRECEDENZE", 12f, "#626A71", true).apply {
            setPadding(dp(3), dp(18), 0, dp(7))
        })
        val byTask = tasks.associateBy { it.ge360Id }
        val byPlan = plans.associateBy { it.taskGe360Id }
        report.lines.forEach { item ->
            val plan = byPlan[item.task.ge360Id]
            body.addView(card().apply {
                addView(label(item.task.title, 16f, "#17191C", true))
                addView(label(
                    item.task.status + " · " +
                        (plan?.plannedWorkdays?.takeIf { it > 0 }?.let { it.toString() + " gg lavorativi" }
                            ?: "Durata non impostata"),
                    12f, "#626A71"
                ))
                if (!plan?.predecessorIds.isNullOrEmpty()) {
                    addView(label(
                        "Dopo: " + plan!!.predecessorIds.joinToString(", ") {
                            byTask[it]?.title ?: "Attività eliminata"
                        },
                        12f, "#626A71"
                    ))
                }
                if (item.start != null && item.finish != null) {
                    addView(label(
                        "Dal " + item.start + " al " + item.finish + " · ipotesi",
                        13f, "#277A49", true
                    ))
                } else {
                    addView(label(item.reason, 12f,
                        if (item.state == TaskScheduleLine.State.DONE ||
                            item.state == TaskScheduleLine.State.NOT_NEEDED) "#626A71" else "#A85A00"
                    ))
                }
                if (!plan?.note.isNullOrBlank()) addView(label(plan!!.note, 11f, "#626A71"))
                if (item.task.status != WorkflowStatuses.DONE &&
                    item.task.status != WorkflowStatuses.NOT_NEEDED) {
                    addView(button(if (plan == null) "＋ Pianifica lavorazione" else "Modifica piano") {
                        edit(item.task, tasks, plans)
                    })
                }
            })
        }
        if (selectedTaskId.isNotBlank()) {
            val chosen = tasks.firstOrNull { it.ge360Id == selectedTaskId }
            selectedTaskId = ""
            if (chosen != null && chosen.status != WorkflowStatuses.DONE &&
                chosen.status != WorkflowStatuses.NOT_NEEDED) {
                edit(chosen, tasks, plans)
            }
        }
    }

    private fun edit(task: WorkflowTask, tasks: List<WorkflowTask>, plans: List<WorkflowTaskPlan>) {
        val existing = plans.firstOrNull { it.taskGe360Id == task.ge360Id }
        val days = EditText(this).apply {
            hint = "Durata in giorni lavorativi (es. 2)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing?.plannedWorkdays?.takeIf { it > 0 }?.toString().orEmpty())
        }
        val note = EditText(this).apply {
            hint = "Nota sul programma (facoltativa)"
            setText(existing?.note.orEmpty())
        }
        val selected = existing?.predecessorIds.orEmpty().toMutableSet()
        val options = tasks.filter { it.ge360Id != task.ge360Id }
        val dependencyButton = button(
            "Predecessori: " + selected.size + " selezionati"
        ) {}
        fun refreshDependencyText() {
            dependencyButton.text = "Predecessori: " + selected.size + " selezionati"
        }
        dependencyButton.setOnClickListener {
            val checked = BooleanArray(options.size) { options[it].ge360Id in selected }
            AlertDialog.Builder(this)
                .setTitle("Attività da terminare prima")
                .setMultiChoiceItems(options.map { it.title + " · " + it.status }.toTypedArray(),
                    checked) { _, which, isChecked ->
                    if (isChecked) selected += options[which].ge360Id
                    else selected -= options[which].ge360Id
                }
                .setPositiveButton("Conferma") { _, _ -> refreshDependencyText() }
                .setNegativeButton("Annulla") { _, _ ->
                    selected.clear()
                    selected += existing?.predecessorIds.orEmpty()
                    refreshDependencyText()
                }
                .show()
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
            addView(label(
                "Durata ipotizzata per questa singola lavorazione, non ore di squadra.",
                12f, "#626A71"
            ))
            addView(days)
            addView(dependencyButton)
            addView(note)
            addView(label(
                "L'ordine fra attività nasce soltanto dalle dipendenze selezionate. " +
                    "L'assenza di predecessori consente sovrapposizioni teoriche.",
                11f, "#626A71"
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(task.title)
            .setView(form)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva", null)
            .apply {
                if (existing != null) setNeutralButton("Rimuovi piano") { _, _ ->
                    runCatching { workflows.removeTaskPlan(task.ge360Id) }
                        .onSuccess { render() }
                        .onFailure { toast(it.message ?: "Piano non rimosso") }
                }
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val duration = days.text.toString().trim().toIntOrNull() ?: 0
                val now = System.currentTimeMillis()
                val candidate = WorkflowTaskPlan(
                    taskGe360Id = task.ge360Id,
                    jobsiteId = task.jobsiteId,
                    plannedWorkdays = duration,
                    predecessorIds = options.map { it.ge360Id }.filter { it in selected },
                    note = note.text.toString(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
                runCatching { workflows.saveTaskPlan(candidate) }
                    .onSuccess { dialog.dismiss(); render() }
                    .onFailure { toast(it.message ?: "Piano non salvato") }
            }
        }
        dialog.show()
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#DDE1E5")
        setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(9) }
        addView(LinearLayout(this@JobsiteTaskPlanActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
        })
    }

    private fun label(value: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(value: String, click: () -> Unit) =
        MaterialButton(this).apply {
            text = value
            isAllCaps = false
            textSize = 12f
            minHeight = dp(48)
            setOnClickListener { click() }
        }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_TASK_ID = "ge360_schedule_task_id"
    }
}
