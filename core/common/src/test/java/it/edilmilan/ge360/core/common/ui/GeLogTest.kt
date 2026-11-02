package it.edilmilan.ge360.core.common.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratti della rotazione del registro errori.
 *
 * E' il punto in cui il registro potrebbe perdere righe o lasciarne una
 * troncata a metà, senza che nessuno se ne accorga: un registro che si
 * corrompe da solo è peggio di non averlo, perché si legge e si crede.
 */
class GeLogTest {

    private fun lines(count: Int, prefix: String = "riga"): String =
        (1..count).joinToString("\n", postfix = "\n") { "$prefix-$it" }

    @Test
    fun `sotto il limite non si tocca niente`() {
        val content = lines(3)

        assertEquals(content, GeLog.rotated(content, keep = 10_000))
    }

    @Test
    fun `la rotazione tiene le righe recenti e scarta le vecchie`() {
        val content = lines(200)
        // Abbastanza per molte righe, non per tutte.
        val result = GeLog.rotated(content, keep = 200)

        assertTrue("manca l'ultima riga", result.contains("riga-200"))
        assertFalse("la prima riga non doveva sopravvivere", result.contains("riga-1\n"))
    }

    @Test
    fun `non resta mai una riga troncata a meta`() {
        // keep cade di proposito in mezzo a una riga.
        val content = lines(50, prefix = "voce-lunga-abbastanza")
        val result = GeLog.rotated(content, keep = 137)

        val body = result.removePrefix(GeLog.HEADER)
        body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            assertTrue(
                "riga troncata trovata: '$line'",
                line.startsWith("voce-lunga-abbastanza-")
            )
        }
    }

    @Test
    fun `il taglio e segnalato nel file`() {
        val result = GeLog.rotated(lines(200), keep = 200)

        assertTrue(
            "il registro ruotato deve dire che è stato tagliato",
            result.startsWith(GeLog.HEADER)
        )
    }

    @Test
    fun `un contenuto senza righe intere non lascia spazzatura`() {
        // Caso limite: una sola riga enorme senza interruzioni. Meglio restare
        // con il solo avviso che con un frammento senza data né livello.
        val result = GeLog.rotated("x".repeat(5_000), keep = 100)

        assertEquals(GeLog.HEADER, result)
    }

    @Test
    fun `la rotazione riduce davvero la dimensione`() {
        val content = lines(500)
        val result = GeLog.rotated(content, keep = 1_000)

        assertTrue(
            "atteso un file più piccolo: ${result.length} vs ${content.length}",
            result.length < content.length
        )
        assertTrue(
            "atteso un file vicino al limite richiesto: ${result.length}",
            result.length <= 1_000 + GeLog.HEADER.length
        )
    }
}
