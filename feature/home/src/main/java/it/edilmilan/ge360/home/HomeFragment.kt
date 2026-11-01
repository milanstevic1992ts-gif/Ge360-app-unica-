package it.edilmilan.ge360.home

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import it.edilmilan.ge360.agenda.AgendaActivity
import it.edilmilan.ge360.agenda.AgendaCalendar
import it.edilmilan.ge360.agenda.AgendaEntry
import it.edilmilan.ge360.agenda.AgendaStore
import it.edilmilan.ge360.attrezzi.ToolsHubActivity
import it.edilmilan.ge360.backup.BackupActivity
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsitesActivity
import it.edilmilan.ge360.clienti.ClientsActivity
import it.edilmilan.ge360.core.common.Ge360Module
import it.edilmilan.ge360.fisco.FiscalActivity
import it.edilmilan.ge360.galleria.GalleryActivity
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.DailyWorkActivity
import it.edilmilan.ge360.giornata.DailyReport
import it.edilmilan.ge360.jarvis.JarvisActivity
import it.edilmilan.ge360.marketing.MarketingActivity
import it.edilmilan.ge360.messaggi.MessagesActivity
import it.edilmilan.ge360.preventivi.EstimateRecord
import it.edilmilan.ge360.preventivi.EstimateStatuses
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimatesActivity
import it.edilmilan.ge360.scanner.ScannerActivity
import it.edilmilan.ge360.spese.ExpensesActivity
import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceStore
import it.edilmilan.ge360.spese.FinanceType
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var jobsiteStore: JobsiteStore
    private lateinit var financeStore: FinanceStore
    private lateinit var estimateStore: EstimateStore
    private lateinit var dailyStore: DailyStore

    private val currency = NumberFormat.getCurrencyInstance(Locale.ITALY)

    private val modules = listOf(
        Ge360Module("clienti", "Clienti", "CRM, contatti, temperatura e follow-up", enabled = true),
        Ge360Module("cantieri", "Cantieri", "Centro operativo di ogni lavoro", enabled = true),
        Ge360Module("giornata", "Giornata", "Timer ore, costo manodopera e rapportino quotidiano", enabled = true),
        Ge360Module("agenda", "Note e Agenda", "Promemoria, attività, calendario e appunti offline", enabled = true),
        Ge360Module("preventivi", "Preventivi", "Prezzario, lavorazioni, totali e margine con ore reali", enabled = true),
        Ge360Module("spese", "Spese", "Entrate, uscite, costi e saldo per cantiere", enabled = true),
        Ge360Module("scanner", "Scanner", "PDF, OCR locale e passaggio diretto a Spese", enabled = true),
        Ge360Module("attrezzi", "Attrezzi e materiali", "Pacchetti lavoro e modalità PREPARA FURGONE", enabled = true),
        Ge360Module("galleria", "Galleria cantieri", "Foto prima, durante, dopo, problemi e Drive", enabled = true),
        Ge360Module("messaggi", "Messaggi e prompt", "Template rapidi, Cliente/Cantiere e WhatsApp", enabled = true),
        Ge360Module("fisco", "Fisco", "Confronto regimi, soglie, INPS e simulazioni", enabled = true),
        Ge360Module("marketing", "Marketing", "KPI, lead, SEO, tracking e approvazioni", enabled = true),
        Ge360Module("jarvis", "Jarvis / IA", "Analizza, propone e agisce solo dopo conferma", enabled = true),
        Ge360Module("backup", "Backup e sincronizzazione", "Backup unico, ultimi 10, Drive e ripristino", enabled = true)
    )

    private data class JobSnapshot(
        val job: JobsiteRecord,
        val estimate: EstimateRecord?,
        val income: Double,
        val directExpense: Double,
        val laborCost: Double,
        val laborMinutes: Long,
        val lastReport: DailyReport?,
        val dateProgress: Int?,
        val daysLeft: Long?,
        val health: Health
    ) {
        val totalCost: Double get() = directExpense + laborCost
        val margin: Double? get() = estimate?.finalTotal?.minus(totalCost)
        val marginPercent: Double? get() = estimate?.finalTotal
            ?.takeIf { it > 0.0 }
            ?.let { (it - totalCost) / it * 100.0 }
    }

    private data class Health(
        val label: String,
        val color: String,
        val background: String,
        val reason: String
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val appContext = requireContext().applicationContext
        jobsiteStore = JobsiteStore(appContext)
        financeStore = FinanceStore(appContext)
        estimateStore = EstimateStore(appContext)
        dailyStore = DailyStore(appContext)

        view.findViewById<TextView>(R.id.homeDate).text =
            SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(Date())
                .replaceFirstChar { it.uppercase() }

        view.findViewById<TextView>(R.id.openJobsites).setOnClickListener { openModule("cantieri") }
        view.findViewById<TextView>(R.id.openExpenses).setOnClickListener { openModule("spese") }

        renderModules(view)
        refreshDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (
            view != null &&
            ::jobsiteStore.isInitialized &&
            ::financeStore.isInitialized &&
            ::estimateStore.isInitialized &&
            ::dailyStore.isInitialized
        ) {
            refreshDashboard()
        }
    }

    override fun onDestroyView() {
        if (::jobsiteStore.isInitialized) jobsiteStore.close()
        if (::financeStore.isInitialized) financeStore.close()
        if (::estimateStore.isInitialized) estimateStore.close()
        if (::dailyStore.isInitialized) dailyStore.close()
        super.onDestroyView()
    }

    private fun refreshDashboard() {
        val root = view ?: return
        val jobsites = jobsiteStore.list()
        val counts = jobsiteStore.counts()
        val businessRows = financeStore.all()
            .filter { it.scope == FinanceScope.BUSINESS }
            .sortedWith(compareByDescending<FinanceRecord> { it.date }.thenByDescending { it.updatedAt })

        val monthKey = YearMonth.now().toString()
        val monthRows = businessRows.filter { it.date.startsWith(monthKey) }
        val estimatesByJob = estimateStore.list().filter { it.jobsiteId.isNotBlank() }.groupBy { it.jobsiteId }
        val financeByJob = businessRows.filter { it.jobsiteId.isNotBlank() }.groupBy { it.jobsiteId }
        val reportsByJob = dailyStore.reports(1000).groupBy { it.jobsiteId }

        val snapshots = jobsites.map { job ->
            buildSnapshot(
                job = job,
                estimates = estimatesByJob[job.ge360Id].orEmpty(),
                finance = financeByJob[job.ge360Id].orEmpty(),
                reports = reportsByJob[job.ge360Id].orEmpty()
            )
        }

        val active = counts[JobsiteStore.STATUS_ACTIVE] ?: 0
        val scheduled = counts[JobsiteStore.STATUS_SCHEDULED] ?: 0
        val totalIncome = businessRows.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
        val monthExpense = monthRows.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }
        val activeSnapshots = snapshots.filter {
            it.job.status == JobsiteStore.STATUS_ACTIVE || it.job.status == JobsiteStore.STATUS_SCHEDULED
        }
        val projectMargin = activeSnapshots.mapNotNull { it.margin }.sum()
        val critical = activeSnapshots.count { it.health.label == "CRITICO" }
        val attention = activeSnapshots.count { it.health.label == "ATTENZIONE" }

        root.findViewById<TextView>(R.id.homeStatus).text = buildString {
            append("$active in corso · $scheduled programmati")
            if (critical > 0) append(" · $critical critici")
            else if (attention > 0) append(" · $attention da controllare")
            else append(" · situazione regolare")
        }

        renderAgendaSummary(root)
        renderKpis(root, active, totalIncome, monthExpense, projectMargin, activeSnapshots)
        renderPriorityJobs(root, snapshots)
        renderAlerts(root, snapshots, businessRows)
        renderJobsiteStats(root, counts, jobsites.size)
        renderCashflow(root, businessRows)
        renderExpenseCategories(root, monthRows)
        renderRecentMovements(root, businessRows)
    }

    private fun buildSnapshot(
        job: JobsiteRecord,
        estimates: List<EstimateRecord>,
        finance: List<FinanceRecord>,
        reports: List<DailyReport>
    ): JobSnapshot {
        val estimate = preferredEstimate(estimates)
        val income = finance.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
        val directExpense = finance.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }
        val laborCost = dailyStore.laborCostForJobsite(job.ge360Id)
        val laborMinutes = dailyStore.totalMinutesForJobsite(job.ge360Id)
        val lastReport = reports.maxByOrNull { it.workDate }
        val dateProgress = timeProgress(job)
        val daysLeft = targetDaysLeft(job)
        val totalCost = directExpense + laborCost
        val health = calculateHealth(job, estimate, totalCost, lastReport, daysLeft)

        return JobSnapshot(
            job = job,
            estimate = estimate,
            income = income,
            directExpense = directExpense,
            laborCost = laborCost,
            laborMinutes = laborMinutes,
            lastReport = lastReport,
            dateProgress = dateProgress,
            daysLeft = daysLeft,
            health = health
        )
    }

    private fun preferredEstimate(rows: List<EstimateRecord>): EstimateRecord? {
        if (rows.isEmpty()) return null
        val approvedStatuses = setOf(
            EstimateStatuses.ACCEPTED,
            EstimateStatuses.IN_PROGRESS,
            EstimateStatuses.COMPLETED
        )
        return rows
            .filter { it.status in approvedStatuses && it.finalTotal > 0.0 }
            .maxByOrNull { it.updatedAt }
    }

    private fun calculateHealth(
        job: JobsiteRecord,
        estimate: EstimateRecord?,
        totalCost: Double,
        lastReport: DailyReport?,
        daysLeft: Long?
    ): Health {
        if (job.status == JobsiteStore.STATUS_PAUSED) {
            return Health("ATTENZIONE", "#A85A00", "#FFF4E8", "Cantiere sospeso")
        }

        if (job.status != JobsiteStore.STATUS_ACTIVE && job.status != JobsiteStore.STATUS_SCHEDULED) {
            return Health("INFO", "#667078", "#F2F4F6", "Stato ${job.status}")
        }

        val budget = estimate?.finalTotal ?: 0.0
        if (budget > 0.0 && totalCost > budget) {
            return Health("CRITICO", "#B42318", "#FFF0EE", "Costi oltre il valore del preventivo")
        }
        if (daysLeft != null && daysLeft < 0 && job.status == JobsiteStore.STATUS_ACTIVE) {
            return Health("CRITICO", "#B42318", "#FFF0EE", "Data prevista superata")
        }
        if (budget > 0.0 && totalCost >= budget * 0.85) {
            return Health("ATTENZIONE", "#A85A00", "#FFF4E8", "Costi oltre l'85% del preventivo")
        }

        val daysSinceReport = lastReport?.workDate?.let { safeDate(it) }
            ?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) }

        if (
            job.status == JobsiteStore.STATUS_ACTIVE &&
            (lastReport == null || (daysSinceReport != null && daysSinceReport >= 4))
        ) {
            return Health("ATTENZIONE", "#A85A00", "#FFF4E8", "Rapportino non aggiornato")
        }

        if (estimate == null) {
            return Health("DATI MANCANTI", "#667078", "#F2F4F6", "Manca un preventivo accettato collegato")
        }

        return Health("OK", "#277A49", "#EDF8F1", "Costi e scadenze sotto controllo")
    }

    /** Optional summary in the full dashboard, never in the protected bootstrap Home. */
    private fun renderAgendaSummary(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.agendaTodayContainer)
        container.removeAllViews()
        val rows = runCatching { AgendaStore(requireContext()).list() }.getOrElse {
            container.addView(createAlertCard(
                "Agenda da verificare", "I dati locali non sono stati modificati. Apri Note e Agenda per maggiori dettagli.",
                "agenda", "#FFF4E8"))
            return
        }
        val now = System.currentTimeMillis()
        val todayEnd = AgendaCalendar.nextDay(now)
        val due = rows.filter {
            it.kind != AgendaEntry.NOTE && !it.completed && it.dueAt > 0 && it.dueAt < todayEnd
        }.sortedBy { it.dueAt }
        container.addView(sectionLead("Note e Agenda", if (due.isEmpty())
            "Niente in scadenza oggi · apri per scrivere una nota" else
            due.size.toString() + " impegni da seguire oggi o in ritardo"))
        if (due.isEmpty()) {
            container.addView(createAlertCard(
                "Apri la tua agenda", "Nuove note, sopralluoghi e attività in un solo posto.",
                "agenda", "#EEF5FF"))
        } else {
            due.take(3).forEach { item ->
                container.addView(createAlertCard(
                    if (item.scope == AgendaEntry.PRIVATE) "Promemoria privato" else item.title,
                    if (item.dueAt < now) "Da controllare · già in scadenza" else
                        SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(item.dueAt)),
                    "agenda", if (item.priority == AgendaEntry.HIGH) "#FFF4E8" else "#EEF5FF"))
            }
            if (due.size > 3) container.addView(createAlertCard(
                "Altri impegni", (due.size - 3).toString() + " attività o appuntamenti da vedere",
                "agenda", "#EEF5FF"))
        }
    }

    private fun renderKpis(
        root: View,
        active: Int,
        totalIncome: Double,
        monthExpense: Double,
        projectMargin: Double,
        activeSnapshots: List<JobSnapshot>
    ) {
        val container = root.findViewById<LinearLayout>(R.id.dashboardKpiContainer)
        container.removeAllViews()
        val quotedJobs = activeSnapshots.count { it.estimate != null }

        container.addView(createKpiCard("CANTIERI", active.toString(), "attivi adesso", "cantieri", "#FFF4E8"))
        container.addView(createKpiCard("INCASSI", money(totalIncome), "movimenti aziendali", "spese", "#EEF8F1"))
        container.addView(createKpiCard("USCITE MESE", money(monthExpense), "mese corrente", "spese", "#FFF0F0"))
        container.addView(
            createKpiCard(
                "MARGINE",
                if (quotedJobs > 0) money(projectMargin) else "—",
                if (quotedJobs > 0) "$quotedJobs cantieri con preventivo" else "collega preventivi accettati",
                "preventivi",
                if (projectMargin >= 0) "#EEF5FF" else "#FFF0F0"
            )
        )
    }

    private fun renderPriorityJobs(root: View, snapshots: List<JobSnapshot>) {
        val container = root.findViewById<LinearLayout>(R.id.jobsiteFinanceContainer)
        container.removeAllViews()

        val relevant = snapshots
            .filter {
                it.job.status == JobsiteStore.STATUS_ACTIVE ||
                    it.job.status == JobsiteStore.STATUS_SCHEDULED ||
                    it.health.label == "CRITICO" ||
                    it.health.label == "ATTENZIONE"
            }
            .sortedWith(
                compareBy<JobSnapshot> {
                    when (it.health.label) {
                        "CRITICO" -> 0
                        "ATTENZIONE" -> 1
                        "DATI MANCANTI" -> 2
                        else -> 3
                    }
                }.thenBy {
                    when (it.job.status) {
                        JobsiteStore.STATUS_ACTIVE -> 0
                        JobsiteStore.STATUS_SCHEDULED -> 1
                        else -> 2
                    }
                }.thenBy { it.daysLeft ?: Long.MAX_VALUE }
            )
            .take(6)

        if (relevant.isEmpty()) {
            container.addView(emptyState("Nessun cantiere da mostrare", "I cantieri attivi e programmati compariranno qui con costi, ore, margine e scadenze."))
            return
        }

        relevant.forEachIndexed { index, snapshot ->
            if (index > 0) container.addView(space(dp(10)))
            container.addView(createJobHealthCard(snapshot))
        }
    }

    private fun renderAlerts(root: View, snapshots: List<JobSnapshot>, businessRows: List<FinanceRecord>) {
        val container = root.findViewById<LinearLayout>(R.id.alertsContainer)
        container.removeAllViews()

        val critical = snapshots.filter { it.health.label == "CRITICO" }
        val warnings = snapshots.filter { it.health.label == "ATTENZIONE" }
        val missing = snapshots.filter {
            it.health.label == "DATI MANCANTI" &&
                (it.job.status == JobsiteStore.STATUS_ACTIVE || it.job.status == JobsiteStore.STATUS_SCHEDULED)
        }
        val unassignedExpenses = businessRows.count { it.type == FinanceType.EXPENSE && it.jobsiteId.isBlank() }

        var added = 0

        critical.take(2).forEach {
            container.addView(createAlertCard("🔴  ${it.job.displayTitle()}", it.health.reason, "cantieri", "#FFF0EE"))
            added++
        }

        warnings.take(max(0, 3 - added)).forEach {
            container.addView(createAlertCard("🟠  ${it.job.displayTitle()}", it.health.reason, "cantieri", "#FFF4E8"))
            added++
        }

        if (added < 3 && missing.isNotEmpty()) {
            container.addView(
                createAlertCard(
                    "⚪  ${missing.size} cantier${if (missing.size == 1) "e" else "i"} senza preventivo accettato",
                    "Collega il preventivo per calcolare budget e margine reale.",
                    "preventivi",
                    "#F2F4F6"
                )
            )
            added++
        }

        if (added < 3 && unassignedExpenses > 0) {
            container.addView(
                createAlertCard(
                    "↗  $unassignedExpenses uscit${if (unassignedExpenses == 1) "a" else "e"} non associat${if (unassignedExpenses == 1) "a" else "e"}",
                    "Collegale ai cantieri: altrimenti il margine del lavoro risulta falsato.",
                    "spese",
                    "#EEF5FF"
                )
            )
            added++
        }

        if (added == 0) {
            container.addView(createAlertCard("✓  Nessuna urgenza evidente", "I dati registrati non mostrano scadenze superate o costi fuori controllo.", "cantieri", "#EEF8F1"))
        }
    }

    private fun renderJobsiteStats(root: View, counts: Map<String, Int>, total: Int) {
        val container = root.findViewById<LinearLayout>(R.id.jobsiteStatsContainer)
        container.removeAllViews()
        container.addView(sectionLead("Portafoglio lavori", "$total cantieri registrati"))

        val states = listOf(
            Triple(JobsiteStore.STATUS_ACTIVE, "#D96B00", "In lavorazione adesso"),
            Triple(JobsiteStore.STATUS_SCHEDULED, "#4E78A0", "In partenza"),
            Triple(JobsiteStore.STATUS_ESTIMATE, "#8A6A3F", "Preventivo aperto"),
            Triple(JobsiteStore.STATUS_INSPECTION, "#7C7F85", "Da sopralluogo"),
            Triple(JobsiteStore.STATUS_PAUSED, "#B54D4D", "Da sbloccare"),
            Triple(JobsiteStore.STATUS_COMPLETED, "#44845B", "Conclusi")
        )

        states.forEachIndexed { index, (status, color, subtitle) ->
            if (index > 0) container.addView(divider())
            container.addView(createStatusRow(status, subtitle, counts[status] ?: 0, max(total, 1), color))
        }
        container.setOnClickListener { openModule("cantieri") }
    }

    private fun renderCashflow(root: View, businessRows: List<FinanceRecord>) {
        val container = root.findViewById<LinearLayout>(R.id.cashflowContainer)
        container.removeAllViews()

        val now = YearMonth.now()
        val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
        val monthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ITALY)
        val totals = months.map { month ->
            val key = month.toString()
            val rows = businessRows.filter { it.date.startsWith(key) }
            val income = rows.filter { it.type == FinanceType.INCOME }.sumOf { it.amount }
            val expense = rows.filter { it.type == FinanceType.EXPENSE }.sumOf { it.amount }
            Triple(month, income, expense)
        }
        val maxValue = totals.maxOfOrNull { max(it.second, it.third) }?.coerceAtLeast(1.0) ?: 1.0

        container.addView(sectionLead("Entrate e uscite", "Ultimi 6 mesi · confronto movimenti aziendali"))

        if (totals.all { it.second == 0.0 && it.third == 0.0 }) {
            container.addView(emptyState("Dati insufficienti per il trend", "Il grafico inizierà a popolarsi con le prossime entrate e uscite aziendali."))
            return
        }

        totals.forEach { (month, income, expense) ->
            container.addView(
                createCashflowMonthRow(
                    month.format(monthFormatter).replaceFirstChar { it.uppercase() },
                    income,
                    expense,
                    maxValue
                )
            )
        }

        container.setOnClickListener { openModule("spese") }
    }

    private fun renderExpenseCategories(root: View, monthRows: List<FinanceRecord>) {
        val container = root.findViewById<LinearLayout>(R.id.categoryStatsContainer)
        container.removeAllViews()
        val expenses = monthRows.filter { it.type == FinanceType.EXPENSE }
        val total = expenses.sumOf { it.amount }

        container.addView(sectionLead("Dove stanno andando i soldi", "Questo mese · ${money(total)}"))

        val grouped = expenses
            .groupBy { it.category.ifBlank { "Altro" } }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(6)

        if (grouped.isEmpty()) {
            container.addView(emptyState("Nessuna uscita questo mese", "Le categorie appariranno qui appena registri una spesa aziendale."))
            return
        }

        val maxValue = grouped.maxOf { it.second }.coerceAtLeast(1.0)
        grouped.forEach { (category, value) -> container.addView(createCategoryRow(category, value, maxValue)) }
        container.setOnClickListener { openModule("spese") }
    }

    private fun renderRecentMovements(root: View, businessRows: List<FinanceRecord>) {
        val container = root.findViewById<LinearLayout>(R.id.recentMovementsContainer)
        container.removeAllViews()
        val rows = businessRows.take(5)

        if (rows.isEmpty()) {
            container.addView(emptyState("Ancora nessun movimento", "Entrate e uscite aziendali compariranno qui in ordine di data."))
            return
        }

        rows.forEachIndexed { index, record ->
            if (index > 0) container.addView(divider())
            container.addView(createMovementRow(record))
        }
        container.setOnClickListener { openModule("spese") }
    }

    private fun createJobHealthCard(snapshot: JobSnapshot): View {
        val context = requireContext()
        val estimate = snapshot.estimate
        val margin = snapshot.margin
        val marginPercent = snapshot.marginPercent

        return MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E2E4E7")
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { openModule("cantieri") }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15))

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    addView(TextView(context).apply {
                        text = snapshot.health.label
                        textSize = 11f
                        setTextColor(Color.parseColor(snapshot.health.color))
                        setTypeface(typeface, Typeface.BOLD)
                        background = roundedBackground(snapshot.health.background, 10f)
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                    })

                    addView(TextView(context).apply {
                        text = snapshot.job.status
                        textSize = 11f
                        setTextColor(Color.parseColor("#71767C"))
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                })

                addView(TextView(context).apply {
                    text = snapshot.job.displayTitle()
                    textSize = 18f
                    setTextColor(Color.parseColor("#17191C"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(11), 0, 0)
                })

                val subtitle = listOf(snapshot.job.code, snapshot.job.address).filter { it.isNotBlank() }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(Color.parseColor("#71767C"))
                        setPadding(0, dp(3), 0, 0)
                    })
                }

                addView(space(dp(13)))
                addView(metricGridRow("PREVENTIVO", estimate?.let { money(it.finalTotal) } ?: "—", "INCASSATO", money(snapshot.income)))
                addView(space(dp(10)))
                addView(metricGridRow("COSTI DIRETTI", money(snapshot.directExpense), "MANODOPERA", money(snapshot.laborCost)))
                addView(space(dp(10)))
                addView(
                    metricGridRow(
                        "MARGINE STIMATO",
                        margin?.let { money(it) } ?: "—",
                        "ORE LAVORATE",
                        formatMinutes(snapshot.laborMinutes),
                        leftValueColor = if ((margin ?: 0.0) < 0.0) "#B42318" else "#277A49"
                    )
                )

                snapshot.dateProgress?.let { progress ->
                    addView(space(dp(14)))
                    addView(TextView(context).apply {
                        text = buildString {
                            append("TEMPO CANTIERE · $progress%")
                            snapshot.daysLeft?.let {
                                append(
                                    when {
                                        it > 1 -> " · $it giorni alla scadenza"
                                        it == 1L -> " · 1 giorno alla scadenza"
                                        it == 0L -> " · scade oggi"
                                        else -> " · ${-it} giorni oltre la data prevista"
                                    }
                                )
                            }
                        }
                        textSize = 11f
                        setTextColor(Color.parseColor("#62676D"))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(progressBar(progress, if ((snapshot.daysLeft ?: 1) < 0) "#B42318" else "#D96B00"))
                }

                addView(TextView(context).apply {
                    text = snapshot.health.reason
                    textSize = 12f
                    setTextColor(Color.parseColor(snapshot.health.color))
                    setPadding(0, dp(11), 0, 0)
                    setTypeface(typeface, Typeface.BOLD)
                })

                val reportText = snapshot.lastReport?.let { "Ultimo rapportino: ${formatIsoDate(it.workDate)}" } ?: "Nessun rapportino giornaliero"
                addView(TextView(context).apply {
                    text = reportText
                    textSize = 11f
                    setTextColor(Color.parseColor("#7A7F85"))
                    setPadding(0, dp(3), 0, 0)
                })

                marginPercent?.let {
                    addView(TextView(context).apply {
                        text = "Margine teorico ${String.format(Locale.ITALY, "%.1f", it)}% sul preventivo"
                        textSize = 11f
                        setTextColor(Color.parseColor("#7A7F85"))
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            })
        }
    }

    private fun metricGridRow(
        leftLabel: String,
        leftValue: String,
        rightLabel: String,
        rightValue: String,
        leftValueColor: String = "#222529",
        rightValueColor: String = "#222529"
    ): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metricCell(leftLabel, leftValue, leftValueColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                metricCell(rightLabel, rightValue, rightValueColor),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) }
            )
        }
    }

    private fun metricCell(label: String, value: String, valueColor: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#7A7F85"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 16f
                setTextColor(Color.parseColor(valueColor))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun createKpiCard(label: String, value: String, subtitle: String, module: String, background: String): View {
        val context = requireContext()
        return MaterialCardView(context).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E2E4E7")
            setCardBackgroundColor(Color.parseColor(background))
            layoutParams = LinearLayout.LayoutParams(dp(164), dp(116)).apply { marginEnd = dp(10) }
            isClickable = true
            isFocusable = true
            setOnClickListener { openModule(module) }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15))
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Color.parseColor("#6B7076"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = value
                    textSize = if (value.length > 10) 19f else 25f
                    setTextColor(Color.parseColor("#15171A"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(7), 0, 0)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Color.parseColor("#60656B"))
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                })
            })
        }
    }

    private fun createStatusRow(title: String, subtitle: String, count: Int, total: Int, color: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = title
                        textSize = 14f
                        setTextColor(Color.parseColor("#25282C"))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = subtitle
                        textSize = 11f
                        setTextColor(Color.parseColor("#777C82"))
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                addView(TextView(context).apply {
                    text = count.toString()
                    textSize = 18f
                    setTextColor(Color.parseColor(color))
                    setTypeface(typeface, Typeface.BOLD)
                })
            })

            val percent = ((count.toDouble() / total.toDouble()) * 100.0).roundToInt()
            addView(progressBar(percent, color))
        }
    }

    private fun createCashflowMonthRow(label: String, income: Double, expense: Double, maxValue: Double): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    setTextColor(Color.parseColor("#30343A"))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(TextView(context).apply {
                    text = "Entrate ${money(income)} · Uscite ${money(expense)}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#70757B"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
            addView(valueBar(income / maxValue, "#3E8B5B"))
            addView(valueBar(expense / maxValue, "#C05757"))
        }
    }

    private fun createCategoryRow(category: String, value: Double, maxValue: Double): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = category
                    textSize = 13f
                    setTextColor(Color.parseColor("#30343A"))
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = money(value)
                    textSize = 13f
                    setTextColor(Color.parseColor("#30343A"))
                    setTypeface(typeface, Typeface.BOLD)
                })
            })
            addView(valueBar(value / maxValue, "#D96B00"))
        }
    }

    private fun createMovementRow(record: FinanceRecord): View {
        val context = requireContext()
        val income = record.type == FinanceType.INCOME
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))

            addView(TextView(context).apply {
                text = if (income) "↓" else "↑"
                textSize = 18f
                setTextColor(Color.parseColor(if (income) "#277A49" else "#B54D4D"))
                gravity = Gravity.CENTER
                background = roundedBackground(if (income) "#EEF8F1" else "#FFF0F0", 12f)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(11) }
                addView(TextView(context).apply {
                    text = record.category.ifBlank { if (income) "Entrata" else "Uscita" }
                    textSize = 14f
                    setTextColor(Color.parseColor("#25282C"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = listOf(record.jobsiteTitle, record.note, formatIsoDate(record.date)).filter { it.isNotBlank() }.take(2).joinToString(" · ")
                    textSize = 11f
                    setTextColor(Color.parseColor("#777C82"))
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                })
            })

            addView(TextView(context).apply {
                text = "${if (income) "+" else "-"}${money(record.amount)}"
                textSize = 14f
                setTextColor(Color.parseColor(if (income) "#277A49" else "#B54D4D"))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
    }

    private fun createAlertCard(title: String, subtitle: String, module: String, background: String): View {
        val context = requireContext()
        return MaterialCardView(context).apply {
            radius = dp(15).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor(background))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
            isClickable = true
            isFocusable = true
            setOnClickListener { openModule(module) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14))
                addView(TextView(context).apply {
                    text = title
                    textSize = 14f
                    setTextColor(Color.parseColor("#25282C"))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Color.parseColor("#666B71"))
                    setPadding(0, dp(4), 0, 0)
                })
            })
        }
    }

    private fun sectionLead(title: String, subtitle: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(7))
            addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(Color.parseColor("#222529"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#777C82"))
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun emptyState(title: String, subtitle: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTextColor(Color.parseColor("#35393E"))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#777C82"))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun progressBar(percent: Int, color: String): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
            addView(View(context).apply { background = roundedBackground(color, 4f) }, LinearLayout.LayoutParams(0, dp(6), percent.coerceIn(0, 100).toFloat()))
            addView(View(context).apply { background = roundedBackground("#ECEEF0", 4f) }, LinearLayout.LayoutParams(0, dp(6), (100 - percent.coerceIn(0, 100)).toFloat()))
        }
    }

    private fun valueBar(ratio: Double, color: String): View {
        val context = requireContext()
        val percent = (ratio.coerceIn(0.0, 1.0) * 100).roundToInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
            addView(View(context).apply { background = roundedBackground(color, 4f) }, LinearLayout.LayoutParams(0, dp(5), percent.coerceAtLeast(1).toFloat()))
            addView(View(context).apply { background = roundedBackground("#ECEEF0", 4f) }, LinearLayout.LayoutParams(0, dp(5), (100 - percent.coerceAtLeast(1)).coerceAtLeast(0).toFloat()))
        }
    }

    private fun divider(): View = View(requireContext()).apply {
        setBackgroundColor(Color.parseColor("#ECEEF0"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun space(height: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun roundedBackground(color: String, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setColor(Color.parseColor(color))
    }

    private fun renderModules(root: View) {
        val container = root.findViewById<LinearLayout>(R.id.moduleContainer)
        container.removeAllViews()
        modules.forEach { container.addView(createModuleCard(it)) }
    }

    private fun createModuleCard(module: Ge360Module): View {
        val context = requireContext()
        return MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#E4E6E8")
            setCardBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
            isClickable = module.enabled
            isFocusable = module.enabled
            setOnClickListener { if (module.enabled) openModule(module.id) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = module.title
                        textSize = 15f
                        setTextColor(Color.parseColor("#202327"))
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = module.subtitle
                        textSize = 12f
                        setTextColor(Color.parseColor("#74797F"))
                        setPadding(0, dp(3), 0, 0)
                        maxLines = 2
                    })
                })
                addView(TextView(context).apply {
                    text = "›"
                    textSize = 24f
                    setTextColor(Color.parseColor("#B85700"))
                    setPadding(dp(10), 0, 0, 0)
                })
            })
        }
    }

    private fun openModule(id: String) {
        val target = when (id) {
            "clienti" -> ClientsActivity::class.java
            "agenda" -> AgendaActivity::class.java
            "cantieri" -> JobsitesActivity::class.java
            "giornata" -> DailyWorkActivity::class.java
            "preventivi" -> EstimatesActivity::class.java
            "spese" -> ExpensesActivity::class.java
            "scanner" -> ScannerActivity::class.java
            "attrezzi" -> ToolsHubActivity::class.java
            "galleria" -> GalleryActivity::class.java
            "messaggi" -> MessagesActivity::class.java
            "fisco" -> FiscalActivity::class.java
            "marketing" -> MarketingActivity::class.java
            "jarvis" -> JarvisActivity::class.java
            "backup" -> BackupActivity::class.java
            else -> null
        } ?: return
        startActivity(Intent(requireContext(), target))
    }

    private fun timeProgress(job: JobsiteRecord): Int? {
        val start = safeDate(job.startDate) ?: return null
        val end = safeDate(job.targetEndDate) ?: return null
        val total = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1)
        val elapsed = ChronoUnit.DAYS.between(start, LocalDate.now())
        return ((elapsed.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun targetDaysLeft(job: JobsiteRecord): Long? =
        safeDate(job.targetEndDate)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }

    private fun safeDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.trim()) }.getOrNull()

    private fun formatIsoDate(value: String): String {
        val date = safeDate(value) ?: return value
        return runCatching { date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALY)) }.getOrDefault(value)
    }

    private fun formatMinutes(minutes: Long): String {
        if (minutes <= 0) return "0 h"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours h" else "$hours h ${rest}m"
    }

    private fun money(value: Double): String = currency.format(value)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
