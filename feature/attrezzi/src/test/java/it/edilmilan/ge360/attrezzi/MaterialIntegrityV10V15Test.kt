package it.edilmilan.ge360.attrezzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic cross-module contracts for consolidated V10–V15 integrity.
 * Test source is saved only, NEVER executed by this change.
 */
class MaterialIntegrityV10V15Test {
    private val job = "11111111-1111-4111-8111-111111111111"
    private val mat = "22222222-2222-4222-8222-222222222222"
    private fun line(id: String, kind: String, scope: String = job) =
        MaterialDocumentLineV13(id, "doc-$id", mat, scope, kind, 5.0,
            "sacchi", 100L)
    private fun doc(id: String, kind: String, exists: Boolean = true) =
        MaterialIntegrityV10V15.DocumentRef("doc-$id", kind, job, exists)
    private fun evaluate(
        lines: List<MaterialDocumentLineV13>,
        docs: List<MaterialIntegrityV10V15.DocumentRef>,
        matches: List<MaterialInvoiceDdtMatchV14> = emptyList(),
        costs: List<MaterialInvoiceCostV15> = emptyList(),
        budgets: List<MaterialBudgetRevisionV15> = emptyList(),
        plans: List<MaterialPlanRevisionV1> = emptyList()
    ) = MaterialIntegrityV10V15.inspect(lines, matches, costs, budgets,
        plans, docs, mapOf(mat to "sacchi"), setOf(job))
    private fun match(id: String, quantity: Double, invoice: String = "i",
                      delivery: String = "d") =
        MaterialInvoiceDdtMatchV14(id, invoice, delivery, quantity,
            "sacchi", 200L)

    @Test fun emptyLedgersAreInformationalNotFalselyVerified() {
        val summary = evaluate(emptyList(), emptyList())
        assertTrue(summary.checkedNothing)
        assertEquals(0, summary.totalProblems)
    }

    @Test fun sourcePdfMissingExcludesItFromTrustedEvidence() {
        val summary = evaluate(listOf(line("i", "FATTURA")),
            listOf(doc("i", "FATTURA", exists = false)))
        assertEquals(1, summary.sourceProblems)
        assertEquals(0, summary.validSourceLines)
    }

    @Test fun validMatchAndCostYieldNoIntegrityFault() {
        val summary = evaluate(listOf(line("i", "FATTURA"), line("d", "DDT")),
            listOf(doc("i", "FATTURA"), doc("d", "DDT")),
            listOf(match("m1", 3.0)),
            listOf(MaterialInvoiceCostV15("c1", "i", 1000, 210L)))
        assertEquals(2, summary.validSourceLines)
        assertEquals(0, summary.totalProblems)
        assertEquals(0, summary.unvaluedInvoices)
    }

    @Test fun overallocatedInvoiceIsFlaggedWithoutPublishingLineIds() {
        val summary = evaluate(listOf(line("i", "FATTURA"), line("d", "DDT")),
            listOf(doc("i", "FATTURA"), doc("d", "DDT")),
            listOf(match("m1", 3.0), match("m2", 4.0, delivery = "d")))
        assertEquals(1, summary.matchProblems)
        assertTrue(summary.totalProblems > 0)
    }

    @Test fun orphanedCostAndMissingMoneyAreBothVisible() {
        val summary = evaluate(listOf(line("i", "FATTURA")),
            listOf(doc("i", "FATTURA")),
            costs = listOf(MaterialInvoiceCostV15("c1", "obsolete", 500, 210L)))
        assertEquals(1, summary.costProblems)
        assertEquals(1, summary.unvaluedInvoices)
    }

    @Test fun obsoletePlanBudgetIsWarningNotFakeCurrentBudget() {
        val earlier = MaterialPlanRevisionV1("p1", mat, job, 10.0, "sacchi", 100L)
        val latest = MaterialPlanRevisionV1("p2", mat, job, 12.0, "sacchi", 200L)
        val b = MaterialBudgetRevisionV15("b1", "p1", mat, job,
            "sacchi", 1500, 205L)
        val summary = evaluate(emptyList(), emptyList(),
            budgets = listOf(b), plans = listOf(earlier, latest))
        assertEquals(0, summary.budgetProblems)
        assertEquals(1, summary.oldPlanBudgets)
    }

    @Test fun incompatibleBudgetUnitsAreAnIntegrityProblem() {
        val p = MaterialPlanRevisionV1("p1", mat, job, 10.0, "sacchi", 100L)
        val b = MaterialBudgetRevisionV15("b1", "p1", mat, job, "kg", 1500, 205L)
        val summary = evaluate(emptyList(), emptyList(),
            budgets = listOf(b), plans = listOf(p))
        assertEquals(1, summary.budgetProblems)
    }
}
