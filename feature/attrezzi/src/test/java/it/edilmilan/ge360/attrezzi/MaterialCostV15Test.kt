package it.edilmilan.ge360.attrezzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic V15 source-level contracts. WRITTEN ONLY; tests NOT EXECUTED. */
class MaterialCostV15Test {
    private val mat = "11111111-1111-4111-8111-111111111111"
    private val job = "22222222-2222-4222-8222-222222222222"

    private fun plan(qty: Double = 3.5) = MaterialPlanRevisionV1(
        ge360Id = "plan1", materialGe360Id = mat, jobsiteGe360Id = job,
        quantity = qty, unit = "sacchi", confirmedAt = 123L
    )
    private fun budget() = MaterialBudgetRevisionV15(
        ge360Id = "budget1", planRevisionGe360Id = "plan1",
        materialGe360Id = mat, jobsiteGe360Id = job,
        unit = "sacchi", unitNetCents = 1234L, confirmedAt = 125L
    )
    private fun invoice(id: String, scope: String = job) = MaterialDocumentLineV13(
        ge360Id = id, documentGe360Id = "doc-$id", materialGe360Id = mat,
        jobsiteGe360Id = scope, documentKind = "FATTURA", quantity = 2.0,
        unit = "sacchi", confirmedAt = 100L
    )
    private fun price(id: String, row: String, cents: Long,
                      voidAt: Long = 0L) = MaterialInvoiceCostV15(
        ge360Id = id, invoiceLineGe360Id = row, netCents = cents,
        confirmedAt = 101L, voidedAt = voidAt
    )

    @Test fun decimalMoneyConvertsExactlyToCentsWithoutFloatIngestion() {
        assertEquals(1250L, MaterialCostRulesV15.parseEuroCents("12,50"))
        assertEquals(3L, MaterialCostRulesV15.parseEuroCents("0.03"))
        assertEquals(10000L, MaterialCostRulesV15.parseEuroCents("100"))
        assertTrue(runCatching { MaterialCostRulesV15.parseEuroCents("12,999") }.isFailure)
        assertTrue(runCatching { MaterialCostRulesV15.parseEuroCents("-5,00") }.isFailure)
        assertTrue(runCatching { MaterialCostRulesV15.parseEuroCents("NaN") }.isFailure)
    }

    @Test fun plannedBudgetUsesPreciselyTheApprovedPlanRevision() {
        assertEquals(4319L, MaterialCostRulesV15.plannedNetCents(plan(), budget()))
        assertTrue(runCatching {
            MaterialCostRulesV15.plannedNetCents(
                plan().copy(ge360Id = "new-plan"), budget())
        }.isFailure)
        assertTrue(runCatching {
            MaterialCostRulesV15.plannedNetCents(
                plan(), budget().copy(jobsiteGe360Id = "other"))
        }.isFailure)
    }

    @Test fun partialOrUnverifiableInvoiceLinesNeverCountAsComplete() {
        val lines = listOf(invoice("a"), invoice("b"))
        val costs = listOf(price("ca", "a", 1000L), price("cb", "b", 1500L))
        val result = MaterialCostRulesV15.coverage(
            lines, costs, mat, job, setOf("a"))
        assertEquals(1000L, result.confirmedNetCents)
        assertEquals(1, result.valuedLines)
        assertEquals(1, result.unvaluedLines)
        assertEquals(1, result.orphanCosts)
        assertFalse(result.completeForRecordedLines)
    }

    @Test fun voidedCostsAndDifferentJobsitesDoNotChangeThisScope() {
        val rows = listOf(invoice("a"), invoice("b", "another-job"))
        val costs = listOf(price("old", "a", 900L, voidAt = 102L),
            price("new", "a", 1200L), price("unrelated", "b", 1700L))
        val x = MaterialCostRulesV15.coverage(rows, costs, mat, job, setOf("a"))
        assertEquals(1200L, x.confirmedNetCents)
        assertEquals(1, x.valuedLines)
        assertEquals(0, x.unvaluedLines)
        assertTrue(x.completeForRecordedLines)
    }

    @Test fun amountRecordsAreImmutableAndNeverBecomePayments() {
        assertTrue(runCatching {
            MaterialInvoiceCostV15("a", "invoice1", -1L, 1L)
        }.isFailure)
        assertTrue(runCatching {
            MaterialBudgetRevisionV15("b", "", mat, job, "sacchi", 100L, 1L)
        }.isFailure)
        val cost = price("c", "a", 1250L)
        assertEquals(cost, MaterialCostStoreV15.costFromJson(
            MaterialCostStoreV15.costToJson(cost)))
        val b = budget()
        assertEquals(b, MaterialCostStoreV15.budgetFromJson(
            MaterialCostStoreV15.budgetToJson(b)))
    }
}
