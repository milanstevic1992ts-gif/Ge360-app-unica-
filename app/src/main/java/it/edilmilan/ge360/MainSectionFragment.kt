package it.edilmilan.ge360

import android.app.Activity
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
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.agenda.AgendaActivity
import it.edilmilan.ge360.agenda.OperationalDayActivity
import it.edilmilan.ge360.agenda.WeeklySitePlannerActivity
import it.edilmilan.ge360.attrezzi.ToolsHubActivity
import it.edilmilan.ge360.backup.BackupActivity
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.clienti.ClientsActivity
import it.edilmilan.ge360.core.common.Ge360Contracts
import it.edilmilan.ge360.core.common.ui.Ge360Brand
import it.edilmilan.ge360.core.common.ui.applyGe360Card
import it.edilmilan.ge360.core.common.ui.applyGe360Primary
import it.edilmilan.ge360.core.common.ui.applyGe360Secondary
import it.edilmilan.ge360.core.common.ui.ge360AccentLine
import it.edilmilan.ge360.core.common.ui.ge360Badge
import it.edilmilan.ge360.core.common.ui.ge360Eyebrow
import it.edilmilan.ge360.fisco.FiscalActivity
import it.edilmilan.ge360.galleria.GalleryActivity
import it.edilmilan.ge360.giornata.CollaboratorsActivity
import it.edilmilan.ge360.giornata.DailyWorkActivity
import it.edilmilan.ge360.giornata.ExternalCollaboratorsActivity
import it.edilmilan.ge360.jarvis.JarvisActivity
import it.edilmilan.ge360.marketing.MarketingActivity
import it.edilmilan.ge360.messaggi.MessagesActivity
import it.edilmilan.ge360.planimetrie.FloorPlanEditorActivity
import it.edilmilan.ge360.planimetrie.AdvancedSurveyActivity
import it.edilmilan.ge360.planimetrie.FloorPlanStore
import it.edilmilan.ge360.planimetrie.FloorPlansActivity
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.scanner.ScannerActivity
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.sync.DataHubSyncCoordinator

/**
 * Pagine principali della shell GE360.
 * I moduli operativi restano Activity indipendenti: questa schermata li organizza,
 * mostra i dati essenziali e mantiene semplice la navigazione principale.
 */
class MainSectionFragment : Fragment() {
    private lateinit var content: LinearLayout
    private val section: String get() = arguments?.getString(ARG_SECTION).orEmpty()

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(118))
        }
        return ScrollView(requireContext()).apply {
            setBackgroundColor(Ge360Brand.Background)
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        content.removeAllViews()
        when (section) {
            SECTION_JOBSITES -> renderJobsites()
            SECTION_ESTIMATES -> renderEstimates()
            SECTION_FLOORPLANS -> renderFloorPlans()
            else -> renderMore()
        }
    }

    private fun renderJobsites() {
        title("Cantieri", "Il lavoro corrente al centro. Foto, documenti, giornate, costi e cronologia nello stesso posto.")
        primaryButton("Apri gestione cantieri") { open(JobsitesActivity::class.java) }

        val store = JobsiteStore(requireContext().applicationContext)
        val rows = try { store.list() } finally { store.close() }
        val active = rows.count { it.status == JobsiteStore.STATUS_ACTIVE }
        val scheduled = rows.count { it.status == JobsiteStore.STATUS_SCHEDULED }
        val inspections = rows.count { it.status == JobsiteStore.STATUS_INSPECTION }

        metricRow(
            Triple("IN CORSO", active.toString(), Ge360Brand.PrimarySoft),
            Triple("PROGRAMMATI", scheduled.toString(), BLUE_SOFT),
            Triple("SOPRALLUOGHI", inspections.toString(), Ge360Brand.ForestSoft)
        )

        sectionTitle("Da continuare")
        val priority = rows.filter {
            it.status == JobsiteStore.STATUS_ACTIVE ||
                it.status == JobsiteStore.STATUS_SCHEDULED ||
                it.status == JobsiteStore.STATUS_INSPECTION ||
                it.status == JobsiteStore.STATUS_ESTIMATE
        }.take(6)
        if (priority.isEmpty()) {
            emptyState("Nessun cantiere operativo", "I lavori attivi, programmati e i sopralluoghi compariranno qui.")
        } else {
            priority.forEach { job ->
                listCard(
                    title = job.displayTitle(),
                    subtitle = listOf(job.code, job.status, job.address).filter { it.isNotBlank() }.joinToString(" · "),
                    badge = job.status
                ) { openJobsite(job) }
            }
        }
    }

    private fun renderEstimates() {
        title("Preventivi", "Bozze, inviati, accettati, revisioni ed extra in un'unica area commerciale.")
        primaryButton("Apri tutti i preventivi") { open(EstimatesActivity::class.java) }

        val store = EstimateStore(requireContext().applicationContext)
        val rows = try { store.list() } finally { store.close() }
        val drafts = rows.count { it.status == EstimateStatuses.DRAFT || it.status == EstimateStatuses.REVIEW || it.status == EstimateStatuses.READY }
        val sent = rows.count { it.status == EstimateStatuses.SENT }
        val accepted = rows.count { it.status == EstimateStatuses.ACCEPTED || it.status == EstimateStatuses.IN_PROGRESS }

        metricRow(
            Triple("DA PREPARARE", drafts.toString(), Ge360Brand.SurfaceWarm),
            Triple("INVIATI", sent.toString(), BLUE_SOFT),
            Triple("ACCETTATI", accepted.toString(), Ge360Brand.SuccessSoft)
        )

        val variants = rows.count {
            it.documentType.contains("variante", true) || it.documentType.contains("extra", true)
        }
        infoStrip("Varianti / Extra", "$variants documenti collegati o commerciali") { open(EstimatesActivity::class.java) }

        sectionTitle("Recenti")
        if (rows.isEmpty()) {
            emptyState("Nessun preventivo", "Crea il primo preventivo dal modulo Preventivi o direttamente da un rilievo.")
        } else {
            rows.take(6).forEach { estimate ->
                listCard(
                    title = estimate.title.ifBlank { estimate.number.ifBlank { "Preventivo" } },
                    subtitle = listOf(estimate.number, estimate.clientName, estimate.workType).filter { it.isNotBlank() }.joinToString(" · "),
                    badge = estimate.status
                ) {
                    startActivity(Intent(requireContext(), EstimatesActivity::class.java)
                        .putExtra(EstimatesActivity.EXTRA_ESTIMATE_ID, estimate.ge360Id))
                }
            }
        }
    }

    private fun renderFloorPlans() {
        title("Rilievi", "Misura prima, decidi dopo: un rilievo può restare indipendente oppure diventare preventivo e cantiere.")
        // OpenPlan3D fa parte della stessa GE360 App Unica, non è una app a sé.
        // Da qui si apre direttamente il disegnatore: libero oppure guidato,
        // anche offline. I rilievi nativi esistenti restano disponibili.
        primaryButton("✏ RILIEVO LIBERO / GUIDATO") {
            startActivity(Intent(requireContext(), AdvancedSurveyActivity::class.java))
        }
        secondaryButton("+ Rilievo tradizionale") {
            startActivity(Intent(requireContext(), FloorPlansActivity::class.java).putExtra(FloorPlansActivity.EXTRA_CREATE_NEW, true))
        }

        val store = FloorPlanStore(requireContext().applicationContext)
        val rows = try { store.list() } finally { store.close() }
        val unlinked = rows.count { it.jobsiteId.isBlank() && it.estimateId.isBlank() }
        val linkedJob = rows.count { it.jobsiteId.isNotBlank() }
        val linkedEstimate = rows.count { it.estimateId.isNotBlank() }

        metricRow(
            Triple("NON COLLEGATI", unlinked.toString(), Ge360Brand.PrimarySoft),
            Triple("CANTIERI", linkedJob.toString(), Ge360Brand.SuccessSoft),
            Triple("PREVENTIVI", linkedEstimate.toString(), BLUE_SOFT)
        )

        sectionTitle("Ultimi rilievi")
        if (rows.isEmpty()) {
            emptyState("Nessun rilievo", "Puoi crearne uno anche prima di avere cliente, preventivo o cantiere.")
        } else {
            rows.take(7).forEach { plan ->
                val state = when {
                    plan.jobsiteId.isNotBlank() && plan.estimateId.isNotBlank() -> "Cantiere + preventivo"
                    plan.jobsiteId.isNotBlank() -> "Cantiere"
                    plan.estimateId.isNotBlank() -> "Preventivo"
                    else -> "Indipendente"
                }
                listCard(
                    title = plan.title,
                    subtitle = listOf(plan.kind, plan.clientName).filter { it.isNotBlank() }.joinToString(" · "),
                    badge = state
                ) {
                    startActivity(Intent(requireContext(), FloorPlanEditorActivity::class.java).putExtra(FloorPlanEditorActivity.EXTRA_PLAN_ID, plan.ge360Id))
                }
            }
        }

        secondaryButton("Vedi archivio rilievi") { open(FloorPlansActivity::class.java) }
    }

    private fun renderMore() {
        title("Altro", "Tutti gli strumenti operativi, ordinati senza affollare la Home.")
        sectionTitle("Lavoro e organizzazione")
        toolGrid(
            Tool("La mia giornata", "Priorità, appuntamenti e cose da fare", OperationalDayActivity::class.java),
            Tool("Settimana cantieri", "Squadra, lavori e preparazione materiali", WeeklySitePlannerActivity::class.java),
            Tool("Note e Agenda", "Taccuino, scadenze e appuntamenti", AgendaActivity::class.java),
            Tool("Clienti", "Contatti e follow-up", ClientsActivity::class.java),
            Tool("Giornata", "Ore e rapportino", DailyWorkActivity::class.java),
            Tool("Collaboratori", "Paghe e promemoria", CollaboratorsActivity::class.java),
            Tool("Collaboratori esterni", "Artigiani e pagamenti", ExternalCollaboratorsActivity::class.java),
            Tool("Spese", "Entrate, costi e saldo", ExpensesActivity::class.java),
            Tool("Scanner", "PDF, OCR e documenti", ScannerActivity::class.java)
        )
        sectionTitle("Cantiere")
        toolGrid(
            Tool("Attrezzi e materiali", "Pacchetti e furgone", ToolsHubActivity::class.java),
            Tool("Galleria", "Foto dei cantieri", GalleryActivity::class.java),
            Tool("Messaggi", "Template e prompt", MessagesActivity::class.java),
            Tool("Fisco", "Regimi e simulazioni", FiscalActivity::class.java)
        )
        sectionTitle("Sistema")
        toolGrid(
            Tool("Marketing", "Lead, KPI e SEO", MarketingActivity::class.java),
            Tool("Jarvis / IA", "Assistente GE360", JarvisActivity::class.java),
            Tool("Backup", "Backup e ripristino", BackupActivity::class.java),
            Tool("Centro diagnostico", "Integrità dati, documenti e backup", DiagnosticsActivity::class.java)
        )
        secondaryButton("Sincronizza Data Hub ora") {
            DataHubSyncCoordinator.syncAsync(requireContext().applicationContext, force = true) { result ->
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class Tool(val title: String, val subtitle: String, val target: Class<out Activity>)

    private fun toolGrid(vararg tools: Tool) {
        tools.toList().chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            pair.forEachIndexed { index, tool ->
                row.addView(toolCard(tool), LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                    if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
                    bottomMargin = dp(12)
                })
            }
            if (pair.size == 1) row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(row)
        }
    }

    private fun toolCard(tool: Tool): View = MaterialCardView(requireContext()).apply {
        applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 18)
        isClickable = true
        isFocusable = true
        setOnClickListener { open(tool.target) }
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(requireContext()).apply {
                text = tool.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
            })
            addView(TextView(requireContext()).apply {
                text = tool.subtitle
                textSize = 11f
                setTextColor(Ge360Brand.TextSecondary)
                setPadding(0, dp(4), 0, 0)
                maxLines = 2
            })
        })
    }

    private fun title(main: String, subtitle: String) {
        content.addView(requireContext().ge360Eyebrow("EDIL MILAN STEVIC"))
        content.addView(TextView(requireContext()).apply {
            text = main
            textSize = 29f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.ForestDark)
            setPadding(0, dp(3), 0, 0)
        })
        content.addView(requireContext().ge360AccentLine(46))
        content.addView(TextView(requireContext()).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Ge360Brand.TextSecondary)
            setPadding(0, dp(3), 0, dp(15))
        })
    }

    private fun primaryButton(label: String, action: () -> Unit) {
        content.addView(MaterialButton(requireContext()).apply {
            text = label
            applyGe360Primary()
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(17) })
    }

    private fun secondaryButton(label: String, action: () -> Unit) {
        content.addView(MaterialButton(requireContext()).apply {
            text = label
            applyGe360Secondary()
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(5) })
    }

    private fun metricRow(vararg cells: Triple<String, String, Int>) {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        cells.forEachIndexed { index, cell ->
            row.addView(metricCard(cell.first, cell.second, cell.third), LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                if (index > 0) marginStart = dp(6)
                if (index < cells.lastIndex) marginEnd = dp(6)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(19) })
    }

    private fun metricCard(label: String, value: String, background: Int): View = MaterialCardView(requireContext()).apply {
        applyGe360Card(fill = background, stroke = background, radiusDp = 16)
        strokeWidth = 0
        addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 23f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.ForestDark)
                gravity = Gravity.CENTER
            })
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ge360Brand.TextSecondary)
                gravity = Gravity.CENTER
                maxLines = 2
            })
        })
    }

    private fun sectionTitle(text: String) {
        content.addView(TextView(requireContext()).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.07f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ge360Brand.PrimaryDark)
            setPadding(dp(2), dp(12), 0, dp(10))
        })
    }

    private fun listCard(title: String, subtitle: String, badge: String, action: () -> Unit) {
        content.addView(MaterialCardView(requireContext()).apply {
            applyGe360Card(fill = Ge360Brand.Surface, stroke = Ge360Brand.Border, radiusDp = 17)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(14), dp(13), dp(14))
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(requireContext()).apply {
                        text = title
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Ge360Brand.ForestDark)
                        maxLines = 1
                    })
                    if (subtitle.isNotBlank()) addView(TextView(requireContext()).apply {
                        text = subtitle
                        textSize = 11.5f
                        setTextColor(Ge360Brand.TextSecondary)
                        setPadding(0, dp(3), 0, 0)
                        maxLines = 2
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(requireContext().ge360Badge(badge))
            })
        })
    }

    private fun infoStrip(title: String, subtitle: String, action: () -> Unit) {
        content.addView(MaterialCardView(requireContext()).apply {
            applyGe360Card(fill = Ge360Brand.PrimarySoft, stroke = Ge360Brand.PrimarySoft, radiusDp = 16)
            strokeWidth = 0
            isClickable = true
            setOnClickListener { action() }
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(requireContext()).apply {
                    text = title
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.PrimaryDark)
                })
                addView(TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
    }

    private fun emptyState(title: String, subtitle: String) {
        content.addView(MaterialCardView(requireContext()).apply {
            applyGe360Card(fill = Ge360Brand.SurfaceWarm, stroke = Ge360Brand.Border, radiusDp = 16)
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                addView(TextView(requireContext()).apply {
                    text = title
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Ge360Brand.ForestDark)
                })
                addView(TextView(requireContext()).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Ge360Brand.TextSecondary)
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
    }

    private fun open(target: Class<out Activity>) {
        startActivity(Intent(requireContext(), target))
    }

    private fun openJobsite(job: JobsiteRecord) {
        val intent = Intent(Ge360Contracts.ACTION_OPEN_JOBSITE).apply {
            setPackage(requireContext().packageName)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_ID, job.ge360Id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_LOCAL_ID, job.id)
            putExtra(Ge360Contracts.EXTRA_JOBSITE_CODE, job.code)
        }
        if (intent.resolveActivity(requireContext().packageManager) != null) startActivity(intent)
        else open(JobsitesActivity::class.java)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val SECTION_JOBSITES = "cantieri"
        const val SECTION_ESTIMATES = "preventivi"
        const val SECTION_FLOORPLANS = "rilievi"
        const val SECTION_MORE = "altro"
        private const val ARG_SECTION = "section"
        private val BLUE_SOFT = 0xFFEEF5FF.toInt()

        fun newInstance(section: String) = MainSectionFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
    }
}
