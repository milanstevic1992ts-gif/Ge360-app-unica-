package it.edilmilan.ge360.centrocantiere

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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.attrezzi.ResourceReminderDialog
import it.edilmilan.ge360.attrezzi.VanModeActivity
import it.edilmilan.ge360.attrezzi.MaterialsActivity
import it.edilmilan.ge360.attrezzi.MaterialEvidenceActivityV13
import it.edilmilan.ge360.attrezzi.WorkPreparationActivity
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteProgressActivity
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.brandRounded
import it.edilmilan.ge360.core.common.ui.buildScreenHeaderWithSubtitle
import it.edilmilan.ge360.core.common.ui.ge360Badge
import it.edilmilan.ge360.galleria.GalleryActivity
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.EveningReportActivity
import it.edilmilan.ge360.giornata.CompensationActivity
import it.edilmilan.ge360.giornata.JobsiteLaborActivity
import it.edilmilan.ge360.giornata.laborDayCostForJobsite
import it.edilmilan.ge360.giornata.laborDayHoursForJobsite
import it.edilmilan.ge360.giornata.laborDayWorkedPersonDaysForJobsite
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.scanner.CanonicalDocumentRegistry
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.FirStore
import it.edilmilan.ge360.scanner.ScannerActivity
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.spese.FinanceStore
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

class JobsiteHubActivity : AppCompatActivity() {
    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var dailyStore: DailyStore
    private lateinit var documentStore: DocumentStore
    private lateinit var firStore: FirStore
    private lateinit var estimateStore: EstimateStore
    private lateinit var galleryStore: GalleryStore
    private lateinit var toolsStore: ToolsStore
    private lateinit var archiveStore: JobsiteArchiveStore
    private lateinit var financeStore: FinanceStore
    private lateinit var jobsite: JobsiteRecord
    private lateinit var summaryContainer: LinearLayout
    private lateinit var controlContainer: LinearLayout
    private var controlRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobsiteStore = JobsiteStore(this)
        dailyStore = DailyStore(this)
        documentStore = DocumentStore(this)
        firStore = FirStore(this)
        estimateStore = EstimateStore(this)
        galleryStore = GalleryStore(this)
        toolsStore = ToolsStore(this)
        archiveStore = JobsiteArchiveStore(this)
        financeStore = FinanceStore(this)

        jobsite = resolveJobsite() ?: run {
            Toast.makeText(this, "Cantiere non trovato", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(buildScreen())
        refreshSummary()
    }

    override fun onResume() {
        super.onResume()
        if (::summaryContainer.isInitialized) refreshSummary()
        if (::controlContainer.isInitialized && ::jobsite.isInitialized) loadControlPulse()
    }

    override fun onDestroy() {
        controlRequest++ // Ignore callbacks from an activity that is closing.
        jobsiteStore.close()
        dailyStore.close()
        documentStore.close()
        firStore.close()
        estimateStore.close()
        galleryStore.close()
        toolsStore.close()
        archiveStore.close()
        financeStore.close()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            setBackgroundColor(Ge360Brand.Background)
        }
        root.addView(
            buildScreenHeaderWithSubtitle(
                context = this,
                title = jobsite.displayTitle(),
                subtitle = listOf(jobsite.code, jobsite.workType, jobsite.status).filter { it.isNotBlank() }.joinToString(" · "),
                onBack = { finish() }
            )
        )

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        scroll.addView(body)

        body.addView(sectionTitle("CONTROLLO OPERATIVO",
            "Una lettura guidata del cantiere: dati registrati, attenzione e prossima azione."))
        controlContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(controlContainer)
        body.addView(sectionTitle("CANTIERE",
            "Un'unica cartella: lavorazioni, ore, documenti, preventivi, spese, foto, planimetrie e appunti."))
        summaryContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(summaryContainer)

        body.addView(sectionTitle("OPERATIVO", "Le attività che servono durante il lavoro."))
        body.addView(actionCard(
            ActionSpec("📝", "RAPPORTINO SERALE", "Lavori fatti oggi, domani e imprevisti · salvato qui") {
                openContext(EveningReportActivity::class.java)
            }
        ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)).apply {
            bottomMargin = dp(10)
        })

        body.addView(actionRow(
            ActionSpec("✅", "Cronologia / lavori", "Checklist, fasi e attività registrate") { openContext(JobsiteCenterActivity::class.java) },
            ActionSpec("👷", "Ore / manodopera", "Calendario, operatori e costo per giornata") { openContext(JobsiteLaborActivity::class.java) }
        ))
        body.addView(actionRow(
            ActionSpec("🗓", "Piano lavorazioni", "Durate, dipendenze e sequenza del cantiere") { openContext(JobsiteTaskPlanActivity::class.java) },
            ActionSpec("📈", "Avanzamento & SAL", "Fasi fisiche, storico, scenari e acconti") { openContext(JobsiteProgressActivity::class.java) }
        ))
        body.addView(actionRow(
            ActionSpec("🧰", "Attrezzi / materiali", "Preparazione del lavoro") { openContext(WorkPreparationActivity::class.java) },
            ActionSpec("📸", "Foto", "Prima, durante e dopo") { openContext(GalleryActivity::class.java) }
        ))

        body.addView(actionRow(
            ActionSpec("📅", "Piano settimanale", "Squadra e materiali della settimana") { openWeeklyPlanner() },
            ActionSpec("🗓", "Note e Agenda", "Scadenze del cantiere e note collegate") { openAgenda(null) }
        ))
        body.addView(actionRow(
            ActionSpec("✍", "Nota veloce", "Salva una nota già collegata al cantiere") { openAgenda("NOTE") },
            ActionSpec("✅", "Nuova attività", "Promemoria collegato al cantiere") { openAgenda("TASK") }
        ))
        body.addView(actionRow(
            ActionSpec("🧠", "Ricorda da portare", "Materiale, attrezzo o acquisto per questo cantiere") {
                ResourceReminderDialog.show(this, preferredJobsiteId = jobsite.ge360Id) { savedJob ->
                    if (savedJob.ge360Id == jobsite.ge360Id) refreshSummary()
                }
            },
            ActionSpec("🚚", "Checklist furgone", "Spunta cosa comprare e caricare") {
                startActivity(Intent(this, VanModeActivity::class.java).putExtra(
                    VanModeActivity.EXTRA_JOB_ID, jobsite.ge360Id
                ))
            }
        ))

        body.addView(sectionTitle("DOCUMENTI E CLIENTE", "FIR, DDT, fatture, piante e appunti nello stesso archivio cantiere."))
        body.addView(actionRow(
            ActionSpec("📄", "Documenti / FIR", "PDF, fatture, DDT e formulari rifiuti") { openContext(ScannerActivity::class.java) },
            ActionSpec("📐", "Planimetrie / cliente", "Piante, scansioni e documenti consegnati") { openArchive() }
        ))
        body.addView(actionRow(
            ActionSpec("👤", "Appunti cliente", "Richieste, scelte, misure e decisioni") { openArchive() },
            ActionSpec("🔒", "Appunti interni", "Note riservate, mai condivise automaticamente") { openArchive() }
        ))

        body.addView(sectionTitle("ECONOMICO", "Preventivo, entrate, costi, utile e margine sempre riferiti al cantiere."))
        body.addView(actionRow(
            ActionSpec("🧾", "Preventivi", "Preventivi collegati al lavoro") { openContext(EstimatesActivity::class.java) },
            ActionSpec("📊", "Costi / statistiche", "Torte, totale costi, utile e margine") { openContext(JobsiteCostAnalyticsActivity::class.java) }
        ))

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun openWeeklyPlanner() {
        val target = Intent().setClassName(packageName, "it.edilmilan.ge360.agenda.WeeklySitePlannerActivity")
            .putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
        runCatching { startActivity(target) }
            .onFailure { Toast.makeText(this, "Pianificatore non disponibile", Toast.LENGTH_LONG).show() }
    }

    private fun openAgenda(createKind: String?) {
        val target = Intent().setClassName(packageName, "it.edilmilan.ge360.agenda.AgendaActivity")
            .putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
            .putExtra(Ge360Contracts.EXTRA_CLIENT_ID, jobsite.clientGe360Id)
            .putExtra(Ge360Contracts.EXTRA_AGENDA_CONTEXT_LABEL, jobsite.displayTitle())
        if (createKind != null) target.putExtra(Ge360Contracts.EXTRA_AGENDA_CREATE_KIND, createKind)
        runCatching { startActivity(target) }
            .onFailure { Toast.makeText(this, "Agenda non disponibile in questa build", Toast.LENGTH_LONG).show() }
    }

    private fun loadControlPulse() {
        val request = ++controlRequest
        controlContainer.removeAllViews()
        controlContainer.addView(MaterialCardView(this).apply {
            applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 18)
            addView(TextView(this@JobsiteHubActivity).apply {
                text = "Lettura delle informazioni del cantiere…"
                textSize = 12f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(dp(15), dp(14), dp(15), dp(14))
            })
        })
        val selected = jobsite.copy()
        thread(name = "ge360-jobsite-v12") {
            val outcome = runCatching { JobsiteControlV12.read(applicationContext, selected) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != controlRequest) return@runOnUiThread
                controlContainer.removeAllViews()
                outcome.onSuccess { renderControlPulse(it) }.onFailure {
                    controlContainer.addView(controlTextCard(
                        "Controllo non completato",
                        "Alcuni archivi non sono leggibili: riprova o apri il Centro diagnostico.",
                        JobsiteControlV12.Level.ATTENZIONE))
                }
            }
        }
    }

    private fun renderControlPulse(pulse: JobsiteControlV12.Pulse) {
        val level = when {
            pulse.critical > 0 -> JobsiteControlV12.Level.CRITICO
            pulse.issues > 0 -> JobsiteControlV12.Level.ATTENZIONE
            else -> JobsiteControlV12.Level.INFO
        }
        controlContainer.addView(controlTextCard(
            pulse.headline,
            "${pulse.signals.size} aree lette · ${pulse.issues} da verificare · " +
                "Stato indicativo; nessuna operazione automatica",
            level))
        pulse.signals.forEach { signal ->
            val view = MaterialCardView(this).apply {
                applyGe360Card(fill = Ge360Brand.Surface,
                    stroke = Ge360Brand.Border, radiusDp = 17)
                addView(LinearLayout(this@JobsiteHubActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(12))
                    addView(LinearLayout(this@JobsiteHubActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@JobsiteHubActivity).apply {
                            text = signal.title
                            textSize = 14f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Ge360Brand.ForestDark)
                        }, LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@JobsiteHubActivity).apply {
                            text = when (signal.level) {
                                JobsiteControlV12.Level.OK -> "REGISTRATO"
                                JobsiteControlV12.Level.INFO -> "INFORMATIVO"
                                JobsiteControlV12.Level.ATTENZIONE -> "VERIFICARE"
                                JobsiteControlV12.Level.CRITICO -> "BLOCCATO"
                            }
                            textSize = 10f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Ge360Brand.PrimaryDark)
                        })
                    })
                    addView(TextView(this@JobsiteHubActivity).apply {
                        text = signal.value
                        textSize = 18f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.ForestDark)
                        setPadding(0, dp(5), 0, 0)
                    })
                    addView(TextView(this@JobsiteHubActivity).apply {
                        text = signal.detail
                        textSize = 11.5f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(5), 0, 0)
                    })
                    if (signal.action != JobsiteControlV12.Action.NONE) {
                        addView(TextView(this@JobsiteHubActivity).apply {
                            text = "Apri modulo  ›"
                            textSize = 12f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Ge360Brand.PrimaryDark)
                            setPadding(0, dp(9), 0, dp(3))
                            isClickable = true
                            isFocusable = true
                            contentDescription = "Apri modulo ${signal.title}"
                            setOnClickListener { openControlAction(signal.action) }
                        })
                    }
                })
            }
            controlContainer.addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        controlContainer.addView(TextView(this).apply {
            text = "Materiali: carichi di magazzino ≠ acquisti fatturati. " +
                "Costi ore e compensi non vengono sommati automaticamente alle Spese."
            textSize = 10.5f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(dp(5), dp(6), dp(5), dp(6))
        })
        controlContainer.addView(TextView(this).apply {
            text = "Aggiorna quadro"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { loadControlPulse() }
        })
    }

    private fun controlTextCard(
        heading: String,
        detail: String,
        level: JobsiteControlV12.Level
    ): View = MaterialCardView(this).apply {
        val fill = when (level) {
            JobsiteControlV12.Level.CRITICO -> Ge360Brand.WarningSoft
            JobsiteControlV12.Level.ATTENZIONE -> Ge360Brand.SurfaceWarm
            else -> Ge360Brand.PrimarySoft
        }
        applyGe360Card(fill = fill, stroke = Ge360Brand.Border, radiusDp = 17)
        addView(LinearLayout(this@JobsiteHubActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(this@JobsiteHubActivity).apply {
                text = heading
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
            })
            addView(TextView(this@JobsiteHubActivity).apply {
                text = detail
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(5), 0, 0)
            })
        })
    }

    private fun openControlAction(action: JobsiteControlV12.Action) {
        when (action) {
            JobsiteControlV12.Action.WORK -> openContext(JobsiteCenterActivity::class.java)
            JobsiteControlV12.Action.MATERIALS -> openContext(MaterialsActivity::class.java)
            JobsiteControlV12.Action.EVIDENCE -> openContext(MaterialEvidenceActivityV13::class.java)
            JobsiteControlV12.Action.DOCUMENTS -> openContext(ScannerActivity::class.java)
            JobsiteControlV12.Action.LABOR -> openContext(JobsiteLaborActivity::class.java)
            JobsiteControlV12.Action.COMPENSATIONS -> openContext(CompensationActivity::class.java)
            JobsiteControlV12.Action.COSTS -> openContext(JobsiteCostAnalyticsActivity::class.java)
            JobsiteControlV12.Action.ESTIMATES -> openContext(EstimatesActivity::class.java)
            JobsiteControlV12.Action.NONE -> Unit
        }
    }

    private fun refreshSummary() {
        summaryContainer.removeAllViews()
        val minutes = dailyStore.totalMinutesForJobsite(jobsite.ge360Id)
        val laborDayHours = dailyStore.laborDayHoursForJobsite(jobsite.ge360Id)
        val laborDayCost = dailyStore.laborDayCostForJobsite(jobsite.ge360Id)
        val laborTotalCost = dailyStore.laborCostForJobsite(jobsite.ge360Id)
        val canonicalDocs = CanonicalDocumentRegistry(documentStore, firStore).summary(jobsite.ge360Id)
        val fir = firStore.summary(jobsite.ge360Id)
        val estimates = estimateStore.forJobsite(jobsite.ge360Id).size
        val photos = galleryStore.summary(jobsite.ge360Id).total
        val tools = toolsStore.summary(jobsite.ge360Id)
        val plans = archiveStore.files(jobsite.ge360Id, JobsiteArchiveCategories.PLAN).size
        val clientDocs = archiveStore.files(jobsite.ge360Id, JobsiteArchiveCategories.CLIENT_DOCUMENT).size
        val clientNotes = archiveStore.memos(jobsite.ge360Id, JobsiteMemoTypes.CLIENT).size
        val internalNotes = archiveStore.memos(jobsite.ge360Id, JobsiteMemoTypes.INTERNAL).size
        val finance = financeStore.jobsiteSummary(jobsite.ge360Id)

        summaryContainer.addView(MaterialCardView(this).apply {
            applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.BorderStrong, radiusDp = 20)
            addView(LinearLayout(this@JobsiteHubActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(15), dp(15), dp(15))

                addView(LinearLayout(this@JobsiteHubActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@JobsiteHubActivity).apply {
                        text = "Riepilogo cantiere"
                        textSize = 17f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.ForestDark)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(this@JobsiteHubActivity.ge360Badge(jobsite.status))
                })

                addView(space(dp(12)))
                addView(metricRow("ORE TOTALI", formatMinutes(minutes), "ORE CALEND.", formatHours(laborDayHours), "MANODOPERA", money(laborTotalCost)))
                addView(space(dp(10)))
                addView(metricRow("DOCUMENTI", canonicalDocs.total.toString(), "PREVENTIVI", estimates.toString(), "FOTO", photos.toString()))
                addView(space(dp(10)))
                addView(metricRow("PLANIMETRIE", plans.toString(), "DOC. CLIENTE", clientDocs.toString(), "DA PORTARE", tools.toBring.toString()))
                addView(space(dp(10)))
                addView(metricRow("APP. CLIENTE", clientNotes.toString(), "🔒 INTERNI", internalNotes.toString(), "GIORNI UOMO", dailyStore.laborDayWorkedPersonDaysForJobsite(jobsite.ge360Id).toString()))

                addView(View(this@JobsiteHubActivity).apply {
                    background = this@JobsiteHubActivity.brandRounded(
                        fill = Ge360Brand.Border,
                        stroke = Ge360Brand.Border,
                        radiusDp = 1,
                        strokeDp = 0
                    )
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(12)
                })

                addView(TextView(this@JobsiteHubActivity).apply {
                    text = "ECONOMIA CANTIERE"
                    textSize = 10.5f
                    letterSpacing = 0.07f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.PrimaryDark)
                    setPadding(0, 0, 0, dp(8))
                })
                addView(financeMetricRow(finance.income, finance.expense, finance.balance))

                if (laborTotalCost > 0.0) addView(TextView(this@JobsiteHubActivity).apply {
                    text = buildString {
                        append("👷 Manodopera gestionale: ").append(money(laborTotalCost))
                        if (laborDayCost > 0.0) append(" · da calendario ").append(money(laborDayCost))
                        append(" · separata dai movimenti Spese")
                    }
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                    setPadding(0, dp(8), 0, 0)
                })
                if (finance.movements > 0) addView(TextView(this@JobsiteHubActivity).apply {
                    text = "${finance.movements} movimenti economici collegati a questo cantiere"
                    textSize = 10.5f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(8), 0, 0)
                })
                if (canonicalDocs.total > 0) addView(TextView(this@JobsiteHubActivity).apply {
                    text = "Archivio unico · ${canonicalDocs.fir} FIR · ${canonicalDocs.invoices} fatture · ${canonicalDocs.ddt} DDT · conteggio senza doppioni"
                    textSize = 10f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(9), 0, 0)
                })
                if (fir.open > 0) addView(TextView(this@JobsiteHubActivity).apply {
                    text = "♻ ${fir.open} FIR ancora aperti"
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.PrimaryDark)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = this@JobsiteHubActivity.brandRounded(
                        fill = Ge360Brand.WarningSoft,
                        stroke = Ge360Brand.WarningSoft,
                        radiusDp = 12,
                        strokeDp = 0
                    )
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
            })
        })
    }

    private data class ActionSpec(val icon: String, val title: String, val subtitle: String, val action: () -> Unit)

    private fun actionRow(left: ActionSpec, right: ActionSpec): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(actionCard(left), LinearLayout.LayoutParams(0, dp(98), 1f).apply { marginEnd = dp(5); bottomMargin = dp(10) })
        addView(actionCard(right), LinearLayout.LayoutParams(0, dp(98), 1f).apply { marginStart = dp(5); bottomMargin = dp(10) })
    }

    private fun actionCard(spec: ActionSpec): View = MaterialCardView(this).apply {
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 17)
        isClickable = true
        isFocusable = true
        setOnClickListener { spec.action() }
        addView(LinearLayout(this@JobsiteHubActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            addView(TextView(this@JobsiteHubActivity).apply {
                text = "${spec.icon} ${spec.title}"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
            })
            addView(TextView(this@JobsiteHubActivity).apply {
                text = spec.subtitle
                textSize = 10f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(4), 0, 0)
                maxLines = 2
            })
        })
    }

    private fun openArchive() {
        openContext(JobsiteArchiveActivity::class.java)
    }

    private fun openContext(target: Class<*>) {
        startActivity(Intent(this, target).apply {
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, jobsite.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, jobsite.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, jobsite.code)
        })
    }

    private fun resolveJobsite(): JobsiteRecord? {
        val uuid = intent.getStringExtra(Ge360Contracts.EXTRA_JOBSITE_ID).orEmpty()
        val localId = intent.getLongExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, 0L)
        return jobsiteStore.list().firstOrNull {
            (uuid.isNotBlank() && it.ge360Id == uuid) || (localId > 0 && it.id == localId)
        }
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(3), dp(19), dp(3), dp(9))
        addView(TextView(this@JobsiteHubActivity).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.07f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
        })
        addView(TextView(this@JobsiteHubActivity).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(3), 0, 0)
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
        addView(TextView(this@JobsiteHubActivity).apply {
            text = label
            textSize = 8.8f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
        })
        addView(TextView(this@JobsiteHubActivity).apply {
            text = value
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
            setPadding(0, dp(2), 0, 0)
        })
    }

    private fun financeMetricRow(income: Double, expense: Double, balance: Double): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(financeMetric("ENTRATE", money(income), Ge360Brand.SuccessSoft, Ge360Brand.Success), LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginEnd = dp(4) })
        addView(financeMetric("USCITE", money(expense), Ge360Brand.WarningSoft, Ge360Brand.PrimaryDark), LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        val positive = balance >= 0.0
        addView(financeMetric("SALDO", money(balance), if (positive) Ge360Brand.ForestSoft else Ge360Brand.DangerSoft, if (positive) Ge360Brand.Forest else Ge360Brand.Danger), LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(4) })
    }

    private fun financeMetric(label: String, value: String, fill: Int, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = this@JobsiteHubActivity.brandRounded(fill = fill, stroke = fill, radiusDp = 13, strokeDp = 0)
        addView(TextView(this@JobsiteHubActivity).apply {
            text = value
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            gravity = Gravity.CENTER
            maxLines = 1
        })
        addView(TextView(this@JobsiteHubActivity).apply {
            text = label
            textSize = 8.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.TextSecondary)
            gravity = Gravity.CENTER
        })
    }

    private fun formatMinutes(minutes: Long): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "$h h" else "$h h ${m}m"
    }

    private fun formatHours(hours: Double): String = if (kotlin.math.abs(hours - hours.toInt()) < 0.0001) "${hours.toInt()} h" else String.format(Locale.ITALY, "%.1f h", hours)
    private fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, height) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
