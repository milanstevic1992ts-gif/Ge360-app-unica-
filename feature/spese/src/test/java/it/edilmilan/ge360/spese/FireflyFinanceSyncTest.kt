package it.edilmilan.ge360.spese

import it.edilmilan.ge360.core.bridge.BridgeHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Contratti della sincronizzazione Firefly.
 *
 * Il test che conta e' quello sulla classificazione dell'esito: e' la regola
 * che impedisce i movimenti doppi. Un movimento la cui creazione e' finita
 * senza risposta NON deve essere ritentato in automatico, perche' la
 * transazione su Firefly potrebbe esistere comunque.
 *
 * Il contratto completo, incluso quello che deve fare il server, e' in
 * docs/FIREFLY_SYNC.md.
 */
class FireflyFinanceSyncTest {

    private fun record(fireflyId: String = "") = FinanceRecord(
        ge360Id = "11111111-2222-3333-4444-555555555555",
        type = FinanceType.EXPENSE,
        amount = 120.50,
        category = "Materiali",
        date = "2026-11-02",
        fireflyId = fireflyId
    )

    // -----------------------------------------------------------------------
    // Classificazione dell'esito
    // -----------------------------------------------------------------------

    @Test
    fun `nessuna risposta durante una creazione lascia l'esito ignoto`() {
        assertTrue(
            FireflyFinanceSync.isOutcomeUnknown(
                creating = true,
                error = SocketTimeoutException("timeout")
            )
        )
        assertTrue(
            FireflyFinanceSync.isOutcomeUnknown(
                creating = true,
                error = IOException("connessione interrotta")
            )
        )
    }

    @Test
    fun `un errore del server durante una creazione lascia l'esito ignoto`() {
        // Il server ha ricevuto la richiesta: puo' aver scritto comunque.
        listOf(500, 502, 503).forEach { code ->
            assertTrue(
                "HTTP $code dovrebbe essere incerto",
                FireflyFinanceSync.isOutcomeUnknown(
                    creating = true,
                    error = BridgeHttpException(code, "boom")
                )
            )
        }
    }

    @Test
    fun `una richiesta rifiutata non ha prodotto nulla`() {
        // 4xx: la richiesta e' arrivata ed e' stata scartata senza effetti.
        listOf(400, 401, 404, 409, 422).forEach { code ->
            assertFalse(
                "HTTP $code dovrebbe essere un errore definitivo",
                FireflyFinanceSync.isOutcomeUnknown(
                    creating = true,
                    error = BridgeHttpException(code, "rifiutato")
                )
            )
        }
    }

    @Test
    fun `un aggiornamento non puo mai essere incerto`() {
        // Il PUT riscrive una transazione che esiste gia': ripeterlo non crea
        // doppioni, qualunque sia l'errore.
        listOf(
            SocketTimeoutException("timeout"),
            IOException("rete giu'"),
            BridgeHttpException(503, "manutenzione")
        ).forEach { error ->
            assertFalse(
                "un aggiornamento non deve diventare incerto: $error",
                FireflyFinanceSync.isOutcomeUnknown(creating = false, error = error)
            )
        }
    }

    @Test
    fun `un errore di configurazione non e un esito ignoto`() {
        // Non e' nemmeno partita una richiesta.
        assertFalse(
            FireflyFinanceSync.isOutcomeUnknown(
                creating = true,
                error = IllegalArgumentException("Backend GE360 non configurato")
            )
        )
    }

    @Test
    fun `una risposta senza id non e un esito ignoto`() {
        // Il server ha risposto 2xx ma senza l'id: sappiamo che e' arrivata, il
        // problema e' la risposta malformata, non l'incertezza.
        assertFalse(
            FireflyFinanceSync.isOutcomeUnknown(
                creating = true,
                error = IllegalArgumentException("Firefly non ha restituito l'ID della transazione")
            )
        )
    }

    // -----------------------------------------------------------------------
    // Chiave di idempotenza
    // -----------------------------------------------------------------------

    @Test
    fun `la chiave dipende dal movimento e non dal tentativo`() {
        val first = FireflyFinanceSync.idempotencyKey(record())
        val second = FireflyFinanceSync.idempotencyKey(record())

        assertEquals(first, second)
        assertEquals("ge360-finance-11111111-2222-3333-4444-555555555555", first)
    }

    @Test
    fun `movimenti diversi hanno chiavi diverse`() {
        val other = record().copy(ge360Id = "99999999-8888-7777-6666-555555555555")

        assertTrue(
            FireflyFinanceSync.idempotencyKey(record()) !=
                FireflyFinanceSync.idempotencyKey(other)
        )
    }

    @Test
    fun `la chiave resta valida come header anche con id sporchi`() {
        // Un id arrivato da un backup vecchio potrebbe contenere caratteri che
        // il client HTTP rifiuterebbe nell'header.
        val dirty = record().copy(ge360Id = "id con spazi/eàccenti_42")
        val key = FireflyFinanceSync.idempotencyKey(dirty)

        assertTrue("chiave non valida come header: $key", Regex("^[A-Za-z0-9-]+$").matches(key))
    }

    // -----------------------------------------------------------------------
    // Importi
    // -----------------------------------------------------------------------

    @Test
    fun `gli importi viaggiano in centesimi con arrotondamento commerciale`() {
        assertEquals(12_050L, FireflyFinanceSync.amountCents(120.50))
        assertEquals(1_999L, FireflyFinanceSync.amountCents(19.99))
        assertEquals(0L, FireflyFinanceSync.amountCents(0.0))
        // mezzo centesimo: si arrotonda per eccesso, non si perde
        assertEquals(1_235L, FireflyFinanceSync.amountCents(12.345))
        assertEquals(1L, FireflyFinanceSync.amountCents(0.005))
    }

    // -----------------------------------------------------------------------
    // Riepilogo del ritentativo
    // -----------------------------------------------------------------------

    @Test
    fun `il riepilogo distingue gli errori dai movimenti da verificare`() {
        assertEquals("Firefly: 3 sincronizzati", FireflyBatchResult(3, 0).message)
        assertEquals("Firefly: 3 sincronizzati · 1 errori", FireflyBatchResult(3, 1).message)
        assertEquals(
            "Firefly: 3 sincronizzati · 2 da verificare",
            FireflyBatchResult(3, 0, skippedUncertain = 2).message
        )
    }

    @Test
    fun `lo stato incerto esiste e non e un errore`() {
        // Se qualcuno riducesse l'enum ai tre valori di prima, la protezione
        // contro i doppioni sparirebbe in silenzio.
        assertTrue(
            FireflySyncStatus.entries.map { it.name }.containsAll(
                listOf("PENDING", "SYNCED", "ERROR", "UNCERTAIN")
            )
        )
    }
}
