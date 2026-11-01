package it.edilmilan.ge360.centrocantiere

import android.content.Context
import it.edilmilan.ge360.attrezzi.MaterialInventoryStore
import it.edilmilan.ge360.attrezzi.MaterialDocumentEvidenceStoreV13
import it.edilmilan.ge360.attrezzi.MaterialDocumentEvidenceRulesV13
import it.edilmilan.ge360.attrezzi.MaterialPlanStore
import it.edilmilan.ge360.attrezzi.MaterialStockMovement
import it.edilmilan.ge360.attrezzi.MaterialVarianceRulesV1
import it.edilmilan.ge360.attrezzi.MaterialComparisonStatusV1
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.cantieri.JobsiteMilestone
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.CompensationAccrualStatus
import it.edilmilan.ge360.giornata.CompensationPaymentKind
import it.edilmilan.ge360.giornata.CompensationStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.spese.FinanceStore
import java.io.File
import java.util.Locale

/**
 * V12: optional and independent jobsite pulse.
 * All reads are scoped to an explicit GE360 jobsite UUID; there are no
 * automatic assignments, purchases, payments, pricing, or mutable snapshots.
 *
 * This is NOT a certification of completeness or a bank reconciliation.
 */
internal object JobsiteControlV12 {
    enum class Level { INFO, OK, ATTENZIONE, CRITICO }

    data class Signal(
        val title: String,
        val value: String,
        val detail: String,
        val level: Level = Level.INFO,
        val action: Action = Action.NONE
    )

    enum class Action { NONE, WORK, MATERIALS, EVIDENCE, DOCUMENTS, LABOR, COMPENSATIONS, COSTS, ESTIMATES }

    data class Pulse(val jobsiteId: String, val at: Long, val signals: List<Signal>) {
        val issues: Int get() = signals.count { it.level == Level.ATTENZIONE || it.level == Level.CRITICO }
        val critical: Int get() = signals.count { it.level == Level.CRITICO }
        val headline: String get() = when {
            critical > 0 -> "Interventi da verificare"
            issues > 0 -> "Attività da controllare"
            else -> "Nessuna criticità rilevata nei dati disponibili"
        }
    }

    /**
     * Unit-safe material comparison from a COMPLETE local movement ledger.
     * Loads = warehouse loads; they never imply a purchase or invoice.
     */
    data class MaterialPulse(
        val planned: Int,
        val comparable: Int,
        val overPlan: Int,
        val unitMismatch: Int,
        val withoutPlan: Int,
        val movements: Int
    )

    fun materialPulse(
        jobsiteId: String,
        plans: List<it.edilmilan.ge360.attrezzi.MaterialPlanRevisionV1>,
        movementHistory: List<MaterialStockMovement>,
        completeHistory: Boolean = true
    ): MaterialPulse {
        require(jobsiteId.isNotBlank())
        val scoped = plans.filter { it.jobsiteGe360Id == jobsiteId }
        val latest = scoped.groupBy { it.materialGe360Id }
            .mapValues { (_, versions) ->
                versions.maxWithOrNull(compareBy<it.edilmilan.ge360.attrezzi.MaterialPlanRevisionV1> {
                    it.confirmedAt
                }.thenBy { it.ge360Id })!!
            }
        val history = movementHistory.filter { it.jobsiteId == jobsiteId }
        val historyByMaterial = history.groupBy { it.materialGe360Id }
        val names = (latest.keys + historyByMaterial.keys).filter { it.isNotBlank() }.toSet()
        var comparable = 0
        var over = 0
        var mismatch = 0
        var unplanned = 0
        for (id in names) {
            val plan = latest[id]
            val moves = historyByMaterial[id].orEmpty()
            val unit = plan?.unit ?: moves.firstOrNull()?.unit.orEmpty()
            if (unit.isBlank()) continue
            val result = MaterialVarianceRulesV1.compare(
                materialGe360Id = id,
                unit = unit,
                plan = plan?.toQuantity(),
                movements = moves,
                jobsiteGe360Id = jobsiteId,
                completeHistory = completeHistory
            )
            when (result.status) {
                MaterialComparisonStatusV1.COMPARABLE -> {
                    comparable++
                    if ((result.consumptionDelta ?: 0.0) > 0.001) over++
                }
                MaterialComparisonStatusV1.UNIT_MISMATCH -> mismatch++
                MaterialComparisonStatusV1.NO_PLAN -> unplanned++
                MaterialComparisonStatusV1.PARTIAL_HISTORY -> Unit
            }
        }
        return MaterialPulse(latest.size, comparable, over, mismatch, unplanned, history.size)
    }

    /** Invoke only in a background worker; each source is closed independently. */
    fun read(context: Context, jobsite: JobsiteRecord): Pulse {
        require(jobsite.ge360Id.isNotBlank()) { "Cantiere senza UUID GE360" }
        val id = jobsite.ge360Id
        val signals = mutableListOf<Signal>()
        fun capture(title: String, action: Action, read: () -> Signal) {
            runCatching { read() }
                .onSuccess { signals += it }
                .onFailure {
                    signals += Signal(title, "Non disponibile",
                        "Lettura non riuscita; nessuna modifica effettuata",
                        Level.ATTENZIONE, action)
                }
        }

        capture("Lavorazioni", Action.WORK) {
            val workflow = JobsiteWorkflowStore(context)
            val jobs = JobsiteStore(context)
            try {
                val summary = workflow.summary(id)
                val progress = jobs.progressSummary(id)
                val pending = jobs.milestones(id).count {
                    it.status == JobsiteMilestone.STATUS_TO_REQUEST
                }
                val level = when {
                    summary.blocked > 0 -> Level.CRITICO
                    summary.todo > 0 || pending > 0 -> Level.ATTENZIONE
                    summary.total > 0 -> Level.OK
                    else -> Level.INFO
                }
                val value = if (summary.relevant == 0) "Da configurare"
                    else "${summary.completionPercent}% checklist"
                Signal("Lavorazioni", value,
                    "${summary.inProgress} in corso · ${summary.todo} da fare · ${summary.blocked} bloccate" +
                        (progress?.let { " · ${it.percent}% avanzamento ponderato" } ?: " · avanzamento non registrato") +
                        (if (pending > 0) " · $pending SAL da richiedere" else ""),
                    level, Action.WORK)
            } finally { workflow.close(); jobs.close() }
        }

        capture("Materiali", Action.MATERIALS) {
            val plans = MaterialPlanStore(context)
            val inventory = MaterialInventoryStore(context)
            try {
                val complete = inventory.hasCompleteMovementHistory()
                val pulse = materialPulse(id, plans.all(),
                    inventory.allMovementHistory(jobsiteGe360Id = id), completeHistory = complete)
                val level = when {
                    pulse.overPlan > 0 || pulse.unitMismatch > 0 -> Level.ATTENZIONE
                    pulse.planned > 0 && pulse.comparable > 0 -> Level.OK
                    else -> Level.INFO
                }
                Signal("Materiali", "${pulse.planned} piani confermati",
                    "${pulse.comparable} confrontabili · ${pulse.overPlan} consumi sopra piano" +
                        " · ${pulse.unitMismatch} unità incompatibili · ${pulse.withoutPlan} senza piano" +
                        " · ${pulse.movements} movimenti registrati" +
                        (if (complete) "" else " · storico non certificato completo"),
                    level, Action.MATERIALS)
            } finally { plans.close(); inventory.close() }
        }

        capture("Fatture / DDT materiali", Action.EVIDENCE) {
            val ledger = MaterialDocumentEvidenceStoreV13(context)
            val documents = DocumentStore(context)
            val inventory = MaterialInventoryStore(context)
            try {
                val entries = ledger.forJobsite(id)
                val docs = documents.list().associateBy { it.ge360Id }
                val mats = inventory.list().associateBy { it.ge360Id }
                val missingProof = entries.count {
                    val doc = docs[it.documentGe360Id]
                    doc == null ||
                        !doc.type.equals(it.documentKind, ignoreCase = true) ||
                        doc.pdfPath.isBlank() || !File(doc.pdfPath).isFile
                }
                val mismatch = entries.count {
                    val material = mats[it.materialGe360Id]
                    material == null || !material.unit.equals(it.unit, ignoreCase = true)
                }
                val invoices = entries.count { it.documentKind == "FATTURA" }
                val deliveries = entries.count { it.documentKind == "DDT" }
                val lineMap = entries.associateBy { it.ge360Id }
                val matched = ledger.matches().filter { it.active &&
                    lineMap[it.invoiceLineGe360Id]?.documentKind == "FATTURA" &&
                    lineMap[it.ddtLineGe360Id]?.documentKind == "DDT" }
                val invoicedMatchedIds = matched.map { it.invoiceLineGe360Id }.toSet()
                val ddtMatchedIds = matched.map { it.ddtLineGe360Id }.toSet()
                val invoicesWithoutMatch = entries.count {
                    it.documentKind == "FATTURA" && it.ge360Id !in invoicedMatchedIds
                }
                val ddtWithoutMatch = entries.count {
                    it.documentKind == "DDT" && it.ge360Id !in ddtMatchedIds
                }
                Signal("Fatture / DDT materiali", "${entries.size} righe confermate",
                    "$invoices da fatture · $deliveries da DDT (mai sommati)" +
                        " · ${matched.size} abbinamenti V14" +
                        " · $invoicesWithoutMatch fatture / $ddtWithoutMatch DDT senza abbinamento" +
                        " · $missingProof PDF/record da verificare" +
                        " · $mismatch materiali/unità da controllare",
                    if (missingProof > 0 || mismatch > 0) Level.ATTENZIONE
                    else Level.INFO,
                    Action.EVIDENCE)
            } finally { ledger.close(); documents.close(); inventory.close() }
        }

        capture("Documenti", Action.DOCUMENTS) {
            val docs = DocumentStore(context)
            val archive = JobsiteArchiveStore(context)
            val gallery = GalleryStore(context)
            try {
                val scans = docs.list().filter { it.jobsiteId == id }
                val files = archive.files(id)
                val missingScans = scans.count { it.pdfPath.isNotBlank() && !File(it.pdfPath).isFile }
                val missingFiles = files.count { it.filePath.isNotBlank() && !File(it.filePath).isFile }
                val missing = missingScans + missingFiles
                val notLinked = scans.count { it.pdfPath.isBlank() } +
                    files.count { it.filePath.isBlank() }
                Signal("Documenti", "${scans.size + files.size} schede collegate",
                    "$missing file locali non trovati · $notLinked senza percorso allegato" +
                        " · ${gallery.summary(id).total} foto registrate" +
                        " · non certifica gli obblighi documentali",
                    if (missing > 0 || notLinked > 0) Level.ATTENZIONE else Level.INFO,
                    Action.DOCUMENTS)
            } finally { docs.close(); archive.close(); gallery.close() }
        }

        capture("Collaboratori", Action.LABOR) {
            val daily = DailyStore(context)
            val comp = CompensationStore(context)
            try {
                val people = daily.laborBreakdownForJobsite(id)
                val scopedAgreements = comp.agreements().filter { it.jobsiteGe360Id == id }
                val agreementIds = scopedAgreements.map { it.ge360Id }.toSet()
                val accruals = comp.accruals().filter { it.agreementGe360Id in agreementIds &&
                    it.status == CompensationAccrualStatus.CONFIRMED }
                val accrualIds = accruals.map { it.ge360Id }.toSet()
                val events = comp.payments().filter { it.accrualGe360Id in accrualIds }
                val recorded = events.count { it.kind == CompensationPaymentKind.PAYMENT }
                val reversed = events.count { it.kind == CompensationPaymentKind.REVERSAL }
                val unrated = people.count { it.hasMissingRate }
                Signal("Collaboratori", "${people.size} persone con ore",
                    "${scopedAgreements.size} accordi compensi associati" +
                        " · ${accruals.size} maturati confermati" +
                        " · $recorded pagamenti registrati ($reversed storni)" +
                        " · $unrated tariffe ore incomplete" +
                        " · registrazioni non equiparate a movimenti bancari",
                    if (unrated > 0) Level.ATTENZIONE else Level.INFO, Action.COMPENSATIONS)
            } finally { daily.close(); comp.close() }
        }

        capture("Costi e incassi", Action.COSTS) {
            val finance = FinanceStore(context)
            val daily = DailyStore(context)
            try {
                val recorded = finance.jobsiteSummary(id)
                val labor = daily.laborCostForJobsite(id)
                fun euro(n: Double) = String.format(Locale.ITALY, "%,.2f €", n)
                Signal("Costi e incassi", "${recorded.movements} movimenti Spese",
                    "Entrate ${euro(recorded.income)} · Uscite ${euro(recorded.expense)}" +
                        " · saldo movimenti ${euro(recorded.balance)}" +
                        " · costo ore gestionale ${euro(labor)} (separato, non sommato)",
                    Level.INFO, Action.COSTS)
            } finally { finance.close(); daily.close() }
        }

        capture("Preventivi", Action.ESTIMATES) {
            val store = EstimateStore(context)
            try {
                val linked = store.forJobsite(id)
                Signal("Preventivi", "${linked.size} collegati",
                    if (linked.isEmpty()) "Nessun preventivo collegato: può essere un cantiere indipendente"
                    else "Consulta gli stati e le revisioni nel modulo Preventivi",
                    Level.INFO, Action.ESTIMATES)
            } finally { store.close() }
        }

        return Pulse(id, System.currentTimeMillis(), signals.toList())
    }
}
