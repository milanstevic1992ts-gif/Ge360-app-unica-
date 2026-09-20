package it.edilmilan.ge360.giornata

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.time.LocalDate
import java.util.Locale

class DailyWorkActivity : AppCompatActivity() {
    private lateinit var store: DailyStore
    private lateinit var jobsStore: JobsiteStore
    private lateinit var jobs: List<JobsiteRecord>
    private lateinit var jobsiteSpinner: Spinner
    private lateinit var collaboratorInput: EditText
    private lateinit var hourlyCostInput: EditText
    private lateinit var hourlyCostLabel: TextView
    private lateinit var timerLabel: TextView
    private lateinit var dayTotalLabel: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var doneInput: EditText
    private lateinit var nextInput: EditText
    private lateinit var issuesInput: EditText
    private lateinit var materialsInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var closedCheck: CheckBox
    private lateinit var historyContainer: LinearLayout
    private var rateCollaborator: String = "Milan"

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshTimer()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DailyStore(this)
        jobsStore = JobsiteStore(this)
        jobs = jobsStore.list().filter { it.status != JobsiteStore.STATUS_ARCHIVED }
        setContentView(buildScreen())
        bindSelection()
        restoreRunningSelection()
        if (store.runningSession() == null) syncRateCollaborator(forceReload = true)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        if (::hourlyCostInput.isInitialized && hourlyCostInput.isEnabled) saveCurrentHourlyRate()
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        store.close()
        jobsStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(18))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Giornata",
            subtitle = "Ore · costi reali · rapportino · materiale",
            onBack = { finish() }
        ))

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)

        body.addView(sectionTitle("OGGI · ${LocalDate.now()}"))
        jobsiteSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@DailyWorkActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Seleziona cantiere") + jobs.map { "${it.code} · ${it.displayTitle()}" }
            )
        }
        body.addView(jobsiteSpinner, full(dp(54)))

        collaboratorInput = EditText(this).apply {
            hint = "Chi lavora oggi"
            isSingleLine = true
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("collaborator", "Milan") ?: "Milan")
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && store.runningSession() == null) syncRateCollaborator(forceReload = false)
            }
        }
        body.addView(collaboratorInput, full(dp(54)))

        body.addView(MaterialButton(this).apply {
            text = "Tariffe collaboratori"
            setOnClickListener { showCollaboratorRates() }
        }, full(dp(46)))

        hourlyCostLabel = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), 0)
        }
        body.addView(hourlyCostLabel)

        hourlyCostInput = EditText(this).apply {
            hint = "Costo ora interno € · es. 25,00"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        body.addView(hourlyCostInput, full(dp(54)))
        body.addView(TextView(this).apply {
            text = "La tariffa viene fotografata all'avvio del timer: cambiarla in futuro non modifica il costo delle ore già lavorate. 0/vuoto = tariffa non configurata."
            textSize = 11f
            setPadding(dp(4), 0, dp(4), dp(7))
        })

        val timerCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(1).toFloat()
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val timerBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        timerLabel = TextView(this).apply {
            text = "00:00"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        timerBody.addView(timerLabel, full(ViewGroup.LayoutParams.WRAP_CONTENT))
        dayTotalLabel = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER }
        timerBody.addView(dayTotalLabel, full(ViewGroup.LayoutParams.WRAP_CONTENT))
        startStopButton = MaterialButton(this).apply {
            textSize = 17f
            minHeight = dp(58)
            setOnClickListener { toggleTimer() }
        }
        timerBody.addView(startStopButton, full(dp(62)))
        timerCard.addView(timerBody)
        body.addView(timerCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(14)
        })

        body.addView(sectionTitle("RAPPORTINO RAPIDO"))
        doneInput = reportField("Cosa ho fatto oggi")
        body.addView(doneInput, full(dp(96)))
        nextInput = reportField("Cosa faccio domani / prossimi passi")
        body.addView(nextInput, full(dp(96)))
        issuesInput = reportField("Problemi, imprevisti, decisioni da prendere")
        body.addView(issuesInput, full(dp(96)))
        materialsInput = reportField("Materiale / attrezzi da portare o comprare")
        body.addView(materialsInput, full(dp(96)))
        notesInput = reportField("Note libere")
        body.addView(notesInput, full(dp(86)))
        closedCheck = CheckBox(this).apply { text = "Giornata chiusa / rapportino completo" }
        body.addView(closedCheck)

        body.addView(MaterialButton(this).apply {
            text = "SALVA RAPPORTINO"
            minHeight = dp(56)
            setOnClickListener { saveReport(showToast = true) }
        }, full(dp(60)))
        body.addView(MaterialButton(this).apply {
            text = "Condividi rapportino"
            setOnClickListener { shareReport() }
        }, full(dp(52)))

        body.addView(sectionTitle("ULTIMI RAPPORTINI"))
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(historyContainer)

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun bindSelection() {
        jobsiteSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadReportForSelection()
                refreshTimer()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun restoreRunningSelection() {
        val running = store.runningSession() ?: return
        val index = jobs.indexOfFirst { it.ge360Id == running.jobsiteId }
        if (index >= 0) jobsiteSpinner.setSelection(index + 1)
        collaboratorInput.setText(running.collaborator)
        rateCollaborator = running.collaborator
        hourlyCostInput.setText(running.hourlyRate.takeIf { it > 0.0 }?.let { String.format(Locale.ITALY, "%.2f", it) }.orEmpty())
        hourlyCostLabel.text = "Tariffa fotografata per ${running.collaborator}: ${running.hourlyRate.euro()}/h"
    }

    private fun selectedJobsite(): JobsiteRecord? =
        if (jobsiteSpinner.selectedItemPosition <= 0) null else jobs.getOrNull(jobsiteSpinner.selectedItemPosition - 1)

    private fun currentCollaborator(): String = collaboratorInput.text.toString().trim().ifBlank { "Milan" }

    private fun syncRateCollaborator(forceReload: Boolean) {
        if (!::collaboratorInput.isInitialized || !::hourlyCostInput.isInitialized) return
        val current = currentCollaborator()
        if (!forceReload && current.equals(rateCollaborator, ignoreCase = true)) return
        if (!forceReload && rateCollaborator.isNotBlank() && hourlyCostInput.isEnabled) saveRate(rateCollaborator)
        rateCollaborator = current
        loadRate(current)
    }

    private fun loadRate(collaborator: String) {
        val rate = store.hourlyRate(collaborator)
        hourlyCostLabel.text = "Costo ora di $collaborator"
        hourlyCostInput.setText(rate.takeIf { it > 0.0 }?.let { String.format(Locale.ITALY, "%.2f", it) }.orEmpty())
    }

    private fun saveRate(collaborator: String) {
        val value = parseMoney(hourlyCostInput.text.toString())
        store.setHourlyRate(collaborator, value)
    }

    private fun saveCurrentHourlyRate() {
        syncRateCollaborator(forceReload = false)
        saveRate(rateCollaborator.ifBlank { currentCollaborator() })
    }

    private fun showCollaboratorRates() {
        if (hourlyCostInput.isEnabled) saveCurrentHourlyRate()
        val names = store.knownCollaborators().ifEmpty { listOf(currentCollaborator()) }
        val labels = names.map { name ->
            val rate = store.hourlyRate(name)
            if (rate > 0.0) "$name · ${rate.euro()}/h" else "$name · tariffa non configurata"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Tariffe collaboratori")
            .setItems(labels) { _, position ->
                if (store.runningSession() != null) return@setItems
                collaboratorInput.setText(names[position])
                rateCollaborator = names[position]
                loadRate(names[position])
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun toggleTimer() {
        val running = store.runningSession()
        if (running != null) {
            val stopped = store.stopSession(running.id) ?: running
            Toast.makeText(this, "Lavoro fermato · ${formatMinutes(stopped.durationMinutes())} · costo ${stopped.laborCost().euro()}", Toast.LENGTH_SHORT).show()
            hourlyCostInput.isEnabled = true
            syncRateCollaborator(forceReload = true)
        } else {
            collaboratorInput.clearFocus()
            syncRateCollaborator(forceReload = false)
            saveCurrentHourlyRate()
            val job = selectedJobsite()
            if (job == null) {
                Toast.makeText(this, "Seleziona prima il cantiere", Toast.LENGTH_SHORT).show()
                return
            }
            val collaborator = currentCollaborator()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("collaborator", collaborator).apply()
            val session = store.startSession(job, collaborator)
            Toast.makeText(
                this,
                if (session.hourlyRate > 0.0) "Timer avviato · $collaborator ${session.hourlyRate.euro()}/h" else "Timer avviato · $collaborator · tariffa non configurata",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshAll()
    }

    private fun refreshAll() {
        refreshTimer()
        refreshHistory()
        loadReportForSelection()
    }

    private fun refreshTimer() {
        val running = store.runningSession()
        val selected = selectedJobsite()
        if (running != null) {
            timerLabel.text = formatMinutes(running.durationMinutes())
            startStopButton.text = "■  FINE LAVORO"
            jobsiteSpinner.isEnabled = false
            collaboratorInput.isEnabled = false
            hourlyCostInput.isEnabled = false
            hourlyCostLabel.text = if (running.hourlyRate > 0.0) {
                "Tariffa sessione ${running.collaborator}: ${running.hourlyRate.euro()}/h · snapshot"
            } else {
                "Tariffa sessione ${running.collaborator}: NON CONFIGURATA"
            }
            val jobIndex = jobs.indexOfFirst { it.ge360Id == running.jobsiteId }
            if (jobIndex >= 0 && jobsiteSpinner.selectedItemPosition != jobIndex + 1) jobsiteSpinner.setSelection(jobIndex + 1)
        } else {
            timerLabel.text = "00:00"
            startStopButton.text = "▶  INIZIA LAVORO"
            jobsiteSpinner.isEnabled = true
            collaboratorInput.isEnabled = true
            hourlyCostInput.isEnabled = true
        }
        val today = LocalDate.now().toString()
        val sessions = store.sessions(today, selected?.ge360Id)
        val minutes = sessions.sumOf { it.durationMinutes() }
        val labor = sessions.sumOf { it.laborCost() }
        val missing = sessions.filter { it.hourlyRate <= 0.0 }.sumOf { it.durationMinutes() }
        val missingSuffix = if (missing > 0) " · ${formatMinutes(missing)} senza tariffa" else ""
        dayTotalLabel.text = if (selected == null) {
            "Totale oggi: ${formatMinutes(minutes)} · costo ${labor.euro()}$missingSuffix"
        } else {
            "${selected.code} · oggi ${formatMinutes(minutes)} · costo ${labor.euro()}$missingSuffix"
        }
    }

    private fun loadReportForSelection() {
        val job = selectedJobsite()
        if (job == null) {
            clearReportFields()
            return
        }
        val report = store.report(job.ge360Id)
        doneInput.setText(report?.doneToday.orEmpty())
        nextInput.setText(report?.nextSteps.orEmpty())
        issuesInput.setText(report?.issues.orEmpty())
        materialsInput.setText(report?.materialsNeeded.orEmpty())
        notesInput.setText(report?.notes.orEmpty())
        closedCheck.isChecked = report?.closed == true
    }

    private fun clearReportFields() {
        doneInput.setText("")
        nextInput.setText("")
        issuesInput.setText("")
        materialsInput.setText("")
        notesInput.setText("")
        closedCheck.isChecked = false
    }

    private fun saveReport(showToast: Boolean): DailyReport? {
        if (hourlyCostInput.isEnabled) saveCurrentHourlyRate()
        val job = selectedJobsite()
        if (job == null) {
            if (showToast) Toast.makeText(this, "Seleziona prima il cantiere", Toast.LENGTH_SHORT).show()
            return null
        }
        val current = store.report(job.ge360Id) ?: DailyReport(
            jobsiteId = job.ge360Id,
            jobsiteCode = job.code,
            jobsiteTitle = job.displayTitle()
        )
        current.jobsiteCode = job.code
        current.jobsiteTitle = job.displayTitle()
        current.doneToday = doneInput.text.toString().trim()
        current.nextSteps = nextInput.text.toString().trim()
        current.issues = issuesInput.text.toString().trim()
        current.materialsNeeded = materialsInput.text.toString().trim()
        current.notes = notesInput.text.toString().trim()
        current.closed = closedCheck.isChecked
        store.saveReport(current)
        refreshHistory()
        if (showToast) Toast.makeText(this, "Rapportino salvato", Toast.LENGTH_SHORT).show()
        return current
    }

    private fun shareReport() {
        val report = saveReport(showToast = false) ?: return
        val minutes = store.totalMinutes(report.workDate, report.jobsiteId)
        val text = buildString {
            appendLine("GE360 · Rapportino ${report.workDate}")
            appendLine("${report.jobsiteCode} · ${report.jobsiteTitle}")
            appendLine("Ore registrate: ${formatMinutes(minutes)}")
            if (report.doneToday.isNotBlank()) appendLine("\nFatto oggi:\n${report.doneToday}")
            if (report.nextSteps.isNotBlank()) appendLine("\nProssimi passi:\n${report.nextSteps}")
            if (report.issues.isNotBlank()) appendLine("\nProblemi / decisioni:\n${report.issues}")
            if (report.materialsNeeded.isNotBlank()) appendLine("\nMateriale / attrezzi:\n${report.materialsNeeded}")
            if (report.notes.isNotBlank()) appendLine("\nNote:\n${report.notes}")
        }.trim()
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Condividi rapportino"))
    }

    private fun refreshHistory() {
        historyContainer.removeAllViews()
        val reports = store.reports(8)
        if (reports.isEmpty()) {
            historyContainer.addView(TextView(this).apply {
                text = "Nessun rapportino ancora."
                setPadding(dp(4), dp(8), dp(4), dp(12))
            })
            return
        }
        reports.forEach { report ->
            val sessions = store.sessions(report.workDate, report.jobsiteId)
            val minutes = sessions.sumOf { it.durationMinutes() }
            val labor = sessions.sumOf { it.laborCost() }
            val missing = sessions.any { it.hourlyRate <= 0.0 && it.durationMinutes() > 0 }
            historyContainer.addView(MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                setContentPadding(dp(14), dp(10), dp(14), dp(10))
                addView(LinearLayout(this@DailyWorkActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@DailyWorkActivity).apply {
                        text = "${report.workDate} · ${report.jobsiteCode}"
                        setTypeface(typeface, Typeface.BOLD)
                        textSize = 15f
                    })
                    addView(TextView(this@DailyWorkActivity).apply {
                        text = listOf(
                            report.jobsiteTitle,
                            if (report.closed) "Chiuso" else "Da completare",
                            "Ore ${formatMinutes(minutes)}",
                            "Costo ${labor.euro()}",
                            if (missing) "tariffe mancanti" else ""
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        textSize = 12f
                    })
                    if (report.doneToday.isNotBlank()) addView(TextView(this@DailyWorkActivity).apply {
                        text = report.doneToday.replace('\n', ' ').take(150)
                        setPadding(0, dp(5), 0, 0)
                    })
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun reportField(hintText: String) = EditText(this).apply {
        hint = hintText
        gravity = Gravity.TOP or Gravity.START
        setPadding(dp(12), dp(10), dp(12), dp(10))
        minLines = 2
        maxLines = 5
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(12), dp(4), dp(6))
    }

    private fun parseMoney(raw: String): Double = raw.trim().replace("€", "").replace(" ", "").replace(',', '.')
        .filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

    private fun Double.euro(): String = String.format(Locale.ITALY, "€ %.2f", this)
    private fun formatMinutes(minutes: Long): String = "%02d:%02d".format(minutes / 60, minutes % 60)
    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val PREFS = "ge360_giornata" }
}
