package it.edilmilan.ge360.giornata

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Rapporto serale leggero: usa il DailyReport e il backup esistenti, senza creare un
 * secondo archivio o richiedere timer, tariffe, internet, PDF o servizi esterni.
 */
class EveningReportActivity : AppCompatActivity() {
    private lateinit var reportsStore: DailyStore
    private lateinit var jobsStore: JobsiteStore
    private lateinit var jobs: List<JobsiteRecord>
    private lateinit var jobSpinner: Spinner
    private lateinit var dateButton: MaterialButton
    private lateinit var locationLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var doneInput: EditText
    private lateinit var tomorrowInput: EditText
    private lateinit var issuesInput: EditText
    private lateinit var materialsInput: EditText
    private lateinit var notesInput: EditText
    private lateinit var optionalFields: LinearLayout
    private lateinit var historyContainer: LinearLayout

    private var selectedDate: LocalDate = LocalDate.now()
    private var loadedJobsiteId: String = ""
    private var loading = false
    private var dirty = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoSave = Runnable { saveDraft() }
    private val dateFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ITALY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportsStore = DailyStore(this)
        jobsStore = JobsiteStore(this)
        jobs = jobsStore.list().sortedWith(compareBy<JobsiteRecord> {
            it.status == JobsiteStore.STATUS_ARCHIVED
        }.thenBy { it.displayTitle() })

        selectedDate = savedInstanceState?.getString(STATE_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        setContentView(buildScreen())

        jobSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val nextId = selectedJob()?.ge360Id.orEmpty()
                if (loadedJobsiteId != nextId) {
                    saveDraft()
                    loadedJobsiteId = nextId
                    loadReport()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val preferredId = savedInstanceState?.getString(STATE_JOB)
            ?: intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID)
            ?: reportsStore.runningSession()?.jobsiteId
            ?: jobs.filter { it.status == JobsiteStore.STATUS_ACTIVE }
                .singleOrNull()?.ge360Id
            ?: ""
        val index = jobs.indexOfFirst { it.ge360Id == preferredId }
        if (index >= 0) jobSpinner.setSelection(index + 1)
        loadedJobsiteId = selectedJob()?.ge360Id.orEmpty()
        loadReport()
        refreshHistory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_DATE, selectedDate.toString())
        outState.putString(STATE_JOB, loadedJobsiteId)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        saveDraft()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoSave)
        reportsStore.close()
        jobsStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ge360Brand.Background)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        root.addView(buildScreenHeaderWithSubtitle(
            context = this,
            title = "Rapportino serale",
            subtitle = "Due minuti per ricordare i lavori fatti",
            onBack = { finish() }
        ))

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(7), 0, dp(20))
        }
        scroll.addView(body)

        body.addView(sectionTitle("QUANDO E DOVE"))
        dateButton = MaterialButton(this).apply {
            text = "📅 Oggi"
            setOnClickListener { chooseDate() }
        }
        body.addView(dateButton, full(dp(50)))

        jobSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@EveningReportActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Seleziona il cantiere") + jobs.map { "${it.code} · ${it.displayTitle()}" }
            )
        }
        body.addView(jobSpinner, full(dp(54)))

        locationLabel = TextView(this).apply {
            textSize = 12f
            setPadding(dp(4), dp(5), dp(4), dp(8))
            setTextColor(Ge360Brand.TextSecondary)
        }
        body.addView(locationLabel)

        body.addView(sectionTitle("COSA ABBIAMO FATTO OGGI?"))
        doneInput = multiLine("Es. demolito vecchie piastrelle del bagno, chiuso le tracce e preparato il fondo...", 5)
        body.addView(doneInput, full(dp(156)))

        body.addView(sectionTitle("COSA FACCIAMO DOMANI?"))
        tomorrowInput = multiLine("Es. rasatura pareti, verifica impianto, posa...", 3)
        body.addView(tomorrowInput, full(dp(92)))

        val moreButton = MaterialButton(this).apply {
            text = "＋ Problemi, materiali e altre note (facoltativo)"
        }
        body.addView(moreButton, full(dp(48)))
        optionalFields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        optionalFields.addView(sectionTitle("PROBLEMI / IMPREVISTI"))
        issuesInput = multiLine("Solo se c'è qualcosa da segnalare", 2)
        optionalFields.addView(issuesInput, full(dp(86)))
        optionalFields.addView(sectionTitle("MATERIALE E ATTREZZI PER DOMANI"))
        materialsInput = multiLine("Cosa manca o va ricordato", 2)
        optionalFields.addView(materialsInput, full(dp(86)))
        optionalFields.addView(sectionTitle("ALTRE NOTE"))
        notesInput = multiLine("Appunti aggiuntivi", 2)
        optionalFields.addView(notesInput, full(dp(86)))
        optionalFields.visibility = View.GONE
        body.addView(optionalFields)
        moreButton.setOnClickListener {
            optionalFields.visibility = if (optionalFields.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            moreButton.text = if (optionalFields.visibility == View.VISIBLE) "− Nascondi dettagli" else "＋ Problemi, materiali e altre note (facoltativo)"
        }

        listOf(doneInput, tomorrowInput, issuesInput, materialsInput, notesInput).forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (loading) return
                    dirty = true
                    statusLabel.text = "Modifiche in corso · salvataggio bozza automatico"
                    mainHandler.removeCallbacks(autoSave)
                    mainHandler.postDelayed(autoSave, 900L)
                }
            })
        }

        statusLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(5), dp(11), dp(5), dp(7))
        }
        body.addView(statusLabel)

        body.addView(MaterialButton(this).apply {
            text = "✓ SALVA RAPPORTINO"
            textSize = 16f
            minHeight = dp(58)
            setOnClickListener { saveFinished() }
        }, full(dp(62)))
        body.addView(MaterialButton(this).apply {
            text = "Condividi via WhatsApp o altre app"
            setOnClickListener { shareReport() }
        }, full(dp(50)))

        body.addView(sectionTitle("RAPPORTINI RECENTI · TOCCA PER RIAPRIRE"))
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(historyContainer)

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun selectedJob(): JobsiteRecord? =
        jobs.getOrNull(jobSpinner.selectedItemPosition - 1)

    private fun chooseDate() {
        val date = selectedDate
        val dialog = DatePickerDialog(this, { _, year, month, day ->
            val next = LocalDate.of(year, month + 1, day)
            if (next != selectedDate) {
                saveDraft()
                selectedDate = next
                loadReport()
            }
        }, date.year, date.monthValue - 1, date.dayOfMonth)
        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.show()
    }

    private fun loadReport() {
        mainHandler.removeCallbacks(autoSave)
        loading = true
        try {
            dateButton.text = "📅 ${selectedDate.format(dateFormat).replaceFirstChar { it.uppercase() }}"
            val job = selectedJob()
            locationLabel.text = if (job == null) {
                "Scegli il cantiere: il rapporto sarà archiviato lì, con questa data."
            } else {
                listOf(job.displayTitle(), job.address, "Ore registrate: ${formatMinutes(reportsStore.totalMinutes(selectedDate.toString(), job.ge360Id))}")
                    .filter { it.isNotBlank() }.joinToString(" · ")
            }
            val report = job?.let { reportsStore.report(it.ge360Id, selectedDate.toString()) }
            doneInput.setText(report?.doneToday.orEmpty())
            tomorrowInput.setText(report?.nextSteps.orEmpty())
            issuesInput.setText(report?.issues.orEmpty())
            materialsInput.setText(report?.materialsNeeded.orEmpty())
            notesInput.setText(report?.notes.orEmpty())
            val hasDetails = listOf(report?.issues, report?.materialsNeeded, report?.notes)
                .any { !it.isNullOrBlank() }
            optionalFields.visibility = if (hasDetails) View.VISIBLE else View.GONE
            statusLabel.text = when {
                job == null -> "Seleziona un cantiere per iniziare."
                report == null -> "Nessun rapporto per questo giorno · scrivi e salva."
                report.closed -> "✓ Rapportino salvato · puoi modificarlo quando vuoi."
                else -> "Bozza esistente · completa e premi Salva rapportino."
            }
            listOf(doneInput, tomorrowInput, issuesInput, materialsInput, notesInput)
                .forEach { it.isEnabled = job != null }
            dirty = false
        } finally {
            loading = false
        }
    }

    /**
     * La bozza si salva anche quando cambi cantiere/data o esci dall'app.
     * Non creiamo rapportini vuoti e un rapporto completato viene riaperto
     * solo dopo una modifica vera.
     */
    private fun saveDraft() {
        mainHandler.removeCallbacks(autoSave)
        if (!dirty || loadedJobsiteId.isBlank()) return
        val job = jobs.firstOrNull { it.ge360Id == loadedJobsiteId } ?: return
        val saved = storeReport(job, selectedDate, finalize = false)
        if (saved) {
            statusLabel.text = "✓ Bozza salvata automaticamente"
            refreshHistory()
        }
    }

    private fun storeReport(job: JobsiteRecord, date: LocalDate, finalize: Boolean): Boolean {
        val old = reportsStore.report(job.ge360Id, date.toString())
        val done = doneInput.text.toString().trim()
        val tomorrow = tomorrowInput.text.toString().trim()
        val issues = issuesInput.text.toString().trim()
        val materials = materialsInput.text.toString().trim()
        val notes = notesInput.text.toString().trim()
        if (old == null && listOf(done, tomorrow, issues, materials, notes).all { it.isEmpty() }) return false
        val report = old ?: DailyReport(jobsiteId = job.ge360Id, workDate = date.toString())
        report.jobsiteCode = job.code
        report.jobsiteTitle = job.displayTitle()
        report.doneToday = done
        report.nextSteps = tomorrow
        report.issues = issues
        report.materialsNeeded = materials
        report.notes = notes
        report.closed = finalize || (!dirty && old?.closed == true)
        return runCatching { reportsStore.saveReport(report) }
            .onFailure { Toast.makeText(this, "Errore nel salvataggio: ${it.message}", Toast.LENGTH_LONG).show() }
            .isSuccess.also { if (it) dirty = false }
    }

    private fun saveFinished(): Boolean {
        val job = selectedJob()
        if (job == null) {
            Toast.makeText(this, "Scegli prima il cantiere", Toast.LENGTH_SHORT).show()
            return false
        }
        if (doneInput.text.toString().isBlank()) {
            doneInput.error = "Scrivi almeno i lavori svolti oggi"
            doneInput.requestFocus()
            return false
        }
        mainHandler.removeCallbacks(autoSave)
        if (!storeReport(job, selectedDate, finalize = true)) return false
        statusLabel.text = "✓ Rapportino completato e salvato nello storico del cantiere"
        refreshHistory()
        Toast.makeText(this, "Rapportino salvato", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun shareReport() {
        if (!saveFinished()) return
        val job = selectedJob() ?: return
        val report = reportsStore.report(job.ge360Id, selectedDate.toString()) ?: return
        val text = buildString {
            appendLine("GE360 · RAPPORTINO DI CANTIERE")
            appendLine("Data: ${selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}")
            appendLine("Cantiere: ${job.code} · ${job.displayTitle()}")
            if (job.address.isNotBlank()) appendLine("Dove: ${job.address}")
            appendLine("Lavori svolti: ${report.doneToday}")
            if (report.nextSteps.isNotBlank()) appendLine("Domani: ${report.nextSteps}")
            if (report.issues.isNotBlank()) appendLine("Problemi: ${report.issues}")
            if (report.materialsNeeded.isNotBlank()) appendLine("Materiale / attrezzi: ${report.materialsNeeded}")
            if (report.notes.isNotBlank()) appendLine("Note: ${report.notes}")
            appendLine("Ore da timer: ${formatMinutes(reportsStore.totalMinutes(report.workDate, report.jobsiteId))}")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Condividi rapportino"))
    }

    private fun refreshHistory() {
        historyContainer.removeAllViews()
        val rows = reportsStore.reports(100).take(8)
        if (rows.isEmpty()) {
            historyContainer.addView(TextView(this).apply { text = "Ancora nessun rapportino." })
            return
        }
        rows.forEach { report ->
            val row = MaterialCardView(this).apply {
                applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 14)
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@EveningReportActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(TextView(this@EveningReportActivity).apply {
                        text = "${report.workDate} · ${report.jobsiteCode} · ${if (report.closed) "Completato" else "Bozza"}"
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(this@EveningReportActivity).apply {
                        text = report.doneToday.ifBlank { "Rapportino da completare" }.replace('\n', ' ').take(140)
                        textSize = 12f
                        setPadding(0, dp(5), 0, 0)
                    })
                })
                setOnClickListener {
                    val index = jobs.indexOfFirst { it.ge360Id == report.jobsiteId }
                    if (index < 0) return@setOnClickListener
                    saveDraft()
                    selectedDate = runCatching { LocalDate.parse(report.workDate) }.getOrDefault(LocalDate.now())
                    jobSpinner.setSelection(index + 1)
                    loadedJobsiteId = report.jobsiteId
                    loadReport()
                }
            }
            historyContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun multiLine(hint: String, minLines: Int) = EditText(this).apply {
        this.hint = hint
        gravity = Gravity.TOP or Gravity.START
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(12), dp(12), dp(12), dp(12))
        this.minLines = minLines
        maxLines = 8
        textSize = 15f
    }

    private fun sectionTitle(title: String) = TextView(this).apply {
        text = title
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ge360Brand.ForestDark)
        setPadding(dp(4), dp(12), dp(4), dp(7))
    }

    private fun formatMinutes(minutes: Long): String = "${minutes / 60}h ${minutes % 60}m"
    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_DATE = "evening_date"
        private const val STATE_JOB = "evening_job_id"
    }
}
