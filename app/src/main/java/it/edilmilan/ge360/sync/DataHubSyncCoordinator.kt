package it.edilmilan.ge360.sync

import android.content.Context
import it.edilmilan.ge360.agenda.AgendaStore
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.attrezzi.CustomWorkPackStore
import it.edilmilan.ge360.attrezzi.VanPreparationPlan
import it.edilmilan.ge360.attrezzi.VanPreparationStore
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.centrocantiere.JobsiteArchiveStore
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.bridge.BridgeService
import it.edilmilan.ge360.core.bridge.JsonHttpClient
import it.edilmilan.ge360.core.bridge.SecureBridgeSettings
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.CompensationStore
import it.edilmilan.ge360.messaggi.TemplateStore
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimateLifecycleStore
import it.edilmilan.ge360.preventivi.EstimateRoomStore
import it.edilmilan.ge360.preventivi.PreventiviCustomizationStore
import it.edilmilan.ge360.planimetrie.FloorPlanStore
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.FirStore
import it.edilmilan.ge360.spese.FinanceStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sincronizzazione leggera App Unica -> GE360 Data Hub.
 *
 * Il telefono resta offline-first e continua a salvare nei propri store locali. Quando il
 * backend configurato per Jarvis e' raggiungibile, gli stessi export JSON usati dal backup
 * vengono pubblicati come snapshot di modulo su /api/v1/sync/push.
 *
 * Non esegue merge automatici: un dispositivo senza revisione nota può inizializzare soltanto
 * moduli server vuoti; in seguito ogni divergenza viene segnalata e non viene sovrascritta.
 */
object DataHubSyncCoordinator {
    data class SyncSummary(
        val configured: Boolean,
        val pushed: Int,
        val unchanged: Int,
        val conflicts: Int,
        val failed: Int,
        val message: String
    ) {
        val ok: Boolean get() = configured && failed == 0 && conflicts == 0
    }

    private data class ModuleSnapshot(
        val name: String,
        val schema: String,
        val clientUpdatedAt: Long,
        val payload: JSONObject
    )

    private const val PREFS = "ge360_datahub_sync"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_ATTEMPT = "last_attempt_at"
    private const val KEY_LAST_SUCCESS = "last_success_at"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_REV_PREFIX = "revision_"
    private const val MIN_INTERVAL_MS = 15_000L
    private val running = AtomicBoolean(false)

    fun syncAsync(
        context: Context,
        force: Boolean = false,
        callback: ((SyncSummary) -> Unit)? = null
    ) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_ATTEMPT, 0L) < MIN_INTERVAL_MS) return
        if (!running.compareAndSet(false, true)) return
        prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply()

        thread(name = "ge360-datahub-sync", isDaemon = true) {
            val result = runCatching { syncNow(app) }
                .getOrElse {
                    SyncSummary(
                        configured = true,
                        pushed = 0,
                        unchanged = 0,
                        conflicts = 0,
                        failed = 1,
                        message = it.message ?: "Errore sync Data Hub"
                    )
                }
            prefs.edit()
                .putString(KEY_LAST_STATUS, result.message)
                .apply {
                    if (result.ok) putLong(KEY_LAST_SUCCESS, System.currentTimeMillis())
                }
                .apply()
            running.set(false)
            callback?.invoke(result)
        }
    }

    fun lastStatus(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STATUS, "").orEmpty()

    fun lastSuccessAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SUCCESS, 0L)

    private fun syncNow(context: Context): SyncSummary {
        val config = SecureBridgeSettings(context).get(BridgeService.JARVIS)
        if (!config.configured) {
            return SyncSummary(
                configured = false,
                pushed = 0,
                unchanged = 0,
                conflicts = 0,
                failed = 0,
                message = "Data Hub non configurato: usa lo stesso backend impostato in Jarvis"
            )
        }

        val http = JsonHttpClient()
        val state = http.request(config, "/api/v1/sync/state").jsonObject()
        val serverRevisions = mutableMapOf<String, Int>()
        state.optJSONArray("modules")?.let { rows ->
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val name = row.optString("name").trim().lowercase()
                if (name.isNotBlank()) serverRevisions[name] = row.optInt("serverRevision", 0)
            }
        }

        val snapshots = collectSnapshots(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modules = JSONArray()
        val blockedConflicts = mutableSetOf<String>()

        snapshots.forEach { snapshot ->
            val serverRevision = serverRevisions[snapshot.name] ?: 0
            val knownKey = KEY_REV_PREFIX + snapshot.name
            val hasKnownRevision = prefs.contains(knownKey)
            val knownRevision = prefs.getInt(knownKey, 0)

            // Un dispositivo nuovo può inizializzare soltanto un modulo server vuoto.
            // In seguito, una revisione diversa da quella confermata indica una scrittura
            // concorrente e non deve essere sovrascritta automaticamente.
            if (!DataHubSyncSafety.canPush(hasKnownRevision, knownRevision, serverRevision)) {
                // Un telefono nuovo/reinstallato non deve mai adottare alla cieca la revisione
                // del server e sovrascriverla con uno snapshot locale potenzialmente vuoto.
                blockedConflicts += snapshot.name
                return@forEach
            }

            modules.put(
                JSONObject()
                    .put("name", snapshot.name)
                    .put("schema", snapshot.schema)
                    .put("baseRevision", if (hasKnownRevision) knownRevision else serverRevision)
                    .put("clientUpdatedAt", snapshot.clientUpdatedAt)
                    .put("payload", snapshot.payload)
            )
        }

        if (modules.length() == 0) {
            val message = if (blockedConflicts.isEmpty()) {
                "Nessun modulo da sincronizzare"
            } else {
                "Sincronizzazione bloccata in sicurezza: il server contiene revisioni non adottate (${blockedConflicts.sorted().joinToString(", ")})"
            }
            return SyncSummary(
                configured = true,
                pushed = 0,
                unchanged = 0,
                conflicts = blockedConflicts.size,
                failed = 0,
                message = message
            )
        }

        val body = JSONObject()
            .put("deviceId", deviceId(prefs))
            .put("modules", modules)
        val response = http.request(config, "/api/v1/sync/push", "POST", body).jsonObject()
        val results = response.optJSONArray("results") ?: JSONArray()

        var pushed = 0
        var unchanged = 0
        var conflicts = blockedConflicts.size
        var failed = 0
        val conflictNames = blockedConflicts.toMutableSet()

        for (index in 0 until results.length()) {
            val row = results.optJSONObject(index) ?: continue
            val name = row.optString("name").trim().lowercase()
            val status = row.optString("status").trim().lowercase()
            val revision = row.optInt("serverRevision", 0)
            when (status) {
                "created", "updated" -> {
                    pushed++
                    if (name.isNotBlank()) prefs.edit().putInt(KEY_REV_PREFIX + name, revision).apply()
                }
                "unchanged" -> {
                    unchanged++
                    if (name.isNotBlank()) prefs.edit().putInt(KEY_REV_PREFIX + name, revision).apply()
                }
                "conflict" -> {
                    conflicts++
                    if (name.isNotBlank()) conflictNames += name
                }
                else -> failed++
            }
        }

        val message = buildString {
            append("Data Hub: $pushed aggiornati, $unchanged invariati")
            if (conflicts > 0) append(" · $conflicts conflitti")
            if (failed > 0) append(" · $failed errori")
            if (conflictNames.isNotEmpty()) append(" [${conflictNames.sorted().joinToString(", ")}]")
        }
        return SyncSummary(true, pushed, unchanged, conflicts, failed, message)
    }

    private fun collectSnapshots(context: Context): List<ModuleSnapshot> {
        val clients = ClientStore(context)
        val jobs = JobsiteStore(context)
        val workflow = JobsiteWorkflowStore(context)
        val jobsiteArchive = JobsiteArchiveStore(context)
        val daily = DailyStore(context)
        val compensi = CompensationStore(context)
        val messages = TemplateStore(context)
        val finances = FinanceStore(context)
        val documents = DocumentStore(context)
        val fir = FirStore(context)
        val estimates = EstimateStore(context)
        val estimateRooms = EstimateRoomStore(context)
        val estimateLifecycle = EstimateLifecycleStore(context)
        val estimateCustomizations = PreventiviCustomizationStore(context)
        val floorPlans = FloorPlanStore(context)
        val tools = ToolsStore(context)
        val customWorkPacks = CustomWorkPackStore(context)
        val gallery = GalleryStore(context)

        return try {
            listOf(
                snapshot("clienti", clients.exportJson()),
                snapshot("cantieri", jobs.exportJson()),
                snapshot("centrocantiere", workflow.exportJson()),
                snapshot("jobsite_archive", jobsiteArchive.exportJson()),
                snapshot("giornata", daily.exportJson()),
                snapshot("compensi", compensiSnapshot(compensi, daily)),
                snapshot("agenda", AgendaStore(context).exportForSyncJson()),
                snapshot("messaggi", messages.exportJson()),
                snapshot("spese", finances.exportJson()),
                snapshot("scanner", documents.exportJson()),
                snapshot("fir", fir.exportJson()),
                snapshot("preventivi", estimates.exportJson()),
                snapshot("preventivi_rooms", estimateRooms.exportJson()),
                snapshot("preventivi_lifecycle", estimateLifecycle.exportJson()),
                snapshot("preventivi_customizations", estimateCustomizations.exportFullJson()),
                snapshot("planimetrie", floorPlans.exportJson()),
                snapshot("attrezzi", toolsPayload(tools.exportJson(forSync = true), VanPreparationStore(context).list())),
                snapshot("attrezzi_custom_packs", customWorkPacks.exportJson()),
                snapshot("galleria", gallery.exportJson()),
                snapshot("fisco_settings", fiscalSettingsJson(context))
            )
        } finally {
            clients.close()
            jobs.close()
            workflow.close()
            jobsiteArchive.close()
            daily.close()
            compensi.close()
            messages.close()
            finances.close()
            documents.close()
            fir.close()
            estimates.close()
            estimateRooms.close()
            estimateLifecycle.close()
            floorPlans.close()
            tools.close()
            gallery.close()
        }
    }

    private fun compensiSnapshot(compensi: CompensationStore, daily: DailyStore): String {
        val root = JSONObject(compensi.exportJson())
        // Display-only projection. Personnel remain authoritative in Giornata.
        root.put("collaborators", JSONArray().apply {
            daily.personnel(false).forEach { person ->
                put(JSONObject()
                    .put("ge360Id", person.ge360Id)
                    .put("name", person.name))
            }
        })
        return root.toString()
    }

    private fun snapshot(name: String, json: String): ModuleSnapshot {
        val root = JSONObject(json)
        val exportedAt = when {
            root.has("exportedAt") -> root.optLong("exportedAt", System.currentTimeMillis())
            root.has("exported_at") -> root.optLong("exported_at", System.currentTimeMillis())
            else -> System.currentTimeMillis()
        }
        // Il timestamp di export cambia a ogni serializzazione e non rappresenta una modifica dei dati.
        root.remove("exportedAt")
        root.remove("exported_at")
        return ModuleSnapshot(
            name = name,
            schema = root.optString("schema").ifBlank { "ge360.$name.v1" },
            clientUpdatedAt = exportedAt,
            payload = root
        )
    }

    private fun toolsPayload(json: String, plans: List<VanPreparationPlan>): String {
        val root = JSONObject(json)
        root.put("vanPreparations", JSONArray().apply {
            plans.forEach { plan ->
                put(
                    JSONObject()
                        .put("id", plan.id)
                        .put("jobsiteId", plan.jobsiteId)
                        .put("jobsiteCode", plan.jobsiteCode)
                        .put("jobsiteTitle", plan.jobsiteTitle)
                        .put("date", plan.date)
                        .put("note", plan.note)
                        .put("toolCount", plan.toolCount)
                        .put("materialCount", plan.materialCount)
                        .put("missingCount", plan.missingCount)
                        .put("completed", plan.completed)
                        .put("updatedAt", plan.updatedAt)
                )
            }
        })
        return root.toString()
    }

    private fun fiscalSettingsJson(context: Context): String {
        val prefs = context.getSharedPreferences("ge360_fisco_settings", Context.MODE_PRIVATE)
        return JSONObject()
            .put("schema", "ge360.fisco.settings.v1")
            .put("inps35", prefs.getBoolean("inps35", false))
            .put("passVat", prefs.getBoolean("passVat", true))
            .put("rate", prefs.getInt("rate", 15))
            .put("privateShare", prefs.getFloat("privateShare", 95f).toDouble())
            .put("stamps", prefs.getInt("stamps", 0))
            .put("exportedAt", System.currentTimeMillis())
            .toString()
    }

    private fun deviceId(prefs: android.content.SharedPreferences): String {
        val current = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        if (current.isNotBlank()) return current
        val created = "android-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }
}
