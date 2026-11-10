package it.edilmilan.ge360.planimetrie

import it.edilmilan.ge360.preventivi.EstimateRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyEstimateTransferV1Test {
    private fun sample() = JSONObject().apply {
        put("schema", SurveyEstimateTransferV1.SCHEMA)
        put("status", "PROCESSED")
        put("planId", "plan-001")
        put("planName", "Bagno")
        put("version", 3)
        put("fingerprint", "abc123-1d")
        put("rooms", JSONArray().put(JSONObject().apply {
            put("roomId", "room-001")
            put("name", "Bagno")
            put("quality", "OK")
            put("confidence", 0.98)
            put("floorAreaM2", 5.55)
            put("netWallAreaM2", 19.4)
            put("skirtingM", 4.3)
        }))
    }

    @Test fun draftItemsAreExcludedAndDoNotAffectPrice() {
        val source = SurveyEstimateTransferV1.parse(sample().toString())
        val rows = SurveyEstimateTransferV1.buildDraftItems(source)
        assertEquals(3, rows.size)
        assertTrue(rows.all { !it.included && !it.optional })
        assertTrue(rows.all { it.unitPrice == 0.0 && it.total == 0.0 })
        assertTrue(rows.all { it.internalNote.contains("plan=plan-001") })
        assertEquals(5.55, rows.first { it.name.contains("pavimento") }.quantity, 0.001)
        val quote = EstimateRecord(items = rows.toMutableList())
        assertTrue(SurveyEstimateTransferV1.containsPlan(quote, "plan-001"))
        assertFalse(SurveyEstimateTransferV1.containsPlan(quote, "plan-002"))
    }

    @Test fun rejectedOrUnversionedSourceCannotBeImported() {
        val review = sample().put("status", "NEEDS_REVIEW")
        assertTrue(runCatching {
            SurveyEstimateTransferV1.parse(review.toString())
        }.isFailure)
        val missing = sample().put("version", 0)
        assertTrue(runCatching {
            SurveyEstimateTransferV1.parse(missing.toString())
        }.isFailure)
    }

    @Test fun invalidOrNegativeAreasAreRejected() {
        val raw = sample()
        raw.getJSONArray("rooms").getJSONObject(0).put("floorAreaM2", -5)
        assertTrue(runCatching {
            SurveyEstimateTransferV1.parse(raw.toString())
        }.isFailure)
    }

    @Test fun supportsNonEstimatedSurveyAndWarnsIfLowConfidence() {
        val raw = sample()
        raw.getJSONArray("rooms").getJSONObject(0).put("confidence", 0.55)
        val transfer = SurveyEstimateTransferV1.parse(raw.toString())
        assertTrue(SurveyEstimateTransferV1.reviewWarnings(transfer)
            .any { it.contains("affidabilità") })
    }

    @Test fun noPricesEverAppearInTheHandoffItems() {
        val raw = sample()
        raw.put("customerPrice", 1499.99)
        val transfer = SurveyEstimateTransferV1.parse(raw.toString())
        val rows = SurveyEstimateTransferV1.buildDraftItems(transfer)
        assertTrue(rows.all { it.unitPrice == 0.0 && !it.included })
    }
}
