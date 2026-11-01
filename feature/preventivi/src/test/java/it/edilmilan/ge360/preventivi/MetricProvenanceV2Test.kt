package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for the new metadata model. Prepared, NOT executed. */
class MetricProvenanceV2Test {
    private fun draft(binding: String = "binding-01") = MeasuredEstimateDraftV1(
        sourceBindingGe360Id = binding,
        planGe360Id = "plan-01",
        roomGe360Id = "room-01",
        wallGe360Id = "wall-01",
        roomLabel = "Bagno",
        workCode = "CAP-03",
        surface = "PARETE_SINGOLA_NETTA",
        geometryFingerprint = "b".repeat(64),
        measuredQuantityM2 = 8.0,
        grossM2 = 9.6,
        openingDeductionsM2 = 1.6,
        additionalDeductionM2 = 1.0,
        deductionReason = "Riquadro escluso"
    )

    @Test fun authorizedSourceRetainsIdentityAndDeduction() {
        val origin = MetricProvenanceV2.fromApprovedDraft("estimate-01", draft())
        assertTrue(origin.valid())
        assertEquals("estimate-01", origin.estimateGe360Id)
        assertEquals(draft().stableItemId(), origin.itemGe360Id)
        assertEquals(7.0, origin.reviewedQuantityM2, 0.001)
        assertEquals(1.6, origin.openingDeductionsM2, 0.001)
        assertEquals("wall-01", origin.wallGe360Id)
        assertEquals(MetricProvenanceV2.TRUST_VERIFIED, origin.trust)
    }

    @Test fun originCannotChangeSilentlyOrClaimInvalidGeometry() {
        val origin = MetricProvenanceV2.fromApprovedDraft("estimate-01", draft())
        assertFalse(origin.copy(geometryFingerprint = "").valid())
        assertFalse(origin.copy(roomGe360Id = "").valid())
        assertFalse(origin.copy(grossM2 = 1.0).valid())
        assertFalse(origin.copy(trust = "TRUSTED_FROM_NOTE").valid())
    }

    @Test fun legacyIsExplicitlyUnverifiedNotApproved() {
        val legacy = MetricProvenanceV2.fromApprovedDraft("estimate-01", draft())
            .copy(trust = MetricProvenanceV2.TRUST_LEGACY)
        assertTrue(legacy.valid())
        assertNotEquals(MetricProvenanceV2.TRUST_VERIFIED, legacy.trust)
    }

    @Test fun reviewEventsRequireAuditReasonAndFinitePrices() {
        val event = MetricReviewEventV2(
            estimateGe360Id = "estimate-01",
            itemGe360Id = "item-01",
            action = "PRICE_REVIEW",
            priorPrice = 0.0,
            newPrice = 42.0,
            wasIncluded = false,
            nowIncluded = true,
            reason = "Controllo misure e prezzo"
        )
        assertTrue(event.valid())
        assertFalse(event.copy(reason = "").valid())
        assertFalse(event.copy(newPrice = Double.NaN).valid())
        assertFalse(event.copy(action = "UNTRUSTED").valid())
    }
}
