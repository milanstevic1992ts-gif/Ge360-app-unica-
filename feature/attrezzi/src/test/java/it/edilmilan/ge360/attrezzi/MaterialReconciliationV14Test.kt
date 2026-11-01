package it.edilmilan.ge360.attrezzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level synthetic contracts for V14, NOT EXECUTED.
 * These do not read real invoices, DDTs, bank data or user databases.
 */
class MaterialReconciliationV14Test {
    private val material = "11111111-1111-4111-8111-111111111111"
    private val job = "22222222-2222-4222-8222-222222222222"

    private fun line(id: String, kind: String, qty: Double,
                     jobsite: String = job, unit: String = "sacchi",
                     doc: String = "document-$id") = MaterialDocumentLineV13(
        ge360Id = id, documentGe360Id = doc, materialGe360Id = material,
        jobsiteGe360Id = jobsite, documentKind = kind,
        quantity = qty, unit = unit, confirmedAt = 100L
    )

    private fun match(id: String, invoice: String, ddt: String,
                      quantity: Double, voided: Long = 0L) = MaterialInvoiceDdtMatchV14(
        ge360Id = id, invoiceLineGe360Id = invoice, ddtLineGe360Id = ddt,
        quantity = quantity, unit = "sacchi", confirmedAt = 200L, voidedAt = voided
    )

    @Test fun confirmedOverlapIsCountedOnceNotTwice() {
        val rows = listOf(line("i", "FATTURA", 10.0), line("d", "DDT", 7.0))
        val reconciliation = match("r1", "i", "d", 5.0)
        val available = MaterialReconciliationRulesV14.validate(rows, emptyList(), reconciliation)
        assertEquals(10.0, available.invoicedRemaining, 0.00001)
        assertEquals(7.0, available.deliveredRemaining, 0.00001)
        assertEquals(5.0, MaterialReconciliationRulesV14.matchedQuantity(
            rows, listOf(reconciliation), material, job, "sacchi"), 0.00001)
    }

    @Test fun oneInvoiceCanMatchSeveralDeliveriesWithoutExceedingInvoice() {
        val rows = listOf(line("i", "FATTURA", 10.0),
            line("d1", "DDT", 4.0), line("d2", "DDT", 8.0))
        val first = match("r1", "i", "d1", 4.0)
        val second = match("r2", "i", "d2", 6.0)
        MaterialReconciliationRulesV14.validate(rows, listOf(first), second)
        assertTrue(runCatching {
            MaterialReconciliationRulesV14.validate(rows, listOf(first),
                match("r3", "i", "d2", 7.0))
        }.isFailure)
    }

    @Test fun oneDeliveryCanBeAllocatedAcrossDifferentInvoices() {
        val rows = listOf(line("i1", "FATTURA", 3.0),
            line("i2", "FATTURA", 5.0), line("d", "DDT", 7.0))
        val first = match("r1", "i1", "d", 3.0)
        MaterialReconciliationRulesV14.validate(rows, listOf(first),
            match("r2", "i2", "d", 4.0))
        assertTrue(runCatching {
            MaterialReconciliationRulesV14.validate(rows, listOf(first),
                match("r3", "i2", "d", 5.0))
        }.isFailure)
    }

    @Test fun duplicateActivePairRejectedButVoidedHistoryAllowsReplacement() {
        val rows = listOf(line("i", "FATTURA", 10.0), line("d", "DDT", 10.0))
        val old = match("r1", "i", "d", 8.0)
        val replacement = match("r2", "i", "d", 7.0)
        assertTrue(runCatching {
            MaterialReconciliationRulesV14.validate(rows, listOf(old), replacement)
        }.isFailure)
        MaterialReconciliationRulesV14.validate(
            rows, listOf(old.copy(voidedAt = 201L)), replacement)
    }

    @Test fun crossJobsiteOrDifferentUnitsOrWrongKindsCannotBeMatched() {
        val invoice = line("i", "FATTURA", 10.0)
        val ddt = line("d", "DDT", 8.0)
        val claim = match("r", "i", "d", 2.0)
        assertTrue(runCatching {
            MaterialReconciliationRulesV14.validate(
                listOf(invoice, ddt.copy(jobsiteGe360Id = "other")), emptyList(), claim)
        }.isFailure)
        assertTrue(runCatching {
            MaterialReconciliationRulesV14.validate(
                listOf(invoice, ddt.copy(unit = "kg")), emptyList(), claim)
        }.isFailure)
        assertTrue(runCatching {
            MaterialReconciliationRulesV14.validate(
                listOf(invoice, ddt.copy(documentKind = "FATTURA")), emptyList(), claim)
        }.isFailure)
    }

    @Test fun originalRowsAreNotAlteredByTheComparisonRules() {
        val rows = listOf(line("i", "FATTURA", 5.0), line("d", "DDT", 5.0))
        val before = rows.toList()
        MaterialReconciliationRulesV14.validate(rows, emptyList(),
            match("r", "i", "d", 3.0))
        assertEquals(before, rows)
    }

    @Test fun voidedMatchesDoNotIncreaseReconciledQuantity() {
        val rows = listOf(line("i", "FATTURA", 5.0), line("d", "DDT", 5.0))
        assertEquals(0.0, MaterialReconciliationRulesV14.matchedQuantity(
            rows, listOf(match("r", "i", "d", 4.0, voided = 201L)),
            material, job, "sacchi"), 0.0)
    }
}
