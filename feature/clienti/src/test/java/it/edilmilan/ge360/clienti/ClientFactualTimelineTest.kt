package it.edilmilan.ge360.clienti

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scenari preparati; nessuna esecuzione in questa fase. */
class ClientFactualTimelineTest {
    @Test fun evento_fattuale_non_richiede_cantiere() {
        val e = ClientFactualEventV1.newManual("client-uuid", "Telefonata",
            "Ha chiesto conferma per il sopralluogo", "Telefonata")
        assertTrue(e.isValid())
        assertTrue(e.jobsiteGe360Id == null)
    }

    @Test fun data_prossima_azione_deve_essere_iso() {
        val e = ClientFactualEventV1.newManual("client-uuid", "Impegno preso",
            "Inviare preventivo", "Email", "domani")
        assertFalse(e.isValid())
        assertTrue(e.copy(nextActionDate = "2026-11-05").isValid())
    }

    @Test fun segnale_psicologico_non_e_tipo_evento() {
        val e = ClientFactualEventV1.newManual("client-uuid", "Cliente problematico",
            "Interpretazione soggettiva", "Manuale")
        assertFalse(e.isValid())
    }

    @Test fun descrizione_vuota_e_invalida() {
        val e = ClientFactualEventV1.newManual("client-uuid", "Telefonata", "  ", "Manuale")
        assertFalse(e.isValid())
    }
}
