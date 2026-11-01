package it.edilmilan.ge360.attrezzi

/**
 * V10–V15 integrity probe: purely observational, no SQL, network, payments,
 * modifications, exports or sensitive document identifiers in the result.
 *
 * This checks recorded relationships and internal arithmetic only. It cannot
 * certify that every supplier invoice has been scanned or that a PDF is genuine.
 */
object MaterialIntegrityV10V15 {
    data class DocumentRef(
        val id: String,
        val kind: String,
        val jobsiteId: String,
        val accessiblePdf: Boolean
    )

    data class Summary(
        val activeSourceLines: Int,
        val validSourceLines: Int,
        val sourceProblems: Int,
        val activeMatches: Int,
        val matchProblems: Int,
        val activeCosts: Int,
        val costProblems: Int,
        val unvaluedInvoices: Int,
        val budgetRevisions: Int,
        val budgetProblems: Int,
        val oldPlanBudgets: Int
    ) {
        val totalProblems: Int get() =
            sourceProblems + matchProblems + costProblems + budgetProblems
        val checkedNothing: Boolean get() =
            activeSourceLines == 0 && activeMatches == 0 &&
                activeCosts == 0 && budgetRevisions == 0
    }

    fun inspect(
        lines: List<MaterialDocumentLineV13>,
        matches: List<MaterialInvoiceDdtMatchV14>,
        costs: List<MaterialInvoiceCostV15>,
        budgets: List<MaterialBudgetRevisionV15>,
        plans: List<MaterialPlanRevisionV1>,
        documents: List<DocumentRef>,
        materialUnits: Map<String, String>,
        jobsiteIds: Set<String>
    ): Summary {
        val docById = documents.associateBy { it.id }
        val valid = lines.filter { line ->
            line.active && docById[line.documentGe360Id]?.let { doc ->
                doc.accessiblePdf && doc.kind.equals(line.documentKind, ignoreCase = true) &&
                    (doc.jobsiteId.isBlank() || doc.jobsiteId == line.jobsiteGe360Id)
            } == true &&
                materialUnits[line.materialGe360Id]?.trim()
                    ?.equals(line.unit.trim(), ignoreCase = true) == true &&
                (line.jobsiteGe360Id.isBlank() || line.jobsiteGe360Id in jobsiteIds)
        }
        val active = lines.filter { it.active }
        val validIds = valid.map { it.ge360Id }.toSet()
        val activeMatches = matches.filter { it.active }
        val acceptedMatches = mutableListOf<MaterialInvoiceDdtMatchV14>()
        val allLines = lines
        var invalidMatches = 0
        activeMatches.sortedWith(compareBy<MaterialInvoiceDdtMatchV14> {
            it.confirmedAt
        }.thenBy { it.ge360Id }).forEach { candidate ->
            if (candidate.invoiceLineGe360Id !in validIds ||
                candidate.ddtLineGe360Id !in validIds ||
                runCatching {
                    MaterialReconciliationRulesV14.validate(allLines, acceptedMatches, candidate)
                }.isFailure) {
                invalidMatches++
            } else acceptedMatches += candidate
        }
        val priceRows = costs.filter { it.active }
        val validInvoiceIds = valid.filter { it.documentKind == "FATTURA" }
            .map { it.ge360Id }.toSet()
        val seenPrices = mutableSetOf<String>()
        val validCostIds = mutableSetOf<String>()
        priceRows.forEach { cost ->
            if (cost.invoiceLineGe360Id in validInvoiceIds &&
                seenPrices.add(cost.invoiceLineGe360Id)) {
                validCostIds += cost.invoiceLineGe360Id
            }
        }
        val planById = plans.associateBy { it.ge360Id }
        val latestPlanByScope = plans.groupBy {
            it.materialGe360Id to it.jobsiteGe360Id.orEmpty()
        }.mapValues { (_, versions) ->
            versions.maxWithOrNull(compareBy<MaterialPlanRevisionV1> {
                it.confirmedAt
            }.thenBy { it.ge360Id })!!
        }
        var invalidBudgets = 0
        var staleBudgets = 0
        budgets.forEach { b ->
            val plan = planById[b.planRevisionGe360Id]
            if (plan == null || plan.materialGe360Id != b.materialGe360Id ||
                plan.jobsiteGe360Id.orEmpty() != b.jobsiteGe360Id ||
                !plan.unit.trim().equals(b.unit.trim(), ignoreCase = true) ||
                materialUnits[plan.materialGe360Id]?.trim()
                    ?.equals(plan.unit.trim(), ignoreCase = true) != true ||
                (b.jobsiteGe360Id.isNotBlank() && b.jobsiteGe360Id !in jobsiteIds) ||
                runCatching {
                    if (plan == null) error("No source plan")
                    MaterialCostRulesV15.plannedNetCents(plan, b)
                }.isFailure) {
                invalidBudgets++
            } else if (latestPlanByScope[b.materialGe360Id to b.jobsiteGe360Id]
                ?.ge360Id != plan.ge360Id) {
                staleBudgets++
            }
        }
        return Summary(
            activeSourceLines = active.size,
            validSourceLines = valid.size,
            sourceProblems = active.size - valid.size,
            activeMatches = activeMatches.size,
            matchProblems = invalidMatches,
            activeCosts = priceRows.size,
            costProblems = priceRows.size - validCostIds.size,
            unvaluedInvoices = (validInvoiceIds - validCostIds).size,
            budgetRevisions = budgets.size,
            budgetProblems = invalidBudgets,
            oldPlanBudgets = staleBudgets
        )
    }
}
