package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSurveyCatalogCoverageTest {
    @Test fun everyTypeContainsEveryStandardCapitolatoEntry() {
        assertEquals(42, StandardWorkCatalog.entries.size)
        RoomTypes.ALL.forEach { type ->
            val templates = RoomWorkCatalog.forType(type)
            val standardCodes = templates.mapNotNull { it.standardSourceCode }.toSet()
            StandardWorkCatalog.entries.forEach { entry ->
                assertTrue("$type: voce mancante ${entry.code}", entry.code in standardCodes)
            }
            assertEquals("$type: codici duplicati", templates.size, templates.map { it.code }.distinct().size)
        }
    }

    @Test fun detailedWorksAreReachableWithoutAutomaticSelection() {
        val expected = listOf(
            "SPEC-DEM-INTONACO", "SPEC-DEM-MALTE", "SPEC-RAS-ARMATA",
            "SPEC-STUCCO-Q2", "SPEC-STUCCO-Q3", "SPEC-STUCCO-Q4",
            "SPEC-PITT-LAV-COLORE", "SPEC-CART-VELETTA",
            "SPEC-ISOL-BARRIERA-VAPORE", "SPEC-PAV-AUTOLIV",
            "SPEC-PAV-LEV-PARQUET", "SPEC-CARTA-PARATI",
            "SPEC-RIP-SPALLETTA-FIN", "SPEC-IMP-ASSIST-IDR"
        )
        RoomTypes.ALL.forEach { type ->
            val indexed = RoomWorkCatalog.forType(type).associateBy { it.code }
            expected.forEach { code ->
                assertTrue("$type: manca $code", code in indexed)
                assertFalse("$type: lavoro preselezionato $code", indexed.getValue(code).enabledByDefault)
            }
        }
        assertTrue(SpecializedWorkCatalog.entries.size >= 30)
    }

    @Test fun unmeasuredGenericWorksDoNotInventQuantitiesOrPrices() {
        val general = RoomWorkCatalog.forType(RoomTypes.ROOM).first {
            it.code == "CAT-CAP-21"
        }
        assertEquals(QuantityBasis.BODY, general.basis)
        val plaster = RoomWorkCatalog.forType(RoomTypes.ROOM).first { it.code == "SPEC-RIP-SPALLETTA-FIN" }
        assertEquals(QuantityBasis.MANUAL, plaster.basis)
        val room = SmartSurveyRoomV1(name = "Camera", type = RoomTypes.ROOM).apply {
            floorManual = 12.0; wallsManual = 36.0
            workCodes += plaster.code
        }
        assertEquals(0.0, SmartSurveyDraftV1.quantity(room, plaster), 0.001)
        val estimate = SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertEquals(0.0, estimate.finalTotal, 0.001)
    }

    @Test fun overlappingFinishesProduceReviewWarningNotAutomaticDeletion() {
        val room = SmartSurveyRoomV1(name = "Camera", type = RoomTypes.ROOM).apply {
            floorManual = 12.0; wallsManual = 36.0
            workCodes.addAll(listOf("SPEC-STUCCO-Q2", "SPEC-STUCCO-Q3",
                "SPEC-PITT-LAV-BIANCA", "SPEC-PITT-LAV-COLORE", "PITTURA"))
        }
        val notes = SmartSurveyDraftV1.checks(SmartSurveySessionV1(
            clientName = "Cliente", title = "Intervento", rooms = mutableListOf(room)
        ))
        assertTrue(notes.any { it.contains("Q2/Q3/Q4") })
        assertTrue(notes.any { it.contains("più cicli di pittura") })
        assertTrue(notes.any { it.contains("doppio conteggio") })
        assertTrue("SPEC-STUCCO-Q2" in room.workCodes)
        assertTrue("SPEC-STUCCO-Q3" in room.workCodes)
    }
}
