package it.edilmilan.ge360.attrezzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V13 synthetic source contracts. Only written, NOT RUN; no device, SQLite,
 * personal documents, account or network is used.
 */
class MaterialDocumentEvidenceV13Test {
    private val material = "11111111-1111-4111-8111-111111111111"
    private val job = "22222222-2222-4222-8222-222222222222"
    private val unrelatedJob = "33333333-3333-4333-8333-333333333333"
    private fun line(
        id: String, kind: String, quantity: Double,
        site: String = job, unit: String = "sacchi", voidedAt: Long = 0L
    ) = MaterialDocumentLineV13(
        ge360Id = id, documentGe360Id = "doc-" + id,
        materialGe360Id = material, jobsiteGe360Id = site,
        documentKind = kind, quantity = quantity, unit = unit,
        confirmedAt = 100L, voidedAt = voidedAt
    )

    @Test fun invoiceAndDdtStayInIndependentQuantityMeasures() {
        val totals = MaterialDocumentEvidenceRulesV13.totals(
            listOf(line("1", "FATTURA", 5.0), line("2", "DDT", 7.0)),
            material, job, "sacchi")
        assertEquals(5.0, totals.invoiced, 0.00001)
        assertEquals(7.0, totals.delivered, 0.00001)
        assertEquals(1, totals.invoiceLines)
        assertEquals(1, totals.ddtLines)
    }

    @Test fun voidedAndOtherJobLinesNeverIncreaseAmounts() {
        val totals = MaterialDocumentEvidenceRulesV13.totals(
            listOf(line("1", "FATTURA", 4.0, voidedAt = 101L),
                line("2", "FATTURA", 9.0, site = unrelatedJob),
                line("3", "DDT", 3.0)),
            material, job, "sacchi")
        assertEquals(0.0, totals.invoiced, 0.0)
        assertEquals(3.0, totals.delivered, 0.0)
    }

    @Test fun mismatchedUnitsCannotSilentlyAddIntoTheSameQuantity() {
        val totals = MaterialDocumentEvidenceRulesV13.totals(
            listOf(line("1", "FATTURA", 5.0),
                line("2", "FATTURA", 100.0, unit = "kg")),
            material, job, "sacchi")
        assertEquals(5.0, totals.invoiced, 0.0)
        assertEquals(1, totals.invalidUnits)
    }

    @Test fun malformedEvidenceCannotBecomeAConfirmedQuantity() {
        assertTrue(runCatching { line("1", "FATTURA", Double.NaN) }.isFailure)
        assertTrue(runCatching { line("1", "FATTURA", -4.0) }.isFailure)
        assertTrue(runCatching { line("1", "SCONTRINO", 3.0) }.isFailure)
        assertTrue(runCatching { line("", "DDT", 2.0) }.isFailure)
    }

    @Test fun jsonRoundTripPreservesOriginalUuidAndVoidHistory() {
        val original = line("record-1", "FATTURA", 6.0, voidedAt = 200L)
        val recovered = MaterialDocumentEvidenceStoreV13.parse(
            MaterialDocumentEvidenceStoreV13.toJson(original))
        assertEquals(original, recovered)
        assertFalse(recovered.active)
    }
}
