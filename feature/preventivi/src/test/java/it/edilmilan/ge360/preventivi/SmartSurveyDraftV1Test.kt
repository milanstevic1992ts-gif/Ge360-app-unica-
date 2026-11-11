package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SmartSurveyDraftV1Test {
    private fun bagno() = SmartSurveyRoomV1(
        name = "Bagno", type = RoomTypes.BATHROOM,
        length = 2.0, width = 3.0, height = 2.7
    )

    @Test fun floorChoicesNeverConflict() {
        val room = bagno().apply { workCodes += "RASATURA" }
        SmartSurveyDraftV1.setFloor(room, SmartSurveyDraftV1.DEMOLISH)
        assertTrue("DEM-PAV" in room.workCodes)
        SmartSurveyDraftV1.setFloor(room, SmartSurveyDraftV1.OVERLAY)
        assertTrue("POSA-SU-POSA" in room.workCodes)
        assertFalse("DEM-PAV" in room.workCodes)
        SmartSurveyDraftV1.setFloor(room, SmartSurveyDraftV1.KEEP)
        assertFalse("DEM-PAV" in room.workCodes)
        assertFalse("POSA-SU-POSA" in room.workCodes)
        assertTrue("RASATURA" in room.workCodes)
    }

    @Test fun bathOrShowerRemovalIsExclusive() {
        val room = bagno()
        SmartSurveyDraftV1.setWet(room, SmartSurveyDraftV1.BATH)
        assertTrue("DEM-VASCA" in room.workCodes)
        SmartSurveyDraftV1.setWet(room, SmartSurveyDraftV1.SHOWER)
        assertTrue("DEM-DOCCIA" in room.workCodes)
        assertFalse("DEM-VASCA" in room.workCodes)
        SmartSurveyDraftV1.setWet(room, SmartSurveyDraftV1.NONE)
        assertFalse("DEM-DOCCIA" in room.workCodes)
    }

    @Test fun manualShapeOverridesGeometricQuantities() {
        val room = bagno().apply {
            floorManual = 7.5; wallsManual = 19.4; perimeterManual = 11.7
        }
        assertEquals(7.5, room.floor(), 0.001)
        assertEquals(19.4, room.walls(), 0.001)
        assertEquals(11.7, room.perimeter(), 0.001)
        val t = RoomWorkCatalog.forType(RoomTypes.BATHROOM).first { it.code == "DEM-PAV" }
        assertEquals(7.5, SmartSurveyDraftV1.quantity(room, t), 0.001)
    }

    @Test fun bathroomTilingNeedsExplicitHeightOrMeasuredSurface() {
        val room = bagno().apply { workCodes += "PIASTRELLE" }
        val template = RoomWorkCatalog.forType(RoomTypes.BATHROOM).first { it.code == "PIASTRELLE" }
        assertEquals(0.0, SmartSurveyDraftV1.quantity(room, template), 0.001)
        val initial = SmartSurveyDraftV1.checks(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertTrue(initial.any { it.contains("altezza rivestimenti") })
        room.tilingHeight = 1.2
        assertEquals(18.0, SmartSurveyDraftV1.quantity(room, template), 0.001)
        room.tilingWallsManual = 14.0
        assertEquals(20.0, SmartSurveyDraftV1.quantity(room, template), 0.001)
        val restored = SmartSurveyRoomV1.fromJson(room.toJson())
        assertEquals(1.2, restored.tilingHeight, 0.001)
        assertEquals(14.0, restored.tilingWallsManual, 0.001)
    }

    @Test fun missingManualQuantitiesAreNotInvented() {
        val room = bagno()
        val t = RoomWorkCatalog.forType(RoomTypes.BATHROOM).first { it.basis == QuantityBasis.MANUAL }
        assertEquals(0.0, SmartSurveyDraftV1.quantity(room, t), 0.001)
        room.quantities[t.code] = 8.0
        assertEquals(8.0, SmartSurveyDraftV1.quantity(room, t), 0.001)
    }

    @Test fun optionalWorkDoesNotChangeEstimateTotal() {
        val room = bagno().apply {
            workCodes += "DEM-PAV"
            workCodes += "DEM-SAN"
            unitPrices["DEM-PAV"] = 40.0
            unitPrices["DEM-SAN"] = 200.0
            options += "DEM-SAN"
        }
        val s = SmartSurveySessionV1(rooms = mutableListOf(room), title = "Bagno")
        val before = s.toJson().toString()
        val quote = SmartSurveyDraftV1.toEstimate(s)
        assertEquals(240.0, quote.finalTotal, 0.001)
        assertTrue(quote.items.single { it.priceListCode.endsWith("/DEM-SAN") }.optional)
        assertEquals(before, s.toJson().toString())
    }

    @Test fun priceByRoomDoesNotDoubleChargeChecklist() {
        val room = bagno().apply {
            pricingMode = RoomPricing.BODY; bodyPrice = 4150.0
            workCodes += "DEM-PAV"; workCodes += "PITTURA"
            unitPrices["DEM-PAV"] = 100.0
        }
        val quote = SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertEquals(4150.0, quote.finalTotal, 0.001)
        assertEquals(1, quote.items.count { it.included })
        assertTrue(quote.items.any { it.priceListCode.endsWith("/BODY") })
    }

    @Test fun jsonRoundTripPreservesDraftAndFlags() {
        val room = bagno().apply {
            workCodes += "DEM-RIV"
            unitPrices["DEM-RIV"] = 32.0
            options += "DEM-RIV"
        }
        val source = SmartSurveySessionV1(
            clientName = "Cliente", title = "Bagno 1",
            rooms = mutableListOf(room),
            photoFiles = mutableListOf("foto-local-private.img")
        )
        val restored = SmartSurveySessionV1.fromJson(source.toJson().toString())
        assertEquals(source.id, restored.id)
        assertEquals(source.rooms.first().workCodes, restored.rooms.first().workCodes)
        assertEquals(32.0, restored.rooms.first().unitPrices["DEM-RIV"]!!, 0.001)
        assertTrue("DEM-RIV" in restored.rooms.first().options)
        assertEquals("foto-local-private.img", restored.photoFiles.single())
    }

    @Test fun manualWorkIsPreservedInQuoteAndJson() {
        val room = bagno().apply {
            manualWorks += SmartSurveyManualWorkV1(
                name = "Ripristino muro particolare",
                description = "Dopo rimozione rivestimento",
                quantity = 4.0, unit = "m²", unitPrice = 25.0
            )
        }
        val source = SmartSurveySessionV1(rooms = mutableListOf(room))
        val saved = SmartSurveySessionV1.fromJson(source.toJson().toString())
        assertEquals("Ripristino muro particolare", saved.rooms.single().manualWorks.single().name)
        val quote = SmartSurveyDraftV1.toEstimate(saved)
        val line = quote.items.single()
        assertTrue(line.priceListCode.startsWith(RoomManualWorkDialog.prefixFor(room.id)))
        assertEquals(100.0, quote.finalTotal, 0.001)
        assertTrue(line.internalNote.contains(SmartSurveyDraftV1.QUANTITY_SOURCE_MARKER))
    }

    @Test fun optionalManualExtraStaysOutOfRoomBodyPrice() {
        val room = bagno().apply {
            pricingMode = RoomPricing.BODY
            bodyPrice = 4000.0
            manualWorks += SmartSurveyManualWorkV1(
                name = "Nicchia optional", quantity = 1.0,
                unit = "corpo", unitPrice = 350.0, optional = true
            )
        }
        val quote = SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertEquals(4000.0, quote.finalTotal, 0.001)
        val option = quote.items.single { it.optional }
        assertEquals(350.0, option.unitPrice, 0.001)
        assertTrue(option.included)
    }

    @Test fun convertedSurveyCannotBeSilentlyReused() {
        val session = SmartSurveySessionV1(
            generatedEstimateId = "quote-123", rooms = mutableListOf(bagno())
        )
        assertThrows(IllegalArgumentException::class.java) { SmartSurveyDraftV1.toEstimate(session) }
    }

    @Test fun incompleteSurveyIsAllowedButClearlyFlagged() {
        val room = SmartSurveyRoomV1(name = "Bagno", type = RoomTypes.BATHROOM)
        val warnings = SmartSurveyDraftV1.checks(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertTrue(warnings.any { it.contains("pavimento non misurato") })
        assertTrue(warnings.any { it.contains("prezzo", ignoreCase = true) }.not())
        assertTrue(warnings.any { it.contains("demolizione") })
        assertTrue(warnings.any { it.contains("vasca") })
    }
}
