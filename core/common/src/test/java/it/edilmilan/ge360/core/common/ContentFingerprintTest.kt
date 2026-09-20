package it.edilmilan.ge360.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentFingerprintTest {
    @Test
    fun identicalContentProducesIdenticalFingerprint() {
        assertEquals(
            ContentFingerprint.sha256(listOf("cantieri", "uno", "foto", "due")),
            ContentFingerprint.sha256(listOf("cantieri", "uno", "foto", "due"))
        )
    }

    @Test
    fun contentChangeProducesDifferentFingerprint() {
        assertNotEquals(
            ContentFingerprint.sha256(listOf("preventivo", "1000")),
            ContentFingerprint.sha256(listOf("preventivo", "1200"))
        )
    }

    @Test
    fun boundariesAndOrderArePartOfTheFingerprint() {
        assertNotEquals(
            ContentFingerprint.sha256(listOf("ab", "c")),
            ContentFingerprint.sha256(listOf("a", "bc"))
        )
        assertNotEquals(
            ContentFingerprint.sha256(listOf("a", "b")),
            ContentFingerprint.sha256(listOf("b", "a"))
        )
    }
}
