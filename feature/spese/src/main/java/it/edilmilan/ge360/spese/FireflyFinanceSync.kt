package it.edilmilan.ge360.spese

import android.content.Context
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.concurrent.thread

data class FireflySyncResult(
    val ok: Boolean,
    val message: String,
    val fireflyId: String = ""
)

data class FireflyBatchResult(
    val synced: Int,
    val failed: Int
) {
    val message: String
        get() = "Firefly: $synced sincronizzati${if (failed > 0) " · $failed errori" else ""}"
}

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
                    onFailure = { error ->
                        val message = error.message?.take(500) ?: "Errore sincronizzazione Firefly"
                        store.markFireflyError(record.ge360Id, message)
                        FireflySyncResult(false, message)
                    }
                )
            store.close()
            callback?.invoke(result)
        }
    }

    fun syncPendingAsync(
        context: Context,
        records: List<FinanceRecord>,
        callback: ((FireflyBatchResult) -> Unit)? = null
    ) {
        val app = context.applicationContext
        val pending = records.filter { it.fireflyStatus != FireflySyncStatus.SYNCED }
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
                        store.markFireflyError(
                            record.ge360Id,
                            error.message?.take(500) ?: "Errore sincronizzazione Firefly"
                        )
                        failed++
                    }
            }
            store.close()
            callback?.invoke(FireflyBatchResult(synced, failed))
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

        val path = if (record.fireflyId.isBlank()) {
            "/api/v1/integrations/firefly/transactions"
        } else {
            "/api/v1/integrations/firefly/transactions/${record.fireflyId}"
        }
        val method = if (record.fireflyId.isBlank()) "POST" else "PUT"
        val response = JsonHttpClient().request(config, path, method, body).jsonObject()
        val fireflyId = response.optString("fireflyTransactionId", record.fireflyId).trim()
        require(fireflyId.isNotBlank()) { "Firefly non ha restituito l'ID della transazione" }
        return fireflyId
    }

    private fun amountCents(amount: Double): Long =
        BigDecimal.valueOf(amount)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

    private fun jobsiteLabel(record: FinanceRecord): String =
        listOf(record.jobsiteCode.trim(), record.jobsiteTitle.trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}
