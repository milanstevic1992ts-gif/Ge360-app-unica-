package it.edilmilan.ge360.planimetrie

import it.edilmilan.ge360.preventivi.EstimateItem
import it.edilmilan.ge360.preventivi.EstimateRecord
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID

/** Import manuale e non economico del calcolo OpenPlan3D. */
data class SurveyEstimateRoomV1(
    val roomId: String,
    val name: String,
    val quality: String,
    val confidence: Double?,
    val metrics: Map<String, Double>
)
data class SurveyEstimateHandoffV1(
    val planId: String,
    val planName: String,
    val version: Int,
    val fingerprint: String,
    val rooms: List<SurveyEstimateRoomV1>
)

object SurveyEstimateTransferV1 {
    const val SCHEMA = "ge360.rilievi.to_estimate.v1"
    const val CODE_PREFIX = "OPENPLAN3D/"
    private const val SOURCE_MARKER = "[GE360_OPENPLAN3D_MEASURE_V1]"
    private val idRegex = Regex("[A-Za-z0-9_-]{1,64}")
    private val fingerprintRegex = Regex("[a-z0-9]+-[a-z0-9]+")
    private val METRICS = listOf(
        Metric("floorAreaM2", "Superficie pavimento", "m²"),
        Metric("ceilingAreaM2", "Superficie soffitto", "m²"),
        Metric("netWallAreaM2", "Pareti nette", "m²"),
        Metric("grossWallAreaM2", "Pareti lorde", "m²"),
        Metric("paintAreaM2", "Superficie indicativa pittura", "m²"),
        Metric("tilingAreaM2", "Superficie indicativa rivestimento", "m²"),
        Metric("skirtingM", "Battiscopa stimato", "ml")
    )
    private data class Metric(val key: String, val title: String, val unit: String)

    fun parse(raw: String): SurveyEstimateHandoffV1 {
        require(raw.length <= 256_000) { "Rilievo troppo grande da importare" }
        val root = JSONObject(raw)
        require(root.optString("schema") == SCHEMA) { "Formato di trasferimento non riconosciuto" }
        require(root.optString("status") == "PROCESSED") {
            "Serve prima un calcolo completato e verificabile sul Debian"
        }
        val id = root.optString("planId")
        require(idRegex.matches(id)) { "Identificativo rilievo non valido" }
        val version = root.optInt("version")
        require(version in 1..1_000_000) { "Versione calcolo non valida" }
        val fingerprint = root.optString("fingerprint")
        require(fingerprintRegex.matches(fingerprint) && fingerprint.length <= 100) {
            "Origine geometrica non verificabile: ripetere il calcolo sul Debian"
        }
        val title = clean(root.optString("planName")).ifBlank { "Rilievo $id" }
        val array = root.optJSONArray("rooms") ?: error("Mancano gli ambienti")
        require(array.length() in 1..50) { "Numero ambienti non valido" }
        val known = mutableSetOf<String>()
        val rooms = (0 until array.length()).map { index ->
            val row = array.optJSONObject(index) ?: error("Ambiente non valido")
            val roomId = row.optString("roomId")
            require(idRegex.matches(roomId) && known.add(roomId)) {
                "Identificativo ambiente assente o duplicato"
            }
            val name = clean(row.optString("name")).ifBlank { "Ambiente ${index + 1}" }
            val quality = clean(row.optString("quality")).take(40)
            val confidence = if (row.has("confidence") && !row.isNull("confidence")) {
                val v = row.optDouble("confidence", Double.NaN)
                require(v.isFinite() && v in 0.0..1.0) { "Affidabilità non valida: $name" }
                v
            } else null
            val metrics = linkedMapOf<String, Double>()
            for (metric in METRICS) {
                if (!row.has(metric.key) || row.isNull(metric.key)) continue
                val v = row.optDouble(metric.key, Double.NaN)
                require(v.isFinite() && v >= 0.0 && v <= 100_000.0) {
                    "Metratura non valida per $name: ${metric.title}"
                }
                if (v > 0.0) metrics[metric.key] = v
            }
            require(metrics.containsKey("floorAreaM2")) {
                "Superficie pavimento mancante: $name"
            }
            SurveyEstimateRoomV1(roomId, name, quality, confidence, metrics)
        }
        return SurveyEstimateHandoffV1(id, title, version, fingerprint, rooms)
    }

    fun containsPlan(quote: EstimateRecord, planId: String): Boolean =
        quote.items.any { it.priceListCode.startsWith("$CODE_PREFIX$planId/") }

    fun reviewWarnings(source: SurveyEstimateHandoffV1): List<String> = buildList {
        source.rooms.forEach { room ->
            if (room.quality.isNotBlank() && room.quality.uppercase(Locale.ROOT) != "OK") {
                add("${room.name}: qualità ${room.quality} — verificare le quote sul rilievo")
            }
            if (room.confidence != null && room.confidence < 0.85) {
                add("${room.name}: affidabilità ${"%.0f".format(Locale.ITALY, room.confidence * 100)}%")
            }
            if (room.metrics["netWallAreaM2"] == null) {
                add("${room.name}: pareti nette non disponibili")
            }
        }
    }

    fun preview(source: SurveyEstimateHandoffV1): String = buildString {
        appendLine("Rilievo: ${source.planName}")
        appendLine("Versione calcolo: ${source.version} · ${source.rooms.size} ambienti")
        appendLine("Le quantità derivano dal calcolo Debian. Sono ESCLUSE dal totale e a € 0.")
        appendLine("Non vengono assegnate lavorazioni automaticamente.")
        source.rooms.forEach { room ->
            appendLine()
            appendLine(room.name.uppercase(Locale.ITALY))
            METRICS.forEach { metric ->
                room.metrics[metric.key]?.let {
                    appendLine("• ${metric.title}: ${fmt(it)} ${metric.unit}")
                }
            }
        }
        val warnings = reviewWarnings(source)
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("DA VERIFICARE:")
            warnings.forEach { appendLine("• $it") }
        }
    }.trim()

    fun buildDraftItems(source: SurveyEstimateHandoffV1): List<EstimateItem> {
        val out = mutableListOf<EstimateItem>()
        source.rooms.forEach { room ->
            METRICS.forEach metricLoop@ { metric ->
                val raw = room.metrics[metric.key] ?: return@metricLoop
                val amount = BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP).toDouble()
                if (amount <= 0.0) return@metricLoop
                out += EstimateItem(
                    ge360Id = UUID.randomUUID().toString(),
                    priceListCode = "$CODE_PREFIX${source.planId}/${room.roomId}/${metric.key}",
                    section = "Dati del rilievo",
                    room = room.name,
                    category = "Quantità tecniche da verificare",
                    name = "Rilievo · ${metric.title}",
                    description = "Dato metrico da GE360 Rilievi, NON è una lavorazione offerta. " +
                        "Scegli esplicitamente le opere e i prezzi nella checklist del preventivo.",
                    quantity = amount,
                    unit = metric.unit,
                    unitPrice = 0.0,
                    priceMode = "unit",
                    included = false,
                    optional = false,
                    internalNote = "$SOURCE_MARKER plan=${source.planId}; " +
                        "room=${room.roomId}; version=${source.version}; " +
                        "fingerprint=${source.fingerprint}; quality=${room.quality}",
                    total = 0.0
                )
            }
        }
        require(out.isNotEmpty()) { "Il rilievo non contiene metrature utilizzabili" }
        return out
    }

    private fun clean(raw: String) = raw.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(100)
    private fun fmt(value: Double) = String.format(Locale.ITALY, "%.2f", value)
}
