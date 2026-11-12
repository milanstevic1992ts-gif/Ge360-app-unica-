package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomWorkCatalogCompletenessTest {
    @Test
    fun allStandardCatalogEntriesAreAvailableInEveryRoomType() {
        assertEquals(42, StandardWorkCatalog.entries.size)
        RoomTypes.ALL.forEach { type ->
            val works = RoomWorkCatalog.forType(type)
            assertEquals("$type: codici duplicati", works.size, works.map { it.code }.toSet().size)
            StandardWorkCatalog.entries.forEach { item ->
                assertTrue(
                    "$type: lavorazione di capitolato mancante ${item.code} · ${item.title}",
                    works.any { it.catalogCode == item.code }
                )
            }
        }
    }

    @Test
    fun specialistWorksAreSearchableWithoutAutomaticSelection() {
        val required = setOf(
            "AUTOLIV", "SPC-PACCHETTO", "PARQUET-LEV", "PARQUET-POSA",
            "STUCCO-Q3Q4", "RAS-ARM", "VELETTA", "CARTA-PARATI",
            "SERR-FINESTRA", "SERR-PORTAFINESTRA", "ISOL-ACUST",
            "IMP-IDRAULICO", "IMP-ELETTRICO", "IMP-SPOT",
            "BARR-VAP", "DEM-INTONACO", "CAROTAGGIO"
        )
        RoomTypes.ALL.forEach { type ->
            val works = RoomWorkCatalog.forType(type).associateBy { it.code }
            required.forEach { code ->
                assertTrue("$type: lavorazione specialistica assente: $code", code in works)
            }
            required.forEach { code ->
                assertFalse("$type: lavorazione extra preselezionata: $code", works.getValue(code).enabledByDefault)
            }
        }
    }

    @Test
    fun genericStandardEntriesDoNotInventQuantitiesOrPrices() {
        RoomTypes.ALL.forEach { type ->
            val room = SmartSurveyRoomV1(name = "Test $type", type = type)
            val generics = RoomWorkCatalog.forType(type).filter { it.code.startsWith("STD-CAP-") }
            assertTrue("$type: ci si aspetta voci di capitolato complementari", generics.isNotEmpty())
            generics.forEach { entry ->
                assertFalse("$type: la voce generica non deve essere preattivata", entry.enabledByDefault)
                if (entry.basis == QuantityBasis.MANUAL) {
                    assertEquals("$type: quantità non deducibile: ${entry.code}", 0.0, SmartSurveyDraftV1.quantity(room, entry), 0.0001)
                }
            }
        }
    }

    @Test
    fun bundleSpcWithSeparateWorksProducesDoubleCountWarning() {
        val room = SmartSurveyRoomV1(name = "Camera", type = RoomTypes.ROOM)
        room.workCodes += "SPC-PACCHETTO"
        room.workCodes += "SPC"
        val survey = SmartSurveySessionV1(rooms = mutableListOf(room))
        assertTrue(
            SmartSurveyDraftV1.checks(survey).any { it.contains("doppio conteggio") }
        )
    }
}
