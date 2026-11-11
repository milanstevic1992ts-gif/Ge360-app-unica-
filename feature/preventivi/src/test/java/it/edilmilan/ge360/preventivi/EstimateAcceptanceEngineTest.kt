package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EstimateAcceptanceEngineTest {
    private fun quote() = EstimateRecord(
        id = 5,
        ge360Id = UUID.randomUUID().toString(),
        clientName = "Cliente di test",
        title = "Ristrutturazione bagno",
        items = mutableListOf(EstimateItem(
            ge360Id = "work-1", name = "Demolizione",
            quantity = 1.0, unitPrice = 650.0, included = true
        ))
    ).apply { recalculateEstimate(this) }

    @Test fun deterministicJobsiteIdIsStableAcrossRetries() {
        val estimateId = UUID.randomUUID().toString()
        val first = EstimateAcceptanceEngine.deterministicJobsiteId(estimateId)
        assertEquals(first, EstimateAcceptanceEngine.deterministicJobsiteId(estimateId))
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test fun twoQuotesCannotShareGeneratedJobsiteId() {
        assertNotEquals(
            EstimateAcceptanceEngine.deterministicJobsiteId("a"),
            EstimateAcceptanceEngine.deterministicJobsiteId("b")
        )
    }

    @Test fun acceptsCompleteQuoteOnly() {
        assertTrue(EstimateAcceptanceEngine.isAcceptable(quote()))
        val missingClient = quote().apply { clientName = "" }
        assertFalse(EstimateAcceptanceEngine.isAcceptable(missingClient))
        val noPrice = quote().apply { items.single().unitPrice = 0.0; recalculateEstimate(this) }
        assertFalse(EstimateAcceptanceEngine.isAcceptable(noPrice))
        val optionalOnly = quote().apply {
            items.single().optional = true
            recalculateEstimate(this)
        }
        assertFalse(EstimateAcceptanceEngine.isAcceptable(optionalOnly))
        val unsaved = quote().apply { id = 0L }
        assertFalse(EstimateAcceptanceEngine.isAcceptable(unsaved))
    }

    @Test fun rejectedAndExpiredQuotesCannotBeActivated() {
        assertFalse(EstimateAcceptanceEngine.isAcceptable(quote().apply { status = EstimateStatuses.REJECTED }))
        assertFalse(EstimateAcceptanceEngine.isAcceptable(quote().apply { status = EstimateStatuses.EXPIRED }))
    }
}
