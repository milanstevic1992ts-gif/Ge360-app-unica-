package it.edilmilan.ge360.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisProactivePolicyTest {
    @Test
    fun importantOnlyAcceptsCriticalAndWarning() {
        assertTrue(JarvisProactivePolicy.shouldNotify("a", "critical", emptySet(), includeInfo = false))
        assertTrue(JarvisProactivePolicy.shouldNotify("b", "warning", emptySet(), includeInfo = false))
        assertFalse(JarvisProactivePolicy.shouldNotify("c", "info", emptySet(), includeInfo = false))
        assertEquals("warning", JarvisProactivePolicy.minimumSeverity(includeInfo = false))
    }

    @Test
    fun includeInfoAllowsInfoButNeverDuplicatesSeenAlert() {
        val seen = setOf("already-seen")
        assertTrue(JarvisProactivePolicy.shouldNotify("new-info", "info", seen, includeInfo = true))
        assertFalse(JarvisProactivePolicy.shouldNotify("already-seen", "critical", seen, includeInfo = true))
        assertFalse(JarvisProactivePolicy.shouldNotify("", "warning", seen, includeInfo = true))
        assertFalse(JarvisProactivePolicy.shouldNotify("x", "unknown", seen, includeInfo = true))
        assertEquals("info", JarvisProactivePolicy.minimumSeverity(includeInfo = true))
    }

    @Test
    fun seenHistoryIsDeduplicatedAndBounded() {
        val previous = (1..220).map { "old-$it" }
        val merged = JarvisProactivePolicy.mergeSeen(previous, listOf("old-220", "new-1", "new-2"))

        assertEquals(200, merged.size)
        assertEquals(200, merged.distinct().size)
        assertTrue("new-1" in merged)
        assertTrue("new-2" in merged)
        assertTrue("old-220" in merged)
    }
}
