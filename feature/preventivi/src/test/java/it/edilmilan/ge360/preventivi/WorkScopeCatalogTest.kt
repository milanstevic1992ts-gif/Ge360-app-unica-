package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkScopeCatalogTest {
    @Test fun everyCapitolatoCodeHasAScopeRule() {
        assertEquals(87, StandardWorkCatalog.entries.size)
        assertEquals(87, StandardWorkCatalog.entries.map { it.code }.distinct().size)
        StandardWorkCatalog.entries.forEach { entry ->
            val rule = WorkScopeCatalog.ruleFor(entry.code)
            assertEquals("Unità ${entry.code}", entry.unit, rule?.unit)
            assertTrue("Target mancante: ${entry.code}", rule!!.suggestedTarget in rule.allowedTargets)
        }
    }

    @Test fun aSingleWallKeepsItsIndependentArea() {
        val m = WorkMeasurements(singleWallArea = 8.375, allRoomWallsArea = 30.0)
        val q = WorkScopeCatalog.suggestedQuantity("CAP-48", WorkTargetKind.SINGLE_WALL, m)
        assertEquals(8.38, q!!, 0.001)
    }

    @Test fun aWholeRoomFloorUsesOnlyFloorArea() {
        val m = WorkMeasurements(roomFloorArea = 11.70, allRoomWallsArea = 50.0)
        assertEquals(11.7, WorkScopeCatalog.suggestedQuantity("CAP-60", WorkTargetKind.ROOM_FLOOR, m)!!, 0.001)
        assertNull(WorkScopeCatalog.suggestedQuantity("CAP-60", WorkTargetKind.ROOM_WALLS, m))
    }

    @Test fun missingAndInvalidMeasuresNeverBecomeOneSquareMeter() {
        assertNull(WorkScopeCatalog.suggestedQuantity("CAP-75", WorkTargetKind.SINGLE_WALL, WorkMeasurements()))
        assertNull(WorkScopeCatalog.suggestedQuantity("CAP-75", WorkTargetKind.SINGLE_WALL, WorkMeasurements(singleWallArea = -3.0)))
        assertNull(WorkScopeCatalog.suggestedQuantity("CAP-75", WorkTargetKind.SINGLE_WALL, WorkMeasurements(singleWallArea = Double.NaN)))
    }

    @Test fun aBodyWorkIsCountedOnceAndDoesNotRequireASurvey() {
        val rule = WorkScopeCatalog.ruleFor("CAP-43")!!
        assertTrue(WorkTargetKind.ROOM in rule.allowedTargets)
        assertEquals(1.0, WorkScopeCatalog.suggestedQuantity("CAP-43", WorkTargetKind.ROOM, WorkMeasurements())!!, 0.001)
        assertNull(WorkScopeCatalog.ruleFor("CAP-999"))
    }

    @Test fun unitTargetsAreConsistentWithTheCapitolato() {
        StandardWorkCatalog.entries.forEach { entry ->
            val rule = WorkScopeCatalog.ruleFor(entry.code)!!
            when (entry.unit) {
                "corpo", "forfait" -> assertTrue(WorkTargetKind.ROOM in rule.allowedTargets)
                "cad." -> assertTrue(WorkTargetKind.SINGLE_ELEMENT in rule.allowedTargets)
                "ml" -> assertFalse(WorkTargetKind.ROOM_FLOOR in rule.allowedTargets)
                "m²" -> assertFalse(WorkTargetKind.SINGLE_ELEMENT in rule.allowedTargets)
            }
        }
    }
}
