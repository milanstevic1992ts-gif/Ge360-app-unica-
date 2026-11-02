package it.edilmilan.ge360.centrocantiere

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.attrezzi.WorkPreparationActivity
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.galleria.GalleryActivity
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.DailyWorkActivity
import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.ScannerActivity
import it.edilmilan.ge360.spese.ExpensesActivity
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

class JobsiteCenterActivity : AppCompatActivity() {
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var workflowStore: JobsiteWorkflowStore
    private lateinit var dailyStore: DailyStore
    private lateinit var galleryStore: GalleryStore
    private lateinit var documentStore: DocumentStore
    private lateinit var estimateStore: EstimateStore
    private lateinit var toolsStore: ToolsStore
    private lateinit var jobsite: JobsiteRecord

    private lateinit var summaryContainer: LinearLayout
    private lateinit var checklistContainer: LinearLayout
    private lateinit var notesContainer: LinearLayout
    private lateinit var timelineContainer: LinearLayout
    private lateinit var checklistSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobsiteStore = JobsiteStore(this)
        workflowStore = JobsiteWorkflowStore(this)
        dailyStore = DailyStore(this)
        galleryStore = GalleryStore(this)
        documentStore = DocumentStore(this)
        estimateStore = EstimateStore(this)
        toolsStore = ToolsStore(this)

        val requestedId = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val requestedLocalId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        jobsite = jobsiteStore.list().firstOrNull {
            (requestedId.isNotBlank() && it.ge360Id == requestedId) || (requestedLocalId > 0 && it.id == requestedLocalId)
        } ?: run {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(buildScreen())
        ensureStarterChecklist()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::summaryContainer.isInitialized) refreshAll()
    }

    override fun onDestroy() {
        jobsiteStore.close()
        workflowStore.close()
        dailyStore.close()
        galleryStore.close()
        documentStore.close()
        estimateStore.close()
        toolsStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(22))
            setBackgroundColor(Color.parseColor("#F6F7F8"))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(MaterialButton(this@JobsiteCenterActivity).apply {
                text = "←"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(54), dp(48)))
            addView(LinearLayout(this@JobsiteCenterActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = jobsite.displayTitle()
                    textSize = 23f
                    setTextColor(Color.parseColor("#17191C"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = listOf(jobsite.code, jobsite.workType, jobsite.status).filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = 12f
                    setTextColor(Color.parseColor("#666B71"))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)

        body.addView(sectionTitle("RIEPILOGO OPERATIVO", "Tutto ciò che appartiene a questo cantiere, senza duplicare i dati degli altri moduli."))
        summaryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(summaryContainer)

        body.addView(sectionTitle("AZIONI RAPIDE", "Ore, foto, documenti/FIR, attrezzi, preventivo e spese già filtrati sul cantiere quando il modulo lo supporta."))
        body.addView(quickActions())

        body.addView(sectionTitle("CHECKLIST LAVORI", "Tocca un'attività: Da fare → In corso → Fatto. Tieni premuto per scegliere Bloccato o Non necessario."))
        checklistSubtitle = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#666B71"))
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        body.addView(checklistSubtitle)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("＋ ATTIVITÀ") { showAddTaskDialog() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
            addView(actionButton("MODELLO") { showTemplateDialog() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
            addView(actionButton("PREVENTIVO") { showEstimateImport() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5) })
        })
        body.addView(actionButton("🗓 PIANO LAVORAZIONI · DURATE E DIPENDENZE") {
            openContext(JobsiteTaskPlanActivity::class.java)
        }, full(dp(50)))
        checklistContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(checklistContainer)

        body.addView(sectionTitle("NOTE CANTIERE", "Appunti veloci e note importanti restano collegate al cantiere e finiscono nel backup."))
        body.addView(actionButton("＋ AGGIUNGI NOTA") { showAddNoteDialog() }, full(dp(50)))
        notesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(notesContainer)

        body.addView(sectionTitle("CRONOLOGIA", "Eventi checklist e note insieme alle attività reali già registrate in Giornata, Galleria e Scanner."))
        timelineContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(timelineContainer)

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun quickActions(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(actionRow(
            "⏱ Ore / giornata" to { openContext(DailyWorkActivity::class.java) },
            "📸 Foto" to { openContext(GalleryActivity::class.java) }
        ))
        addView(actionRow(
            "📄 Documenti / FIR" to { openContext(ScannerActivity::class.java) },
            "🧰 Attrezzi / materiali" to { openContext(WorkPreparationActivity::class.java) }
        ))
        addView(actionRow(
            "🧾 Preventivi" to { openContext(EstimatesActivity::class.java) },
            "💶 Spese" to { openContext(ExpensesActivity::class.java) }
        ))
    }

    private fun actionRow(left: Pair<String, () -> Unit>, right: Pair<String, () -> Unit>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(quickCard(left.first, left.second), LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginEnd = dp(5); bottomMargin = dp(10) })
        addView(quickCard(right.first, right.second), LinearLayout.LayoutParams(0, dp(66), 1f).apply { marginStart = dp(5); bottomMargin = dp(10) })
    }

    private fun quickCard(title: String, action: () -> Unit): View = MaterialCardView(this).apply {
        radius = dp(15).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.parseColor("#E0E3E6")
        setCardBackgroundColor(Color.WHITE)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(TextView(this@JobsiteCenterActivity).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#25282C"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(10), 0)
        })
    }

    private fun refreshAll() {
        refreshSummary()
        refreshChecklist()
        refreshNotes()
        refreshTimeline()
    }

    private fun refreshSummary() {
        summaryContainer.removeAllViews()
        val flow = workflowStore.summary(jobsite.ge360Id)
        val gallery = galleryStore.summary(jobsite.ge360Id)
        val documents = documentStore.list().count { it.jobsiteId == jobsite.ge360Id }
        val tools = toolsStore.summary(jobsite.ge360Id)
        val minutes = dailyStore.totalMinutesForJobsite(jobsite.ge360Id)
        val estimates = estimateStore.forJobsite(jobsite.ge360Id)
        val preferredEstimate = preferredEstimate(estimates)

        summaryContainer.addView(MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E0E3E6")
            setCardBackgroundColor(Color.WHITE)
            addView(LinearLayout(this@JobsiteCenterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = "Avanzamento checklist · ${flow.completionPercent}%"
                    textSize = 15f
                    setTextColor(Color.parseColor("#25282C"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(ProgressBar(this@JobsiteCenterActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = flow.completionPercent
                }, full(dp(12)))
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = "${flow.done} fatte · ${flow.inProgress} in corso · ${flow.todo} da fare${if (flow.blocked > 0) " · ⚠ ${flow.blocked} bloccate" else ""}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#666B71"))
                    setPadding(0, dp(6), 0, dp(10))
                })
                addView(metricRow("ORE", formatMinutes(minutes), "FOTO", gallery.total.toString(), "DOCUMENTI", documents.toString()))
                addView(space(dp(9)))
                addView(metricRow("DA PORTARE", tools.toBring.toString(), "RICHIESTE", tools.openRequests.toString(), "NOTE", workflowStore.notes(jobsite.ge360Id).size.toString()))
                preferredEstimate?.let { estimate ->
                    addView(TextView(this@JobsiteCenterActivity).apply {
                        text = "Preventivo collegato · ${estimate.number} · ${money(estimate.finalTotal)} · ${estimate.status}"
                        textSize = 12f
                        setTextColor(Color.parseColor("#76511F"))
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, dp(11), 0, 0)
                    })
                }
            })
        })

        val breakdown = dailyStore.laborBreakdownForJobsite(jobsite.ge360Id)
        if (breakdown.isNotEmpty()) {
            summaryContainer.addView(MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
                addView(LinearLayout(this@JobsiteCenterActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(TextView(this@JobsiteCenterActivity).apply {
                        text = "Ore per persona"
                        textSize = 13f
                        setTextColor(Color.parseColor("#25282C"))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    breakdown.forEach { row ->
                        addView(TextView(this@JobsiteCenterActivity).apply {
                            text = "${row.collaborator} · ${formatMinutes(row.minutes)}${if (row.hasMissingRate) " · tariffa da completare" else ""}"
                            textSize = 12f
                            setTextColor(Color.parseColor("#565C62"))
                            setPadding(0, dp(5), 0, 0)
                        })
                    }
                })
            })
        }
    }

    private fun refreshChecklist() {
        checklistContainer.removeAllViews()
        val rows = workflowStore.tasks(jobsite.ge360Id)
        val summary = workflowStore.summary(jobsite.ge360Id)
        checklistSubtitle.text = if (rows.isEmpty()) {
            "Nessuna attività: applica un modello, importa dal preventivo o aggiungi la prima lavorazione."
        } else {
            "${rows.size} attività · ${summary.completionPercent}% completato"
        }
        if (rows.isEmpty()) return

        var lastPhase = ""
        rows.forEach { task ->
            if (task.phase != lastPhase) {
                lastPhase = task.phase
                checklistContainer.addView(TextView(this).apply {
                    text = task.phase.uppercase(Locale.ITALY)
                    textSize = 11f
                    setTextColor(Color.parseColor("#767C82"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(12), dp(4), dp(5))
                })
            }
            checklistContainer.addView(taskCard(task))
        }
    }

    private fun taskCard(task: WorkflowTask): View {
        val statusColor = when (task.status) {
            WorkflowStatuses.DONE -> "#277A49"
            WorkflowStatuses.IN_PROGRESS -> "#B85700"
            WorkflowStatuses.BLOCKED -> "#B42318"
            WorkflowStatuses.NOT_NEEDED -> "#777C82"
            else -> "#4F5963"
        }
        return MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E1E4E7")
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                workflowStore.cycleStatus(task)
                refreshAll()
            }
            setOnLongClickListener {
                showTaskActions(task)
                true
            }
            addView(LinearLayout(this@JobsiteCenterActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(11))
                addView(LinearLayout(this@JobsiteCenterActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@JobsiteCenterActivity).apply {
                        text = when (task.status) {
                            WorkflowStatuses.DONE -> "✓"
                            WorkflowStatuses.IN_PROGRESS -> "◉"
                            WorkflowStatuses.BLOCKED -> "⚠"
                            WorkflowStatuses.NOT_NEEDED -> "⊘"
                            else -> "☐"
                        }
                        textSize = 20f
                        setTextColor(Color.parseColor(statusColor))
                    }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(TextView(this@JobsiteCenterActivity).apply {
                        text = task.title
                        textSize = 14f
                        setTextColor(Color.parseColor("#25282C"))
                        setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@JobsiteCenterActivity).apply {
                        text = task.status
                        textSize = 10f
                        setTextColor(Color.parseColor(statusColor))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                })
                val meta = buildList {
                    if (task.owner.isNotBlank()) add(task.owner)
                    if (task.photoRequired) add("📸 foto")
                    if (task.documentRequired) add("📄 documento")
                    if (task.workPackId.isNotBlank()) add("🧰 ${task.workPackId}")
                    if (task.source == WorkflowSources.ESTIMATE) add("da preventivo")
                }.joinToString(" · ")
                if (task.detail.isNotBlank() || meta.isNotBlank()) {
                    addView(TextView(this@JobsiteCenterActivity).apply {
                        text = listOf(task.detail, meta).filter { it.isNotBlank() }.joinToString("\n")
                        textSize = 11f
                        setTextColor(Color.parseColor("#686E74"))
                        setPadding(dp(32), dp(4), 0, 0)
                    })
                }
            })
        }
    }

    private fun refreshNotes() {
        notesContainer.removeAllViews()
        val rows = workflowStore.notes(jobsite.ge360Id)
        if (rows.isEmpty()) {
            notesContainer.addView(emptyText("Nessuna nota cantiere."))
            return
        }
        rows.take(12).forEach { note ->
            notesContainer.addView(MaterialCardView(this).apply {
                radius = dp(13).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@JobsiteCenterActivity)
                        .setTitle("Nota")
                        .setMessage(note.text)
                        .setNegativeButton("Chiudi", null)
                        .setPositiveButton("Elimina") { _, _ -> workflowStore.deleteNote(note); refreshAll() }
                        .show()
                    true
                }
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = "${if (note.pinned) "📌 " else ""}${note.text}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#35393E"))
                    setPadding(dp(13), dp(11), dp(13), dp(11))
                })
            })
        }
    }

    private data class TimelineRow(val timestamp: Long, val title: String, val detail: String)

    private fun refreshTimeline() {
        timelineContainer.removeAllViews()
        val rows = mutableListOf<TimelineRow>()
        workflowStore.events(jobsite.ge360Id, 40).forEach { rows += TimelineRow(it.createdAt, it.title, it.detail) }
        dailyStore.sessionsForJobsite(jobsite.ge360Id).forEach {
            rows += TimelineRow(it.updatedAt, "${it.collaborator} · ${formatMinutes(it.durationMinutes())}", it.note.ifBlank { "Ore registrate" })
        }
        dailyStore.reports(1000).filter { it.jobsiteId == jobsite.ge360Id }.forEach {
            rows += TimelineRow(it.updatedAt, "Rapportino ${it.workDate}", it.doneToday.ifBlank { if (it.closed) "Giornata chiusa" else "Rapportino aggiornato" })
        }
        galleryStore.list(jobsite.ge360Id).take(40).forEach {
            rows += TimelineRow(it.createdAt, "Foto · ${it.phase}", it.note.ifBlank { it.fileName })
        }
        documentStore.list().filter { it.jobsiteId == jobsite.ge360Id }.take(40).forEach {
            rows += TimelineRow(it.createdAt, "Documento · ${it.type}", listOf(it.supplier, it.documentNumber).filter { value -> value.isNotBlank() }.joinToString(" · "))
        }

        val visible = rows.sortedByDescending { it.timestamp }.distinctBy { Triple(it.timestamp, it.title, it.detail) }.take(30)
        if (visible.isEmpty()) {
            timelineContainer.addView(emptyText("La cronologia si popolerà mentre lavori sul cantiere."))
            return
        }
        visible.forEach { row ->
            timelineContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(8), dp(5), dp(8))
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = row.title
                    textSize = 13f
                    setTextColor(Color.parseColor("#30343A"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@JobsiteCenterActivity).apply {
                    text = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.ITALY).format(Date(row.timestamp))}${if (row.detail.isBlank()) "" else " · ${row.detail}"}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#71777D"))
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }
    }

    private fun ensureStarterChecklist() {
        if (workflowStore.tasks(jobsite.ge360Id).isNotEmpty()) return
        val estimate = preferredEstimate(estimateStore.forJobsite(jobsite.ge360Id))
        val template = WorkflowTemplates.suggestedFor(jobsite.workType)
        val templateAdded = workflowStore.applyTemplate(jobsite, template)
        val estimateAdded = estimate?.let { workflowStore.applyEstimate(jobsite, it) } ?: 0
        if (templateAdded + estimateAdded > 0) {
            workflowStore.recordEvent(jobsite.ge360Id, "INIT", "Centro Cantiere inizializzato", "Modello ${template.title}${estimate?.let { value -> " + preventivo ${value.number}" } ?: ""}")
        }
    }

    private fun showTemplateDialog() {
        val rows = WorkflowTemplates.ALL
        AlertDialog.Builder(this)
            .setTitle("Applica modello lavorazioni")
            .setItems(rows.map { "${it.title} · ${it.tasks.size} attività" }.toTypedArray()) { _, index ->
                val added = workflowStore.applyTemplate(jobsite, rows[index])
                Toast.makeText(this, "$added attività aggiunte", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showEstimateImport() {
        val rows = estimateStore.forJobsite(jobsite.ge360Id)
        if (rows.isEmpty()) {
            Toast.makeText(this, "Nessun preventivo collegato a questo cantiere", Toast.LENGTH_LONG).show()
            openContext(EstimatesActivity::class.java)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Genera dal preventivo")
            .setItems(rows.map { "${it.number} · ${it.status} · ${money(it.finalTotal)}" }.toTypedArray()) { _, index ->
                val added = workflowStore.applyEstimate(jobsite, rows[index])
                Toast.makeText(this, "$added lavorazioni aggiunte senza duplicati", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showAddTaskDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val title = EditText(this).apply { hint = "Lavoro da fare"; isSingleLine = true }
        val detail = EditText(this).apply { hint = "Dettagli / note"; minLines = 2 }
        val owner = EditText(this).apply { hint = "Responsabile (es. Milan, Papà)"; isSingleLine = true }
        body.addView(title, full(dp(54)))
        body.addView(detail, full(dp(80)))
        body.addView(owner, full(dp(54)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Nuova attività")
            .setView(body)
            .setPositiveButton("Aggiungi", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.toString().trim().isBlank()) {
                    title.error = "Scrivi la lavorazione"
                    return@setOnClickListener
                }
                workflowStore.addManualTask(jobsite, title.text.toString(), detail.text.toString(), owner.text.toString())
                dialog.dismiss()
                refreshAll()
            }
        }
        dialog.show()
    }

    private fun showAddNoteDialog() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val text = EditText(this).apply { hint = "Nota cantiere"; minLines = 3; gravity = Gravity.TOP }
        val pinned = CheckBox(this).apply { this.text = "Nota importante / fissata" }
        body.addView(text, full(dp(110)))
        body.addView(pinned)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Aggiungi nota")
            .setView(body)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (text.text.toString().trim().isBlank()) {
                    text.error = "Scrivi una nota"
                    return@setOnClickListener
                }
                workflowStore.addNote(jobsite.ge360Id, text.text.toString(), pinned.isChecked)
                dialog.dismiss()
                refreshAll()
            }
        }
        dialog.show()
    }

    private fun showTaskActions(task: WorkflowTask) {
        val actions = WorkflowStatuses.ALL + "Pianifica durata / precedenze" + "Elimina attività"
        AlertDialog.Builder(this)
            .setTitle(task.title)
            .setItems(actions.toTypedArray()) { _, index ->
                when {
                    index < WorkflowStatuses.ALL.size ->
                        workflowStore.updateStatus(task, WorkflowStatuses.ALL[index])
                    index == WorkflowStatuses.ALL.size ->
                        startActivity(Intent(this, JobsiteTaskPlanActivity::class.java).apply {
                            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
                            putExtra(JobsiteTaskPlanActivity.EXTRA_TASK_ID, task.ge360Id)
                        })
                    else -> workflowStore.deleteTask(task)
                }
                refreshAll()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun preferredEstimate(rows: List<EstimateRecord>): EstimateRecord? {
        val approved = setOf(EstimateStatuses.ACCEPTED, EstimateStatuses.IN_PROGRESS, EstimateStatuses.COMPLETED)
        return rows.firstOrNull { it.status in approved } ?: rows.firstOrNull()
    }

    private fun openContext(target: Class<*>) {
        startActivity(Intent(this, target).apply {
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, jobsite.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, jobsite.code)
        })
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(18), dp(4), dp(8))
        addView(TextView(this@JobsiteCenterActivity).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#4C5359"))
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@JobsiteCenterActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.parseColor("#7A8086"))
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun metricRow(l1: String, v1: String, l2: String, v2: String, l3: String, v3: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(metric(l1, v1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metric(l2, v2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(metric(l3, v3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun metric(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@JobsiteCenterActivity).apply {
            text = label
            textSize = 9f
            setTextColor(Color.parseColor("#7B8187"))
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@JobsiteCenterActivity).apply {
            text = value
            textSize = 16f
            setTextColor(Color.parseColor("#24272B"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun actionButton(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { action() }
    }

    private fun emptyText(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.parseColor("#72787E"))
        setPadding(dp(5), dp(10), dp(5), dp(10))
    }

    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, height) }
    private fun full(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun money(value: Double) = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun formatMinutes(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h h" else "$h h ${m}m"
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
