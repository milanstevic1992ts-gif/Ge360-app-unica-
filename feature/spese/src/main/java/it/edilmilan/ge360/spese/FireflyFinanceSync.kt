package it.edilmilan.ge360.spese

import android.content.Context
import it.edilmilan.ge360.core.bridge.BridgeHttpException
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.core.common.ui.GeLog
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.concurrent.thread

data class FireflySyncResult(
    val ok: Boolean,
    val message: String,
    val fireflyId: String = "",
    /** True quando non si sa se la transazione e' stata creata. */
    val uncertain: Boolean = false
)

data class FireflyBatchResult(
    val synced: Int,
    val failed: Int,
    /** Movimenti saltati perche' il loro esito precedente e' ignoto. */
    val skippedUncertain: Int = 0
) {
    val message: String
        get() = buildString {
            append("Firefly: $synced sincronizzati")
            if (failed > 0) append(" · $failed errori")
            if (skippedUncertain > 0) append(" · $skippedUncertain da verificare")
        }
}

/**
 * Invio dei movimenti a Firefly III attraverso il bridge GE360.
 *
 * ## Il doppione che questo codice evita
 *
 * La creazione di una transazione e' una POST. Se la POST arriva al server ma
 * la risposta si perde — timeout di 10 secondi, Tailscale che cade, app uccisa
 * dal sistema — il movimento resta senza `fireflyId`. Prima veniva segnato come
 * errore e la sincronizzazione successiva rifaceva la POST: due transazioni
 * identiche su Firefly, e un saldo sbagliato.
 *
 * Due difese, indipendenti:
 *
 * 1. **Chiave di idempotenza.** Ogni creazione porta `Idempotency-Key` derivata
 *    dal `ge360Id` del movimento, quindi stabile tra i tentativi. Un server che
 *    la rispetta restituisce la transazione gia' creata invece di crearne
 *    un'altra. Il contratto lato server e' in `docs/FIREFLY_SYNC.md`.
 * 2. **Esito ignoto distinto dall'errore.** Se non si sa se la richiesta e'
 *    arrivata, il movimento diventa [FireflySyncStatus.UNCERTAIN] e il
 *    ritentativo automatico lo salta. Questa difesa funziona anche se il server
 *    ignora la chiave, ed e' quella che protegge oggi.
 */
object FireflyFinanceSync {

    fun syncAsync(
        context: Context,
        record: FinanceRecord,
        callback: ((FireflySyncResult) -> Unit)? = null
    ) {
        val app = context.applicationContext
        thread(name = "ge360-firefly-${record.ge360Id.take(8)}", isDaemon = true) {
            val store = FinanceStore(app)
            val result = runCatching { syncNow(app, record) }
                .fold(
                    onSuccess = { fireflyId ->
                        store.markFireflySynced(record.ge360Id, fireflyId)
                        FireflySyncResult(true, "Sincronizzato con Firefly", fireflyId)
                    },
                    onFailure = { error -> recordFailure(store, record, error) }
                )
            store.close()
            callback?.invoke(result)
        }
    }

    /**
     * Ritenta i movimenti non sincronizzati.
     *
     * Quelli con esito ignoto vengono saltati: per quelli serve guardare
     * Firefly e decidere, non ritentare alla cieca. Con [includeUncertain] si
     * forza il ritentativo, da usare solo dopo aver verificato che la
     * transazione non c'e' (o quando il server rispetta la chiave di
     * idempotenza).
     */
    fun syncPendingAsync(
        context: Context,
        records: List<FinanceRecord>,
        includeUncertain: Boolean = false,
        callback: ((FireflyBatchResult) -> Unit)? = null
    ) {
        val app = context.applicationContext
        val notSynced = records.filter { it.fireflyStatus != FireflySyncStatus.SYNCED }
        val uncertain = notSynced.filter {
            it.fireflyStatus == FireflySyncStatus.UNCERTAIN && it.fireflyId.isBlank()
        }
        val pending = if (includeUncertain) notSynced else notSynced - uncertain.toSet()

        thread(name = "ge360-firefly-retry", isDaemon = true) {
            var synced = 0
            var failed = 0
            val store = FinanceStore(app)
            pending.forEach { record ->
                runCatching { syncNow(app, record) }
                    .onSuccess { fireflyId ->
                        store.markFireflySynced(record.ge360Id, fireflyId)
                        synced++
                    }
                    .onFailure { error ->
                        recordFailure(store, record, error)
                        failed++
                    }
            }
            store.close()
            callback?.invoke(
                FireflyBatchResult(
                    synced = synced,
                    failed = failed,
                    skippedUncertain = if (includeUncertain) 0 else uncertain.size
                )
            )
        }
    }

    /**
     * Decide se il fallimento e' definitivo o di esito ignoto, e lo registra.
     *
     * La distinzione la fornisce il livello HTTP: un 4xx significa che la
     * richiesta e' stata rifiutata senza produrre nulla, mentre un 5xx o
     * l'assenza di risposta lasciano aperta la possibilita' che la transazione
     * esista.
     */
    private fun recordFailure(
        store: FinanceStore,
        record: FinanceRecord,
        error: Throwable
    ): FireflySyncResult {
        val detail = error.message?.take(500) ?: "Errore sincronizzazione Firefly"
        val outcomeUnknown = isOutcomeUnknown(
            creating = record.fireflyId.isBlank(),
            error = error
        )

        return if (outcomeUnknown) {
            val message = "Esito non confermato · verifica su Firefly prima di riprovare ($detail)"
            store.markFireflyUncertain(record.ge360Id, message)
            GeLog.e(
                "Firefly",
                "esito ignoto per il movimento ${record.ge360Id.take(8)} di ${record.amount} €",
                error
            )
            FireflySyncResult(false, message, uncertain = true)
        } else {
            store.markFireflyError(record.ge360Id, detail)
            GeLog.w(
                "Firefly",
                "invio rifiutato per il movimento ${record.ge360Id.take(8)}",
                error
            )
            FireflySyncResult(false, detail)
        }
    }

    /**
     * Se dopo questo fallimento la transazione possa esistere o no.
     *
     * Funzione pura e separata perche' e' la regola che evita i doppioni, ed e'
     * l'unica parte di questo file che si puo' (e si deve) coprire con un test.
     *
     * - [creating] false: e' un aggiornamento su una transazione che esiste
     *   gia'. Ripeterlo non crea nulla, quindi l'esito non e' mai "ignoto".
     * - 4xx: richiesta rifiutata senza essere elaborata, non ha prodotto nulla.
     * - 5xx: la richiesta e' arrivata, il server puo' aver scritto comunque.
     * - altra [IOException]: nessuna risposta, non si sa se sia arrivata.
     * - tutto il resto (es. configurazione mancante, risposta senza id): non e'
     *   nemmeno partita, oppure e' andata a buon fine a meta' in modo noto.
     */
    internal fun isOutcomeUnknown(creating: Boolean, error: Throwable): Boolean {
        if (!creating) return false
        return when (error) {
            is BridgeHttpException -> error.isServerError
            is IOException -> true
            else -> false
        }
    }

    private fun syncNow(context: Context, record: FinanceRecord): String {
        val config = SecureBridgeSettings(context).get(BridgeService.JARVIS)
        require(config.configured) {
            "Backend GE360 non configurato: configura Jarvis/Data Hub nelle impostazioni"
        }

        val body = JSONObject()
            .put("kind", if (record.type == FinanceType.INCOME) "income" else "expense")
            .put("amount_cents", amountCents(record.amount))
            .put("occurred_at", record.date)
            .put("description", record.note.ifBlank { record.category })
            .put("source_record_id", record.ge360Id)
            .put("category", record.category)
            .put("jobsite", jobsiteLabel(record))
            .put("notes", record.note)
            .put("scope", if (record.scope == FinanceScope.PRIVATE) "private" else "business")
            .put("currency_code", "EUR")

        val creating = record.fireflyId.isBlank()
        val path = if (creating) {
            "/api/v1/integrations/firefly/transactions"
        } else {
            "/api/v1/integrations/firefly/transactions/${record.fireflyId}"
        }

        // La chiave e' derivata dal movimento, non dal tentativo: due invii
        // dello stesso movimento portano la stessa chiave.
        val headers = if (creating) {
            mapOf("Idempotency-Key" to idempotencyKey(record))
        } else {
            emptyMap()
        }

        val response = JsonHttpClient()
            .request(
                config = config,
                path = path,
                method = if (creating) "POST" else "PUT",
                body = body,
                extraHeaders = headers
            )
            .jsonObject()

        val fireflyId = response.optString("fireflyTransactionId", record.fireflyId).trim()
        require(fireflyId.isNotBlank()) { "Firefly non ha restituito l'ID della transazione" }
        return fireflyId
    }

    /**
     * Chiave di idempotenza del movimento.
     *
     * Solo caratteri ammessi negli header: il `ge360Id` e' un UUID, ma la
     * ripulitura evita che un dato importato da un backup con un id strano
     * faccia rifiutare l'header dal client HTTP.
     */
    internal fun idempotencyKey(record: FinanceRecord): String =
        "ge360-finance-" + record.ge360Id.trim().replace(Regex("[^A-Za-z0-9-]"), "")

    internal fun amountCents(amount: Double): Long =
        BigDecimal.valueOf(amount)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

    private fun jobsiteLabel(record: FinanceRecord): String =
        listOf(record.jobsiteCode.trim(), record.jobsiteTitle.trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}
