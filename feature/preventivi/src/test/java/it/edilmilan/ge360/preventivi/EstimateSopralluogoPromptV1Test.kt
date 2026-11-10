package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateSopralluogoPromptV1Test {
    private val bagno = EstimateRoom(
        ge360Id = "bagno-1",
        estimateGe360Id = "quote-1",
        name = "Bagno",
        type = RoomTypes.BATHROOM,
        length = 2.0,
        width = 2.5,
        height = 2.7
    )

    private fun line(code: String, name: String, price: Double = 100.0) = EstimateItem(
        ge360Id = "item-$code",
        priceListCode = "ROOM/bagno-1/$code",
        room = "Bagno",
        section = "Bagno",
        name = name,
        unit = "corpo",
        unitPrice = price
    )

    @Test fun mutuallyExclusiveBathroomFloorSolutionsAreFlaggedWithoutChanges() {
        val first = line("DEM-PAV", "Demolizione pavimento")
        val second = line("POSA-SU-POSA", "Sovrapposizione")
        val quote = EstimateRecord(
            title = "Bagno", clientName = "Cliente", items = mutableListOf(first, second)
        )
        val before = quote.items.map { it.copy() }
        val warnings = EstimateSopralluogoPromptV1.checks(quote, listOf(bagno))
        assertTrue(warnings.any { it.contains("selezionate insieme") })
        assertEquals(before, quote.items)
    }

    @Test fun bathAndShowerRemovalTogetherRequireReview() {
        val quote = EstimateRecord(
            title = "Bagno", clientName = "Cliente",
            items = mutableListOf(
                line("DEM-VASCA", "Rimozione vasca"),
                line("DEM-DOCCIA", "Rimozione doccia")
            )
        )
        assertTrue(EstimateSopralluogoPromptV1.checks(quote, listOf(bagno))
            .any { it.contains("vasca e doccia") })
    }

    @Test fun missingMeasurementsAreReportedButManualSurveyIsAllowed() {
        val room = bagno.copy(length = 0.0, width = 0.0)
        val quote = EstimateRecord(
            title = "Bagno", clientName = "Cliente", items = mutableListOf(line("DEM-PAV", "Pavimento"))
        )
        val automatic = EstimateSopralluogoPromptV1.checks(quote, listOf(room))
        assertTrue(automatic.any { it.contains("dimensioni geometriche mancanti") })
        val manual = EstimateSopralluogoPromptV1.checks(quote, listOf(room)) {
            "Manuale · pavimento 5,00 m² · soffitto 5,00 m² · pareti 18,00 m²"
        }
        assertFalse(manual.any { it.contains("dimensioni geometriche mancanti") })
    }

    @Test fun optionalWorkDoesNotCountTowardsConfirmedPricing() {
        val optional = line("NICCHIA", "Nicchia extra").apply { optional = true }
        val quote = EstimateRecord(
            title = "Bagno", clientName = "Cliente", items = mutableListOf(optional)
        )
        val warnings = EstimateSopralluogoPromptV1.checks(quote, listOf(bagno))
        assertTrue(warnings.any { it.contains("Nessuna lavorazione inclusa") })
    }

    @Test fun zeroPricesAreFlaggedWithoutInventingValues() {
        val item = line("DEM-PAV", "Demolizione pavimento", price = 0.0)
        val quote = EstimateRecord(
            title = "Bagno", clientName = "Cliente", items = mutableListOf(item)
        )
        val warnings = EstimateSopralluogoPromptV1.checks(quote, listOf(bagno))
        assertTrue(warnings.any { it.contains("Prezzi non valorizzati") })
        assertEquals(0.0, item.unitPrice, 0.0)
    }
}
