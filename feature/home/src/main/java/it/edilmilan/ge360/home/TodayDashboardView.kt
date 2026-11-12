package it.edilmilan.ge360.home

import androidx.appcompat.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.centrocantiere.WorkflowSources
import it.edilmilan.ge360.centrocantiere.WorkflowStatuses
import it.edilmilan.ge360.centrocantiere.WorkflowTask
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.DailyWorkActivity
import it.edilmilan.ge360.giornata.EveningReportActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Blocco leggero della Home dedicato a ciò che serve oggi.
 * I promemoria possono essere generali oppure collegati a un singolo cantiere.
 */
class TodayDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private data class DashboardReminder(
        val job: JobsiteRecord?,
        val task: WorkflowTask
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.ITALY)

    init {
        orientation = VERTICAL
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refresh()
    }

    fun refresh() {
        removeAllViews()
        val app = context.applicationContext
        val daily = DailyStore(app)
        val jobs = JobsiteStore(app)
        val workflow = JobsiteWorkflowStore(app)

        val running = buildList {
            daily.runningSession()?.let { add(it) }
            addAll(daily.runningPersonnelSessions())
        }.distinctBy { it.ge360Id }

        val today = LocalDate.now()
        val assignments = runCatching { daily.assignments(today, today) }.getOrDefault(emptyList())
        val reports = runCatching { daily.reports(1000).filter { it.workDate == today.toString() } }.getOrDefault(emptyList())
        val jobRows = runCatching { jobs.list() }.getOrDefault(emptyList())
        val operationalJobs = jobRows.filter { it.status != JobsiteStore.STATUS_ARCHIVED }
        val activeJobs = operationalJobs.filter {
            it.status == JobsiteStore.STATUS_ACTIVE || it.status == JobsiteStore.STATUS_SCHEDULED
        }

        val unresolvedReminders = buildList {
            operationalJobs.forEach { job ->
                runCatching { workflow.tasks(job.ge360Id) }.getOrDefault(emptyList())
                    .filter { task -> isOpenDashboardReminder(task) }
                    .forEach { task -> add(DashboardReminder(job, task)) }
            }
            runCatching { workflow.tasks(GENERAL_REMINDER_SCOPE) }.getOrDefault(emptyList())
                .filter { task -> isOpenDashboardReminder(task) }
                .forEach { task -> add(DashboardReminder(null, task)) }
        }

        val dueReminders = unresolvedReminders
            .filter { item -> reminderDue(item.task)?.let { !it.isAfter(today) } ?: true }
            .sortedWith(
                compareBy<DashboardReminder> {
                    reminderDue(it.task)?.toEpochDay() ?: Long.MIN_VALUE
                }.thenBy { it.task.createdAt }
            )
        val futureReminderCount = unresolvedReminders.count { item -> reminderDue(item.task)?.isAfter(today) == true }

        daily.close()
        jobs.close()
        workflow.close()

        val issues = reports.filter { it.issues.isNotBlank() }
        val materials = reports.filter { it.materialsNeeded.isNotBlank() }

        addView(header("OGGI", "Timer, squadra, cantieri, problemi e promemoria da non dimenticare"))

        addView(MaterialCardView(context).apply {
            applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 20)
            isClickable = true
            isFocusable = true
            setOnClickListener { context.startActivity(Intent(context, DailyWorkActivity::class.java)) }
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(15), dp(15), dp(15), dp(15))

                if (running.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = "⏱ ${running.size} timer ${if (running.size == 1) "attivo" else "attivi"}"
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.PrimaryDark)
                    })
                    running.take(3).forEach { session ->
                        addView(TextView(context).apply {
                            text = "${session.collaborator} · ${session.jobsiteTitle.ifBlank { session.jobsiteCode.ifBlank { "Cantiere" } }} · ${formatMinutes(session.durationMinutes())}"
                            textSize = 12f
                            setTextColor(Ge360Brand.TextSecondary)
                            setPadding(0, dp(5), 0, 0)
                        })
                    }
                } else {
                    addView(TextView(context).apply {
                        text = "Nessun timer attivo"
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.Forest)
                    })
                }

                addView(metricRow(
                    "SQUADRA", assignments.size.toString(),
                    "RAPPORTINI", reports.size.toString(),
                    "CANTIERI", activeJobs.size.toString()
                ))

                if (assignments.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = assignments.take(3).joinToString("\n") { row ->
                            "• ${row.personnelName} → ${row.jobsiteTitle.ifBlank { row.jobsiteCode }} (${row.plannedMinutes / 60}h${if (row.plannedMinutes % 60 > 0) " ${row.plannedMinutes % 60}m" else ""})"
                        }
                        textSize = 12f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(11), 0, 0)
                    })
                }

                if (issues.isNotEmpty() || materials.isNotEmpty()) {
                    addView(TextView(context).apply {
                        text = buildString {
                            if (issues.isNotEmpty()) append("⚠ ${issues.size} rapportini con problemi")
                            if (issues.isNotEmpty() && materials.isNotEmpty()) append(" · ")
                            if (materials.isNotEmpty()) append("🛒 ${materials.size} con materiale richiesto")
                        }
                        textSize = 12f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.PrimaryDark)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = context.brandRounded(
                            fill = Ge360Brand.WarningSoft,
                            stroke = Ge360Brand.WarningSoft,
                            radiusDp = 12,
                            strokeDp = 0
                        )
                    }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(11)
                    })
                }
            })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(MaterialButton(context).apply {
            text = "📝 RAPPORTINO SERALE · ${reports.size} oggi"
            textSize = 15f
            minHeight = dp(54)
            setOnClickListener { context.startActivity(Intent(context, EveningReportActivity::class.java)) }
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(10)
        })

        addView(reminderCard(dueReminders, futureReminderCount), LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        if (activeJobs.isNotEmpty()) {
            addView(TextView(context).apply {
                text = "Prossimi cantieri · ${activeJobs.take(3).joinToString("  ·  ") { it.displayTitle() }}"
                textSize = 11.5f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(dp(4), dp(8), dp(4), 0)
                setOnClickListener { context.startActivity(Intent(context, JobsitesActivity::class.java)) }
            })
        }
    }

    private fun reminderCard(
        reminders: List<DashboardReminder>,
        futureReminderCount: Int
    ): View = MaterialCardView(context).apply {
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 20)
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))

            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = "PROMEMORIA"
                        textSize = 11f
                        letterSpacing = 0.06f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.PrimaryDark)
                    })
                    addView(TextView(context).apply {
                        text = if (reminders.isEmpty()) "Niente da fare adesso" else "${reminders.size} ${if (reminders.size == 1) "cosa da fare" else "cose da fare"}"
                        textSize = 16f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.ForestDark)
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(context).apply {
                    text = "＋ NOTA"
                    textSize = 10f
                    setOnClickListener { showAddReminderDialog() }
                }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            })

            if (reminders.isEmpty()) {
                addView(TextView(context).apply {
                    text = "Aggiungi una cosa da fare generale oppure legata a un cantiere. Scegli domani, una data precisa o lasciala qui finché non clicchi Fatto."
                    textSize = 12f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(8), 0, 0)
                })
            } else {
                reminders.take(8).forEach { reminder ->
                    addView(reminderRow(reminder), LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) })
                }
                if (reminders.size > 8) {
                    addView(TextView(context).apply {
                        text = "+ ${reminders.size - 8} altri promemoria"
                        textSize = 11f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(dp(4), dp(7), dp(4), 0)
                    })
                }
            }

            if (futureReminderCount > 0) {
                addView(TextView(context).apply {
                    text = "📅 $futureReminderCount ${if (futureReminderCount == 1) "promemoria programmato" else "promemoria programmati"} per i prossimi giorni"
                    textSize = 11f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(dp(4), dp(8), dp(4), 0)
                })
            }
        })
    }

    private fun reminderRow(reminder: DashboardReminder): View = MaterialCardView(context).apply {
        val job = reminder.job
        val task = reminder.task
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 14)
        isClickable = job != null
        isFocusable = job != null
        if (job != null) setOnClickListener { openJobsite(job) }
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(7), dp(8))

            addView(CheckBox(context).apply {
                contentDescription = "Segna promemoria come fatto"
                setOnCheckedChangeListener { _, checked ->
                    if (checked) markReminderDone(task)
                }
            }, LayoutParams(dp(44), dp(44)))

            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = task.title
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                })
                addView(TextView(context).apply {
                    text = "${job?.displayTitle() ?: "Generale"} · ${reminderWhenLabel(task)}"
                    textSize = 10.5f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(MaterialButton(context).apply {
                text = "DOMANI"
                textSize = 9f
                setOnClickListener { snoozeReminderTomorrow(task) }
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        })
    }

    private fun showAddReminderDialog() {
        val jobsStore = JobsiteStore(context.applicationContext)
        val availableJobs = runCatching { jobsStore.list().filter { it.status != JobsiteStore.STATUS_ARCHIVED } }
            .getOrDefault(emptyList())
        jobsStore.close()

        val today = LocalDate.now()
        var customDate: LocalDate? = null
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }

        val linkLabels = buildList {
            add("Generale · nessun cantiere")
            addAll(availableJobs.map { "${it.code} · ${it.displayTitle()}" })
        }
        val jobSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                linkLabels
            )
        }
        val noteInput = EditText(context).apply {
            hint = "Es. portare documenti al commercialista, chiamare il cliente, comprare colla…"
            minLines = 2
            gravity = Gravity.TOP
        }
        val timing = RadioGroup(context).apply { orientation = VERTICAL }
        val persistent = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Finché non clicco Fatto"
            isChecked = true
        }
        val tomorrow = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Domani · ${today.plusDays(1).format(dateFormatter)}"
        }
        val custom = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Scegli una data"
        }
        timing.addView(persistent)
        timing.addView(tomorrow)
        timing.addView(custom)

        custom.setOnClickListener {
            val initial = customDate ?: today
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    customDate = LocalDate.of(year, month + 1, day)
                    custom.text = "Data · ${customDate!!.format(dateFormatter)}"
                    custom.isChecked = true
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth
            ).show()
        }

        body.addView(TextView(context).apply {
            text = "Collega a"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(jobSpinner, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        body.addView(TextView(context).apply {
            text = "Nota / cosa da fare"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        body.addView(noteInput, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)))
        body.addView(TextView(context).apply {
            text = "Quando deve comparire"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        body.addView(timing)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Nuovo promemoria")
            .setView(body)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = noteInput.text.toString().trim()
                if (text.isBlank()) {
                    noteInput.error = "Scrivi cosa devi ricordare"
                    return@setOnClickListener
                }
                val due = when (timing.checkedRadioButtonId) {
                    tomorrow.id -> today.plusDays(1)
                    custom.id -> customDate ?: run {
                        Toast.makeText(context, "Scegli prima la data", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    else -> null
                }

                val selectedJob = if (jobSpinner.selectedItemPosition <= 0) {
                    null
                } else {
                    availableJobs.getOrNull(jobSpinner.selectedItemPosition - 1)
                }
                val workflow = JobsiteWorkflowStore(context.applicationContext)
                runCatching {
                    workflow.saveTask(
                        WorkflowTask(
                            jobsiteId = selectedJob?.ge360Id ?: GENERAL_REMINDER_SCOPE,
                            workType = selectedJob?.workType ?: GENERAL_WORK_TYPE,
                            templateId = reminderToken(due),
                            phase = if (selectedJob == null) "Promemoria generale" else "Promemoria",
                            sortOrder = REMINDER_SORT_BASE + (System.currentTimeMillis() % 900_000L).toInt(),
                            title = text,
                            source = WorkflowSources.MANUAL
                        )
                    )
                }.onFailure {
                    Toast.makeText(context, "Impossibile salvare il promemoria: ${it.message ?: "errore"}", Toast.LENGTH_LONG).show()
                }.onSuccess {
                    Toast.makeText(
                        context,
                        if (selectedJob == null) "Promemoria generale salvato" else "Promemoria salvato nel cantiere",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    refresh()
                }
                workflow.close()
            }
        }
        dialog.show()
    }

    private fun markReminderDone(task: WorkflowTask) {
        val workflow = JobsiteWorkflowStore(context.applicationContext)
        runCatching { workflow.updateStatus(task, WorkflowStatuses.DONE) }
            .onFailure { Toast.makeText(context, "Errore nel salvataggio", Toast.LENGTH_SHORT).show() }
        workflow.close()
        refresh()
    }

    private fun snoozeReminderTomorrow(task: WorkflowTask) {
        val workflow = JobsiteWorkflowStore(context.applicationContext)
        task.templateId = reminderToken(LocalDate.now().plusDays(1))
        task.status = WorkflowStatuses.TODO
        runCatching { workflow.saveTask(task) }
            .onSuccess { Toast.makeText(context, "Promemoria spostato a domani", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(context, "Errore nel salvataggio", Toast.LENGTH_SHORT).show() }
        workflow.close()
        refresh()
    }

    private fun openJobsite(job: JobsiteRecord) {
        val intent = Intent(Ge360Contracts.ACTION_OPEN_JOBSITE).apply {
            setPackage(context.packageName)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, job.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, job.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, job.code)
        }
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
        else context.startActivity(Intent(context, JobsitesActivity::class.java))
    }

    private fun isOpenDashboardReminder(task: WorkflowTask): Boolean =
        isDashboardReminder(task) &&
            task.status != WorkflowStatuses.DONE &&
            task.status != WorkflowStatuses.NOT_NEEDED

    private fun isDashboardReminder(task: WorkflowTask): Boolean = task.templateId.startsWith(REMINDER_PREFIX)

    private fun reminderToken(due: LocalDate?): String = REMINDER_PREFIX + (due?.toString() ?: REMINDER_PERSISTENT)

    private fun reminderDue(task: WorkflowTask): LocalDate? {
        if (!isDashboardReminder(task)) return null
        val raw = task.templateId.removePrefix(REMINDER_PREFIX)
        if (raw == REMINDER_PERSISTENT) return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private fun reminderWhenLabel(task: WorkflowTask): String {
        val due = reminderDue(task) ?: return "finché non clicchi Fatto"
        val today = LocalDate.now()
        return when {
            due == today -> "oggi"
            due.isBefore(today) -> "scaduta ${due.format(dateFormatter)}"
            due == today.plusDays(1) -> "domani"
            else -> due.format(dateFormatter)
        }
    }

    private fun header(title: String, subtitle: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(10))
        addView(TextView(context).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
        })
        addView(TextView(context).apply {
            text = "Il lavoro di oggi"
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
            setPadding(0, dp(2), 0, 0)
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun metricRow(
        firstLabel: String,
        firstValue: String,
        secondLabel: String,
        secondValue: String,
        thirdLabel: String,
        thirdValue: String
    ): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(0, dp(13), 0, 0)
        addView(metric(firstLabel, firstValue, Ge360Brand.PrimarySoft), LayoutParams(0, dp(70), 1f).apply { marginEnd = dp(4) })
        addView(metric(secondLabel, secondValue, Ge360Brand.ForestSoft), LayoutParams(0, dp(70), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        addView(metric(thirdLabel, thirdValue, 0xFFEEF5FF.toInt()), LayoutParams(0, dp(70), 1f).apply { marginStart = dp(4) })
    }

    private fun metric(label: String, value: String, fill: Int): View = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(7), dp(8), dp(7))
        background = context.brandRounded(fill = fill, stroke = fill, radiusDp = 14, strokeDp = 0)
        addView(TextView(context).apply {
            text = value
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = label
            textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
            gravity = Gravity.CENTER
        })
    }

    private fun formatMinutes(minutes: Long): String {
        val safe = minutes.coerceAtLeast(0)
        val hours = safe / 60
        val mins = safe % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REMINDER_PREFIX = "dashboard-reminder:"
        private const val REMINDER_PERSISTENT = "persistent"
        private const val REMINDER_SORT_BASE = 1_000_000
        private const val GENERAL_REMINDER_SCOPE = "__ge360_home_general__"
        private const val GENERAL_WORK_TYPE = "Generale"
    }
}
