package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts prepared for the QA gate. No tests were run for this commit. */
class MeasuredEstimateDraftTest {
    private fun request(
        binding: String = "binding-uuid",
        additional: Double = 0.0,
        reason: String = ""
    ) = MeasuredEstimateDraftV1(
        sourceBindingGe360Id = binding,
        planGe360Id = "plan-uuid",
        roomGe360Id = "room-uuid",
        wallGe360Id = null,
        roomLabel = "Cucina",
        workCode = "CAP-03",
        surface = "PARETI_NETTE",
        geometryFingerprint = "a".repeat(64),
        measuredQuantityM2 = 18.0,
        grossM2 = 20.0,
        openingDeductionsM2 = 2.0,
        additionalDeductionM2 = additional,
        deductionReason = reason
    )

    @Test fun manualDeductionRequiresReasonAndCannotExceedArea() {
        assertFalse(request(additional = 2.0).valid())
        assertFalse(request(additional = 18.0, reason = "Quota esclusa").valid())
        assertFalse(request(additional = -1.0, reason = "Quota esclusa").valid())
        assertTrue(request(additional = 2.0, reason = "Fascia esclusa").valid())
        assertEquals(16.0, request(additional = 2.0, reason = "Fascia esclusa")
            .reviewedQuantityM2(), 0.001)
    }

    @Test fun manualDeductionCannotUseUnroundedPrecision() {
        assertFalse(request(additional = 0.123, reason = "Detrazione").valid())
    }

    @Test fun stableItemIdIsRetrySafeButDifferentForDistinctSources() {
        assertEquals(request().stableItemId(), request().stableItemId())
        assertNotEquals(request().stableItemId(), request("other-uuid").stableItemId())
    }

    @Test fun wholeRoomWallsOverlapOneWallButNotFloorOrCeiling() {
        assertTrue(MeasuredEstimateDraftV1.surfacesOverlap(
            "PARETI", null, "PARETE_SINGOLA_NETTA", "wall-a"
        ))
        assertTrue(MeasuredEstimateDraftV1.surfacesOverlap(
            "PARETE_SINGOLA_LORDA", "wall-a", "PARETE_SINGOLA_NETTA", "wall-a"
        ))
        assertFalse(MeasuredEstimateDraftV1.surfacesOverlap(
            "PARETE_SINGOLA_LORDA", "wall-a", "PARETE_SINGOLA_NETTA", "wall-b"
        ))
        assertFalse(MeasuredEstimateDraftV1.surfacesOverlap(
            "PARETI", null, "SOFFITTO", null
        ))
    }

    @Test fun sourceMustBeVerifiableAndRoomScoped() {
        assertFalse(request().copy(geometryFingerprint = "").valid())
        assertFalse(request().copy(roomGe360Id = "").valid())
        assertFalse(request().copy(grossM2 = 16.0).valid())
        assertFalse(request().copy(surface = "PARETE_SINGOLA_LORDA").valid())
        assertTrue(request().copy(surface = "PARETE_SINGOLA_LORDA",
            wallGe360Id = "wall-a").valid())
    }

    @Test fun manualDuplicateOverrideNeedsWrittenRationale() {
        assertFalse(request().copy(manualOverlapAcknowledged = true).valid())
        assertTrue(request().copy(
            manualOverlapAcknowledged = true,
            manualOverlapReason = "Questa è una fascia indipendente"
        ).valid())
    }
}
