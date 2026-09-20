package it.edilmilan.ge360.attrezzi

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
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
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class VanModeActivity : AppCompatActivity() {
    private lateinit var tools: ToolsStore
    private lateinit var jobsStore: JobsiteStore
    private lateinit var planStore: VanPreparationStore
    private lateinit var jobs: List<JobsiteRecord>
    private lateinit var jobSpinner: Spinner
    private lateinit var summary: TextView
    private lateinit var container: LinearLayout
    private lateinit var planDateLabel: TextView
    private lateinit var planNote: EditText
    private lateinit var planStatus: TextView
    private var planDate: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tools = ToolsStore(this)
        jobsStore = JobsiteStore(this)
        planStore = VanPreparationStore(this)
        jobs = jobsStore.list().filter { it.status !in setOf(JobsiteStore.STATUS_ARCHIVED, JobsiteStore.STATUS_COMPLETED) }
        planDate = intent.getStringExtra(EXTRA_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()

        setContentView(buildScreen())

        intent.getStringExtra(EXTRA_JOB_ID)?.let { requestedId ->
            val index = jobs.indexOfFirst { it.ge360Id == requestedId }
            if (index >= 0) jobSpinner.setSelection(index + 1)
        }
        refresh()
        loadPlanForSelection()
    }

    override fun onResume() {
        super.onResume()
        if (::container.isInitialized) {
            refresh()
            loadPlanForSelection()
        }
    }

    override fun onDestroy() {
        tools.close()
        jobsStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        setBackgroundColor(Color.parseColor("#F6F7F9"))

        addView(buildScreenHeaderWithSubtitle(
            context = this@VanModeActivity,
            title = "🚚 Checklist cantiere",
            subtitle = "Spunta ciò che prendi: PRESO ✓ e la voce sparisce.",
            onBack = { finish() }
        ))

        jobSpinner = Spinner(this@VanModeActivity).apply {
            adapter = ArrayAdapter(
                this@VanModeActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Seleziona cantiere") + jobs.map { "${it.code} · ${it.displayTitle()}" }
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    refresh()
                    if (::planNote.isInitialized) loadPlanForSelection()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        addView(jobSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        addView(planningCard())

        summary = TextView(this@VanModeActivity).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#454A50"))
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        addView(summary)

        addView(MaterialButton(this@VanModeActivity).apply {
            text = "📤 CONDIVIDI LISTA"
            minHeight = dp(50)
            setOnClickListener { shareCurrentJob() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            bottomMargin = dp(8)
        })

        val scroll = ScrollView(this@VanModeActivity)
        container = LinearLayout(this@VanModeActivity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun planningCard() = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E2E4E7")
        setCardBackgroundColor(Color.WHITE)
        setContentPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }

        addView(LinearLayout(this@VanModeActivity).apply {
            orientation = LinearLayout.VERTICAL

            addView(TextView(this@VanModeActivity).apply {
                text = "📅 Quando prepararla"
                textSize = 17f
                setTextColor(Color.parseColor("#15171A"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@VanModeActivity).apply {
                text = "Puoi lasciarla in Dashboard per oggi, domani o una data scelta."
                textSize = 12f
                setTextColor(Color.parseColor("#6D7278"))
                setPadding(0, dp(3), 0, dp(9))
            })

            addView(LinearLayout(this@VanModeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(dateButton("OGGI") { setPlanDate(LocalDate.now()) })
                addView(dateButton("DOMANI") { setPlanDate(LocalDate.now().plusDays(1)) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                })
                addView(dateButton("CALENDARIO") { openCalendar() })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

            planDateLabel = TextView(this@VanModeActivity).apply {
                textSize = 14f
                setTextColor(Color.parseColor("#B85700"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(9), dp(2), dp(5))
            }
            addView(planDateLabel)

            planNote = EditText(this@VanModeActivity).apply {
                hint = "Nota facoltativa"
                minLines = 2
                maxLines = 4
                gravity = Gravity.TOP
                setTextColor(Color.parseColor("#15171A"))
                setHintTextColor(Color.parseColor("#868B91"))
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            addView(planNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))

            addView(MaterialButton(this@VanModeActivity).apply {
                text = "SALVA IN DASHBOARD"
                minHeight = dp(50)
                setOnClickListener { savePlan() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(7)
            })

            planStatus = TextView(this@VanModeActivity).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#6D7278"))
                setPadding(dp(2), dp(5), dp(2), 0)
            }
            addView(planStatus)
        })
    }

    private fun dateButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 11f
        minHeight = dp(44)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
    }

    private fun selectedJob(): JobsiteRecord? = jobs.getOrNull(jobSpinner.selectedItemPosition - 1)

    private fun shareCurrentJob() {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        JobResourceShare.show(this, job, tools.jobResources(job.ge360Id))
    }

    private fun setPlanDate(date: LocalDate) {
        planDate = date
        loadPlanForSelection()
    }

    private fun openCalendar() {
        DatePickerDialog(
            this,
            { _, year, month, day -> setPlanDate(LocalDate.of(year, month + 1, day)) },
            planDate.year,
            planDate.monthValue - 1,
            planDate.dayOfMonth
        ).show()
    }

    private fun loadPlanForSelection() {
        if (!::planDateLabel.isInitialized) return
        planDateLabel.text = "Data: ${humanDate(planDate)}"
        val job = selectedJob()
        if (job == null) {
            planNote.setText("")
            planStatus.text = "Seleziona prima un cantiere."
            return
        }

        val existing = planStore.find(job.ge360Id, planDate.toString())
        if (existing == null) {
            planNote.setText("")
            planStatus.text = "Nessuna checklist salvata per questa data."
        } else {
            if (planNote.text.toString() != existing.note) planNote.setText(existing.note)
            planStatus.text = "✓ In Dashboard · ${existing.toolCount} attrezzi · ${existing.materialCount} materiali"
        }
    }

    private fun savePlan() {
        val job = selectedJob() ?: return toast("Scegli prima un cantiere")
        val rows = tools.jobResources(job.ge360Id)
        val existing = planStore.find(job.ge360Id, planDate.toString())
        val plan = VanPreparationPlan(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            jobsiteId = job.ge360Id,
            jobsiteCode = job.code,
            jobsiteTitle = job.displayTitle(),
            date = planDate.toString(),
            note = planNote.text.toString().trim(),
            toolCount = rows.count { it.kind == ResourceKind.TOOL },
            materialCount = rows.count { it.kind == ResourceKind.MATERIAL },
            missingCount = 0,
            completed = rows.isEmpty()
        )
        planStore.save(plan)
        loadPlanForSelection()
        toast("Checklist ${job.code} salvata per ${humanDate(planDate)}")
    }

    private fun syncCurrentPlanCounts() {
        val job = selectedJob() ?: return
        val existing = planStore.find(job.ge360Id, planDate.toString()) ?: return
        val rows = tools.jobResources(job.ge360Id)
        planStore.save(
            existing.copy(
                toolCount = rows.count { it.kind == ResourceKind.TOOL },
                materialCount = rows.count { it.kind == ResourceKind.MATERIAL },
                missingCount = 0,
                completed = rows.isEmpty()
            )
        )
        loadPlanForSelection()
    }

    private fun refresh() {
        if (!::container.isInitialized) return
        container.removeAllViews()
        val job = selectedJob()
        if (job == null) {
            summary.text = "Scegli il cantiere da preparare."
            container.addView(emptyText("Qui compariranno gli attrezzi e i materiali da prendere."))
            return
        }

        val rows = tools.jobResources(job.ge360Id).sortedWith(
            compareBy<JobResource> { if (it.kind == ResourceKind.TOOL) 0 else 1 }
                .thenBy { it.name.lowercase(Locale.ITALY) }
        )
        val toolCount = rows.count { it.kind == ResourceKind.TOOL }
        val materialCount = rows.count { it.kind == ResourceKind.MATERIAL }
        summary.text = "${job.code} · DA PRENDERE ${rows.size} · 🔧 $toolCount · 📦 $materialCount"

        if (rows.isEmpty()) {
            container.addView(readyCard(job))
            return
        }
        rows.forEach { container.addView(resourceCard(it)) }
    }

    private fun readyCard(job: JobsiteRecord) = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#C9DED0")
        setCardBackgroundColor(Color.WHITE)
        setContentPadding(dp(18), dp(22), dp(18), dp(22))
        addView(TextView(this@VanModeActivity).apply {
            text = "✓ TUTTO PRESO\n${job.code} · checklist completata"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#1C5D35"))
            setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun resourceCard(item: JobResource) = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E2E4E7")
        setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(9)
        }
        setContentPadding(dp(12), dp(11), dp(12), dp(11))
        addView(LinearLayout(this@VanModeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@VanModeActivity).apply {
                text = "${if (item.kind == ResourceKind.TOOL) "🔧" else "📦"} ${item.name}"
                textSize = 18f
                setTextColor(Color.parseColor("#15171A"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@VanModeActivity).apply {
                text = if (item.kind == ResourceKind.TOOL && item.quantity == 1.0) {
                    "DA PRENDERE"
                } else {
                    "${clean(item.quantity)} ${displayUnit(item)} · DA PRENDERE"
                }
                textSize = 13f
                setTextColor(Color.parseColor("#5E6369"))
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(LinearLayout(this@VanModeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(MaterialButton(this@VanModeActivity).apply {
                    text = "✓ PRESO"
                    textSize = 13f
                    minHeight = dp(52)
                    setOnClickListener { completeItem(item) }
                }, LinearLayout.LayoutParams(0, dp(56), 2f).apply { marginEnd = dp(4) })
                addView(MaterialButton(this@VanModeActivity).apply {
                    text = "✕ TOGLI"
                    textSize = 12f
                    minHeight = dp(52)
                    setOnClickListener { removeItem(item) }
                }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(4) })
            })
        })
    }

    private fun completeItem(item: JobResource) {
        tools.deleteJobResource(item.id)
        Toast.makeText(this, "✓ ${item.name} preso", Toast.LENGTH_SHORT).show()
        refresh()
        syncCurrentPlanCounts()
    }

    private fun removeItem(item: JobResource) {
        tools.deleteJobResource(item.id)
        Toast.makeText(this, "${item.name} tolto dalla checklist", Toast.LENGTH_SHORT).show()
        refresh()
        syncCurrentPlanCounts()
    }

    private fun displayUnit(item: JobResource): String {
        val unit = item.unit.trim()
        return if (unit.equals("sacco", true) || unit.equals("sacchi", true)) {
            if (item.quantity == 1.0) "sacco" else "sacchi"
        } else unit
    }

    private fun emptyText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.parseColor("#6D7278"))
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(30), dp(20), dp(30))
    }

    private fun humanDate(date: LocalDate): String = when (date) {
        LocalDate.now() -> "Oggi · ${date.format(DATE_FORMAT)}"
        LocalDate.now().plusDays(1) -> "Domani · ${date.format(DATE_FORMAT)}"
        else -> date.format(DATE_FORMAT)
    }

    private fun clean(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ITALY, "%.2f", value)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_JOB_ID = "jobsiteId"
        const val EXTRA_DATE = "planDate"
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMMM", Locale.ITALY)
    }
}
