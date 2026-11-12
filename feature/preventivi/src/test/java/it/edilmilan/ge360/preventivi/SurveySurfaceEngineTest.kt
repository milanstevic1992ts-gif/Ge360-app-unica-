package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SurveySurfaceEngineTest {
    private fun room() = SmartSurveyRoomV1(
        name = "Camera", type = RoomTypes.ROOM,
        length = 4.0, width = 3.0, height = 2.8,
        wallsList = mutableListOf(
            SurveyWallV1(id = "parete-finestra", name = "Parete finestra", width = 4.0, height = 2.8, openingsArea = 2.2),
            SurveyWallV1(id = "parete-porta", name = "Parete porta", width = 3.0, height = 2.8, openingsArea = 1.8)
        )
    )

    @Test fun wallWorkHasCorrectNetQuantityAndIndependentPrices() {
        val room = room()
        SurveySurfaceEngine.applySelection(
            room, "parete-finestra", setOf("RASATURA"), setOf("RASATURA")
        )
        SurveySurfaceEngine.applySelection(
            room, "parete-porta", setOf("RASATURA"), setOf("RASATURA")
        )
        assertEquals(2, room.scopedWorks.size)
        val template = RoomWorkCatalog.forType(room.type).first { it.code == "RASATURA" }
        val a = room.scopedWorks.first { it.scopeId == "parete-finestra" }
        val b = room.scopedWorks.first { it.scopeId == "parete-porta" }
        assertEquals(9.0, SurveySurfaceEngine.quantity(room, a, template), 0.001)
        assertEquals(6.6, SurveySurfaceEngine.quantity(room, b, template), 0.001)
        a.unitPrice = 20.0
        b.unitPrice = 23.0
        val quote = SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertEquals(331.8, quote.finalTotal, 0.001)
        assertEquals(2, quote.items.size)
        assertEquals(2, quote.items.map { it.priceListCode }.toSet().size)
        assertTrue(quote.items.all { it.priceListCode.startsWith("SURFACE/") })
        assertTrue(quote.items.any { it.name.contains("Parete finestra") })
        assertTrue(quote.items.any { it.name.contains("Parete porta") })
    }

    @Test fun sameCodeRoomAndWallMustBeExplicitlyReassigned() {
        val room = room().apply { workCodes += "PITTURA"; unitPrices["PITTURA"] = 15.0 }
        assertEquals(setOf("PITTURA"), SurveySurfaceEngine.overlappingCodes(
            room, "parete-finestra", setOf("PITTURA")
        ))
        assertThrows(IllegalArgumentException::class.java) {
            SurveySurfaceEngine.applySelection(room, "parete-finestra", setOf("PITTURA"), setOf("PITTURA"))
        }
        assertTrue("PITTURA" in room.workCodes)
        assertTrue(room.scopedWorks.isEmpty())
        SurveySurfaceEngine.applySelection(room, "parete-finestra", setOf("PITTURA"), setOf("PITTURA"), true)
        assertFalse("PITTURA" in room.workCodes)
        assertEquals(1, room.scopedWorks.size)
        assertEquals("parete-finestra", room.scopedWorks.single().scopeId)
    }

    @Test fun floorAndCeilingUseDifferentAreasAndCodes() {
        val room = room().apply { floorManual = 12.4; ceilingManual = 13.1 }
        SurveySurfaceEngine.applySelection(room, SurveySurfaceEngine.FLOOR, setOf("PITTURA"), setOf("PITTURA"))
        SurveySurfaceEngine.applySelection(room, SurveySurfaceEngine.CEILING, setOf("PITTURA"), setOf("PITTURA"))
        val template = RoomWorkCatalog.forType(room.type).first { it.code == "PITTURA" }
        val floor = room.scopedWorks.first { it.scopeId == SurveySurfaceEngine.FLOOR }
        val ceiling = room.scopedWorks.first { it.scopeId == SurveySurfaceEngine.CEILING }
        assertEquals(12.4, SurveySurfaceEngine.quantity(room, floor, template), 0.001)
        assertEquals(13.1, SurveySurfaceEngine.quantity(room, ceiling, template), 0.001)
        floor.unitPrice = 10.0
        ceiling.unitPrice = 20.0
        val quote = SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertEquals(386.0, quote.finalTotal, 0.001)
    }

    @Test fun jsonRoundTripKeepsWallIdentityAndManualQuantity() {
        val room = room()
        SurveySurfaceEngine.applySelection(
            room, "parete-finestra", setOf("STUCCO"), setOf("STUCCO")
        )
        room.scopedWorks.single().apply {
            manualQuantity = 8.5
            unitPrice = 25.0
            optional = true
        }
        val session = SmartSurveySessionV1(rooms = mutableListOf(room))
        val saved = SmartSurveySessionV1.fromJson(session.toJson().toString())
        val restored = saved.rooms.single()
        assertEquals("parete-finestra", restored.wallsList.first().id)
        assertEquals("Parete finestra", restored.wallsList.first().name)
        assertEquals(2.2, restored.wallsList.first().openingsArea, 0.001)
        val work = restored.scopedWorks.single()
        assertEquals("parete-finestra", work.scopeId)
        assertEquals(8.5, work.manualQuantity!!, 0.001)
        assertEquals(25.0, work.unitPrice, 0.001)
        assertTrue(work.optional)
        val quote = SmartSurveyDraftV1.toEstimate(saved)
        assertEquals(0.0, quote.finalTotal, 0.001)
        assertTrue(quote.items.single().optional)
        val metadata = SurveySurfaceEngine.parseMetadata(quote.items.single().internalNote)
        assertNotNull(metadata)
        assertEquals("Parete finestra", metadata!!.optString("scopeTitle"))
        assertNotNull(RoomWorkCatalog.parseMetadata(quote.items.single().internalNote))
    }

    @Test fun legacySurveyWithoutSurfacesRemainsCompatible() {
        val old = SmartSurveySessionV1(rooms = mutableListOf(room().apply {
            wallsList.clear()
            workCodes += "PITTURA"
            unitPrices["PITTURA"] = 15.0
        }))
        val raw = old.toJson()
        raw.getJSONArray("rooms").getJSONObject(0).remove("wallsList")
        raw.getJSONArray("rooms").getJSONObject(0).remove("scopedWorks")
        val restored = SmartSurveySessionV1.fromJson(raw.toString())
        assertTrue(restored.rooms.single().scopedWorks.isEmpty())
        assertTrue(restored.rooms.single().wallsList.isEmpty())
        val quote = SmartSurveyDraftV1.toEstimate(restored)
        assertTrue(quote.items.single().priceListCode.startsWith("ROOM/"))
    }

    @Test fun wallDeletionAlsoRemovesOnlyItsOwnAssignments() {
        val room = room()
        SurveySurfaceEngine.applySelection(room, "parete-finestra", setOf("STUCCO"), setOf("STUCCO"))
        SurveySurfaceEngine.applySelection(room, "parete-porta", setOf("STUCCO"), setOf("STUCCO"))
        SurveySurfaceEngine.removeWall(room, "parete-finestra")
        assertEquals(listOf("parete-porta"), room.wallsList.map { it.id })
        assertEquals("parete-porta", room.scopedWorks.single().scopeId)
    }

    @Test fun roomBodyPriceIncludesScopedWorkOnlyOnce() {
        val room = room().apply { pricingMode = RoomPricing.BODY; bodyPrice = 3200.0 }
        SurveySurfaceEngine.applySelection(room, "parete-finestra", setOf("STUCCO"), setOf("STUCCO"))
        room.scopedWorks.single().unitPrice = 35.0
        val quote = SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        assertEquals(3200.0, quote.finalTotal, 0.001)
        assertEquals(1, quote.items.count { it.included })
        assertFalse(quote.items.first { it.priceListCode.startsWith("SURFACE/") }.included)
    }

    @Test fun invalidScopeAndDoubleChargeAreReportedBeforeQuote() {
        val room = room().apply { workCodes += "STUCCO" }
        room.scopedWorks += SurveyScopedWorkV1(scopeId = "parete-finestra", workCode = "STUCCO")
        room.scopedWorks += SurveyScopedWorkV1(scopeId = "removed-wall", workCode = "PITTURA")
        val warnings = SurveySurfaceEngine.checks(room)
        assertTrue(warnings.any { it.contains("doppio conteggio") })
        assertTrue(warnings.any { it.contains("inesistente") })
        assertThrows(IllegalArgumentException::class.java) {
            SmartSurveyDraftV1.toEstimate(SmartSurveySessionV1(rooms = mutableListOf(room)))
        }
    }

    @Test fun promptCarriesStructuredSurfaceTargetWithoutInternalNotes() {
        val room = room()
        SurveySurfaceEngine.applySelection(room, "parete-finestra", setOf("STUCCO"), setOf("STUCCO"))
        room.scopedWorks.single().unitPrice = 25.0
        val s = SmartSurveySessionV1(rooms = mutableListOf(room))
        val estimate = SmartSurveyDraftV1.toEstimate(s)
        val payload = EstimateSopralluogoPromptV1.payload(estimate, SmartSurveyDraftV1.roomModels(s))
        val work = payload.getJSONArray("includedWorks").getJSONObject(0)
        val surface = work.getJSONObject("surface")
        assertEquals("parete-finestra", surface.getString("id"))
        assertEquals("Parete finestra", surface.getString("name"))
        assertEquals("wall", surface.getString("kind"))
        assertFalse(payload.toString().contains("GE360_SURFACE_SCOPE_V1"))
    }
}
