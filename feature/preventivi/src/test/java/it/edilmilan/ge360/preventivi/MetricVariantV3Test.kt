package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract cases only. Do not execute without explicit QA authorization. */
class MetricVariantV3Test {
    private fun proposal() = MetricVariantV3(
        estimateGe360Id = "estimate-01",
        previousItemGe360Id = "item-01",
        previousBindingGe360Id = "binding-before",
        candidateBindingGe360Id = "binding-after",
        planGe360Id = "plan-01",
        roomGe360Id = "room-01",
        wallGe360Id = "wall-01",
        workCode = "CAP-03",
        surface = "PARETE_SINGOLA_NETTA",
        previousFingerprint = "a".repeat(64),
        candidateFingerprint = "b".repeat(64),
        previousQuantityM2 = 10.0,
        proposedQuantityM2 = 8.5,
        candidateGrossM2 = 10.0,
        candidateOpeningDeductionsM2 = 1.0,
        candidateAdditionalDeductionsM2 = 0.5,
        priorUnitPrice = 42.0,
        priorIncluded = true,
        reason = "Dimensione nicchia modificata"
    )

    @Test fun negativeQuantityDeltaIsPreservedAsInformativeAmount() {
        val variant = proposal()
        assertTrue(variant.valid())
        assertEquals(-1.5, variant.deltaQuantityM2(), 0.001)
        assertEquals(-63.0, variant.referenceDeltaEuro(), 0.001)
        assertEquals(MetricVariantV3.STATUS_PROPOSED, variant.status)
    }

    @Test fun unpricedOrExcludedHistoricalLineHasNoMonetaryDelta() {
        assertEquals(0.0, proposal().copy(priorIncluded = false).referenceDeltaEuro(), 0.001)
        assertEquals(0.0, proposal().copy(priorUnitPrice = 0.0).referenceDeltaEuro(), 0.001)
    }

    @Test fun rejectsSameBindingSameGeometryAndUnchangedQuantity() {
        assertFalse(proposal().copy(candidateBindingGe360Id = "binding-before").valid())
        assertFalse(proposal().copy(candidateFingerprint = "a".repeat(64)).valid())
        assertFalse(proposal().copy(proposedQuantityM2 = 10.0).valid())
        assertFalse(proposal().copy(reason = "breve").valid())
    }

    @Test fun reviewRequiresDocumentedReasonAndTimestamp() {
        assertFalse(proposal().copy(status = MetricVariantV3.STATUS_TECH_REVIEWED).valid())
        val p = proposal()
        assertTrue(p.copy(
            status = MetricVariantV3.STATUS_TECH_REVIEWED,
            reviewReason = "Misurazione verificata in cantiere",
            reviewedAt = p.createdAt
        ).valid())
    }

    @Test fun variationEventsAreAppendOnlyDecisionsNotPayments() {
        val event = MetricVariantEventV3(
            variantGe360Id = "variation-01",
            estimateGe360Id = "estimate-01",
            action = "TECH_REVIEWED",
            reason = "Misure ricontrollate sul posto"
        )
        assertTrue(event.valid())
        assertFalse(event.copy(action = "INVOICE_PAID").valid())
        assertFalse(event.copy(reason = "").valid())
    }
}
