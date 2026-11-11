package it.edilmilan.ge360.attrezzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceReminderRulesTest {
    @Test fun toolAlwaysMeansBring() {
        assertEquals(ResourceKind.TOOL, ResourceReminderRules.kind(ResourceReminderRules.TOOL_TO_BRING))
        assertEquals("Da portare", ResourceReminderRules.status(ResourceReminderRules.TOOL_TO_BRING))
    }

    @Test fun materialCanBeBoughtOrCarried() {
        assertEquals(ResourceKind.MATERIAL, ResourceReminderRules.kind(ResourceReminderRules.MATERIAL_TO_BUY))
        assertEquals("Da comprare", ResourceReminderRules.status(ResourceReminderRules.MATERIAL_TO_BUY))
        assertEquals(ResourceKind.MATERIAL, ResourceReminderRules.kind(ResourceReminderRules.MATERIAL_TO_BRING))
        assertEquals("Da portare", ResourceReminderRules.status(ResourceReminderRules.MATERIAL_TO_BRING))
    }

    @Test fun quantitiesAcceptItalianDecimalSeparator() {
        assertEquals(2.5, ResourceReminderRules.amount(" 2,5 ")!!, 0.0001)
        assertEquals(12.75, ResourceReminderRules.amount("12.75")!!, 0.0001)
    }

    @Test fun rejectMissingNegativeZeroAndInfiniteQuantities() {
        for (raw in listOf("", "zero", "0", "-1", "NaN", "Infinity", "100001")) {
            assertNull(ResourceReminderRules.amount(raw))
        }
    }

    @Test fun duplicateCheckIgnoresCaseAndExtraneousSpaces() {
        val rows = listOf(JobResource(kind = ResourceKind.MATERIAL, name = "  Rasante "))
        val found = ResourceReminderRules.findDuplicate(rows, ResourceKind.MATERIAL, "RASANTE")
        assertTrue(found != null)
    }

    @Test fun sameNameToolAndMaterialAreDistinct() {
        val rows = listOf(JobResource(kind = ResourceKind.TOOL, name = "Disco diamantato"))
        val match = ResourceReminderRules.findDuplicate(rows, ResourceKind.MATERIAL, "Disco diamantato")
        assertNull(match)
        assertFalse(ResourceReminderRules.same(ResourceKind.MATERIAL, "Disco diamantato", rows.single()))
    }
}
