package it.edilmilan.ge360.preventivi

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.round

object EstimateStatuses {
    const val DRAFT = "Bozza"
    const val REVIEW = "Da controllare"
    const val READY = "Pronto"
    const val SENT = "Inviato"
    const val ACCEPTED = "Accettato"
    const val REJECTED = "Rifiutato"
    const val EXPIRED = "Scaduto"
    const val IN_PROGRESS = "Lavoro iniziato"
    const val COMPLETED = "Completato"
    val ALL = listOf(DRAFT, REVIEW, READY, SENT, ACCEPTED, REJECTED, EXPIRED, IN_PROGRESS, COMPLETED)

    fun fromLegacy(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "draft" -> DRAFT; "review" -> REVIEW; "ready" -> READY; "sent" -> SENT
        "accepted" -> ACCEPTED; "rejected" -> REJECTED; "expired" -> EXPIRED
        "in_progress" -> IN_PROGRESS; "completed" -> COMPLETED
        else -> value.ifBlank { DRAFT }
    }
}

data class PriceEntry(
    var id: Long = 0,
    var code: String = "",
    var name: String = "",
    var category: String = "Personalizzate",
    var unit: String = "corpo",
    var suggestedPrice: Double? = null,
    var priceMode: String = "unit",
    var active: Boolean = true,
    var updatedAt: Long = System.currentTimeMillis()
)

data class EstimateItem(
    var id: Long = 0,
    var ge360Id: String = "",
    var estimateId: Long = 0,
    var priceListCode: String = "",
    var section: String = "Resto della casa",
    var room: String = "",
    var category: String = "Personalizzate",
    var name: String = "Lavorazione",
    var description: String = "",
    var quantity: Double = 1.0,
    var unit: String = "corpo",
    var unitPrice: Double = 0.0,
    var priceMode: String = "unit",
    var included: Boolean = true,
    var optional: Boolean = false,
    var internalNote: String = "",
    var total: Double = 0.0
)

data class EstimateRecord(
    var id: Long = 0,
    var ge360Id: String = "",
    var legacyId: String = "",
    var number: String = "",
    var clientId: Long = 0,
    var clientGe360Id: String = "",
    var clientName: String = "",
    var jobsiteId: String = "",
    var jobsiteCode: String = "",
    var jobsiteTitle: String = "",
    var title: String = "",
    var workType: String = "",
    var status: String = EstimateStatuses.DRAFT,
    var documentType: String = "Provvisorio",
    var validityDays: Int = 30,
    var generalDescription: String = "",
    var materialsIncluded: String = "",
    var materialsClient: String = "",
    var exclusions: String = "",
    var finalNotes: String = "",
    var discountType: String = "none",
    var discountValue: Double = 0.0,
    var surchargeType: String = "none",
    var surchargeValue: Double = 0.0,
    var rounding: Double = 0.0,
    var subtotal: Double = 0.0,
    var finalTotal: Double = 0.0,
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var items: MutableList<EstimateItem> = mutableListOf()
)

data class EstimateTotals(val subtotal: Double, val discount: Double, val surcharge: Double, val rounding: Double, val finalTotal: Double)

fun calculateItemTotal(item: EstimateItem): Double {
    if (!item.included || item.optional) return 0.0
    val total = if (item.priceMode == "body" || item.unit in listOf("corpo", "forfait")) item.unitPrice else item.quantity * item.unitPrice
    return round2(total.coerceAtLeast(0.0))
}

fun recalculateEstimate(record: EstimateRecord): EstimateTotals {
    record.items.forEach { it.total = calculateItemTotal(it) }
    val subtotal = round2(record.items.sumOf { it.total })
    val discount = adjust(subtotal, record.discountType, record.discountValue)
    val afterDiscount = round2(subtotal - discount)
    val surcharge = adjust(afterDiscount, record.surchargeType, record.surchargeValue)
    val final = round2(afterDiscount + surcharge + record.rounding)
    record.subtotal = subtotal
    record.finalTotal = final
    return EstimateTotals(subtotal, discount, surcharge, record.rounding, final)
}

private fun adjust(base: Double, type: String, value: Double): Double = when (type) {
    "percent" -> round2(base * value.coerceAtLeast(0.0) / 100.0)
    "fixed" -> round2(value.coerceAtLeast(0.0))
    else -> 0.0
}
private fun round2(value: Double) = round(value * 100.0) / 100.0

class EstimateStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    private val appContext = context.applicationContext
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE estimates(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ge360_id TEXT NOT NULL UNIQUE, legacy_id TEXT NOT NULL DEFAULT '', number TEXT NOT NULL DEFAULT '',
            client_id INTEGER NOT NULL DEFAULT 0, client_ge360_id TEXT NOT NULL DEFAULT '', client_name TEXT NOT NULL DEFAULT '',
            jobsite_id TEXT NOT NULL DEFAULT '', jobsite_code TEXT NOT NULL DEFAULT '', jobsite_title TEXT NOT NULL DEFAULT '',
            title TEXT NOT NULL DEFAULT '', work_type TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Bozza', document_type TEXT NOT NULL DEFAULT 'Provvisorio', validity_days INTEGER NOT NULL DEFAULT 30,
            general_description TEXT NOT NULL DEFAULT '', materials_included TEXT NOT NULL DEFAULT '', materials_client TEXT NOT NULL DEFAULT '', exclusions TEXT NOT NULL DEFAULT '', final_notes TEXT NOT NULL DEFAULT '',
            discount_type TEXT NOT NULL DEFAULT 'none', discount_value REAL NOT NULL DEFAULT 0, surcharge_type TEXT NOT NULL DEFAULT 'none', surcharge_value REAL NOT NULL DEFAULT 0,
            rounding REAL NOT NULL DEFAULT 0, subtotal REAL NOT NULL DEFAULT 0, final_total REAL NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE estimate_items(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ge360_id TEXT NOT NULL UNIQUE, estimate_id INTEGER NOT NULL, price_list_code TEXT NOT NULL DEFAULT '',
            section_name TEXT NOT NULL DEFAULT '', room_name TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', name TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '',
            quantity REAL NOT NULL DEFAULT 1, unit TEXT NOT NULL DEFAULT 'corpo', unit_price REAL NOT NULL DEFAULT 0, price_mode TEXT NOT NULL DEFAULT 'unit', included INTEGER NOT NULL DEFAULT 1,
            optional INTEGER NOT NULL DEFAULT 0, internal_note TEXT NOT NULL DEFAULT '', total REAL NOT NULL DEFAULT 0,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE price_list(
            id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'Personalizzate', unit TEXT NOT NULL DEFAULT 'corpo',
            suggested_price REAL, price_mode TEXT NOT NULL DEFAULT 'unit', active INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX idx_est_status ON estimates(status, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_est_job ON estimates(jobsite_id, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_est_client ON estimates(client_id, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_est_items ON estimate_items(estimate_id, id)")
        createMetricTables(db)
        createMetricVariantTables(db)
        createMetricEconomicsTables(db)
        createAgreementTables(db)
        seedPriceList(db)
    }

    override fun onConfigure(db: SQLiteDatabase) { super.onConfigure(db); db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createMetricTables(db)
            backfillLegacyMetricNotes(db)
        }
        if (oldVersion < 3) createMetricVariantTables(db)
        if (oldVersion < 4) createMetricEconomicsTables(db)
        if (oldVersion < 5) createAgreementTables(db)
    }

    private fun createMetricTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_metric_sources (
            item_ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            binding_ge360_id TEXT NOT NULL,
            snapshot_json TEXT NOT NULL,
            trust TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE(estimate_id, binding_ge360_id),
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metric_est ON estimate_metric_sources(estimate_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_metric_events (
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            item_ge360_id TEXT NOT NULL,
            event_json TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metric_events_est ON estimate_metric_events(estimate_id, occurred_at)")
    }

    private fun createMetricVariantTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_metric_variants (
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            previous_item_ge360_id TEXT NOT NULL,
            candidate_binding_ge360_id TEXT NOT NULL,
            snapshot_json TEXT NOT NULL,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE(estimate_id, candidate_binding_ge360_id),
            UNIQUE(estimate_id, previous_item_ge360_id),
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_variants_est ON estimate_metric_variants(estimate_id,created_at)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_metric_variant_events (
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            variant_ge360_id TEXT NOT NULL,
            event_json TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_variant_events_est ON estimate_metric_variant_events(estimate_id,occurred_at)")
    }

    private fun variantValues(id: Long, value: MetricVariantV3) = ContentValues().apply {
        put("ge360_id", value.ge360Id)
        put("estimate_id", id)
        put("previous_item_ge360_id", value.previousItemGe360Id)
        put("candidate_binding_ge360_id", value.candidateBindingGe360Id)
        put("snapshot_json", value.toJson().toString())
        put("status", value.status)
        put("created_at", value.createdAt)
    }
    private fun variantEventValues(id: Long, event: MetricVariantEventV3) =
        ContentValues().apply {
            put("ge360_id", event.ge360Id)
            put("estimate_id", id)
            put("variant_ge360_id", event.variantGe360Id)
            put("event_json", event.toJson().toString())
            put("occurred_at", event.occurredAt)
        }

    private fun createMetricEconomicsTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_metric_economics(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            variant_ge360_id TEXT NOT NULL,
            data_json TEXT NOT NULL,
            status TEXT NOT NULL,
            proposed_at INTEGER NOT NULL,
            UNIQUE(estimate_id, variant_ge360_id),
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metric_economics_est ON estimate_metric_economics(estimate_id,proposed_at)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_metric_economics_events(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            economics_ge360_id TEXT NOT NULL,
            event_json TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metric_economics_events_est ON estimate_metric_economics_events(estimate_id,occurred_at)")
    }

    private fun economicValues(id: Long, value: MetricVariantEconomicsV4) =
        ContentValues().apply {
            put("ge360_id", value.ge360Id)
            put("estimate_id", id)
            put("variant_ge360_id", value.variantGe360Id)
            put("data_json", value.toJson().toString())
            put("status", value.status)
            put("proposed_at", value.proposedAt)
        }
    private fun economicEventValues(id: Long, event: MetricVariantEconomicsEventV4) =
        ContentValues().apply {
            put("ge360_id", event.ge360Id)
            put("estimate_id", id)
            put("economics_ge360_id", event.economicsGe360Id)
            put("event_json", event.toJson().toString())
            put("occurred_at", event.occurredAt)
        }

    private fun createAgreementTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_agreement_attachments(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            economics_ge360_id TEXT NOT NULL,
            correction_ge360_id TEXT NOT NULL DEFAULT '',
            data_json TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_agreement_files ON estimate_agreement_attachments(estimate_id,economics_ge360_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_agreement_corrections(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            economics_ge360_id TEXT NOT NULL,
            data_json TEXT NOT NULL,
            status TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            UNIQUE(estimate_id,economics_ge360_id),
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS estimate_agreement_correction_events(
            ge360_id TEXT PRIMARY KEY NOT NULL,
            estimate_id INTEGER NOT NULL,
            correction_ge360_id TEXT NOT NULL,
            data_json TEXT NOT NULL,
            occurred_at INTEGER NOT NULL,
            FOREIGN KEY(estimate_id) REFERENCES estimates(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_agreement_corrections ON estimate_agreement_corrections(estimate_id,created_at)")
    }

    private fun attachmentValues(id: Long, a: MetricAgreementAttachmentV5) =
        ContentValues().apply {
            put("ge360_id", a.ge360Id)
            put("estimate_id", id)
            put("economics_ge360_id", a.economicsGe360Id)
            put("correction_ge360_id", a.correctionGe360Id)
            put("data_json", a.toJson().toString())
            put("created_at", a.capturedAt)
        }
    private fun correctionValues(id: Long, c: MetricAgreementCorrectionV5) =
        ContentValues().apply {
            put("ge360_id", c.ge360Id)
            put("estimate_id", id)
            put("economics_ge360_id", c.economicsGe360Id)
            put("data_json", c.toJson().toString())
            put("status", c.status)
            put("created_at", c.proposedAt)
        }
    private fun correctionEventValues(id: Long, e: MetricAgreementCorrectionEventV5) =
        ContentValues().apply {
            put("ge360_id", e.ge360Id)
            put("estimate_id", id)
            put("correction_ge360_id", e.correctionGe360Id)
            put("data_json", e.toJson().toString())
            put("occurred_at", e.occurredAt)
        }

    private fun metricSourceValues(estimateId: Long, data: MetricProvenanceV2) =
        ContentValues().apply {
            put("item_ge360_id", data.itemGe360Id)
            put("estimate_id", estimateId)
            put("binding_ge360_id", data.sourceBindingGe360Id)
            put("snapshot_json", data.toJson().toString())
            put("trust", data.trust)
            put("created_at", data.createdAt)
        }

    private fun metricEventValues(estimateId: Long, data: MetricReviewEventV2) =
        ContentValues().apply {
            put("ge360_id", data.ge360Id)
            put("estimate_id", estimateId)
            put("item_ge360_id", data.itemGe360Id)
            put("event_json", data.toJson().toString())
            put("occurred_at", data.occurredAt)
        }

    fun metricProvenance(itemGe360Id: String): MetricProvenanceV2? {
        if (itemGe360Id.isBlank()) return null
        return readableDatabase.query("estimate_metric_sources",
            arrayOf("snapshot_json"), "item_ge360_id=?",
            arrayOf(itemGe360Id), null, null, null, "1").use { c ->
                if (c.moveToFirst()) MetricProvenanceV2.fromJson(JSONObject(c.getString(0)))
                else null
            }
    }

    private fun metricSourcesForEstimateId(id: Long): List<MetricProvenanceV2> {
        val out = mutableListOf<MetricProvenanceV2>()
        readableDatabase.query("estimate_metric_sources",
            arrayOf("snapshot_json"), "estimate_id=?", arrayOf(id.toString()),
            null, null, "created_at").use { c ->
            while (c.moveToNext()) out += MetricProvenanceV2.fromJson(JSONObject(c.getString(0)))
        }
        return out
    }

    fun metricEvents(estimateGe360Id: String): List<MetricReviewEventV2> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val out = mutableListOf<MetricReviewEventV2>()
        readableDatabase.query("estimate_metric_events", arrayOf("event_json"),
            "estimate_id=?", arrayOf(estimate.id.toString()), null, null,
            "occurred_at DESC").use { c ->
            while (c.moveToNext()) out += MetricReviewEventV2.fromJson(JSONObject(c.getString(0)))
        }
        return out
    }

    fun metricSources(estimateGe360Id: String): List<MetricProvenanceV2> =
        getByGe360Id(estimateGe360Id)?.let { metricSourcesForEstimateId(it.id) }
            ?: emptyList()

    fun metricVariants(estimateGe360Id: String): List<MetricVariantV3> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val out = mutableListOf<MetricVariantV3>()
        readableDatabase.query("estimate_metric_variants",
            arrayOf("snapshot_json"), "estimate_id=?", arrayOf(estimate.id.toString()),
            null, null, "created_at DESC").use { c ->
            while (c.moveToNext()) out += MetricVariantV3.fromJson(JSONObject(c.getString(0)))
        }
        return out
    }

    fun metricVariantEvents(estimateGe360Id: String): List<MetricVariantEventV3> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val out = mutableListOf<MetricVariantEventV3>()
        readableDatabase.query("estimate_metric_variant_events",
            arrayOf("event_json"), "estimate_id=?", arrayOf(estimate.id.toString()),
            null, null, "occurred_at DESC").use { c ->
            while (c.moveToNext()) out += MetricVariantEventV3.fromJson(JSONObject(c.getString(0)))
        }
        return out
    }

    /**
     * The new geometry is only a variation proposal, not a replacement quote row.
     * Source geometry must be rechecked by the Rilievi UI before this DB transaction.
     */
    fun proposeMetricVariant(
        estimateGe360Id: String,
        expectedEstimateUpdatedAt: Long,
        previousItemGe360Id: String,
        candidate: MeasuredEstimateDraftV1,
        reason: String
    ): MetricVariantV3 {
        require(candidate.valid() && reason.trim().length >= 8) {
            "Variante: controllare misure, detrazioni e motivo"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val quote = getByGe360Id(estimateGe360Id)
                ?: error("Preventivo originale non presente")
            require(quote.updatedAt == expectedEstimateUpdatedAt) {
                "Preventivo originale cambiato: riaprire la variante"
            }
            require(quote.status != EstimateStatuses.REJECTED) {
                "Non creare varianti su un preventivo rifiutato"
            }
            val old = metricSourcesForEstimateId(quote.id)
                .singleOrNull { it.itemGe360Id == previousItemGe360Id }
                ?: error("Metratura precedente non presente nel registro")
            require(old.trust == MetricProvenanceV2.TRUST_VERIFIED) {
                "Metratura legacy non verificata: serve nuovo rilievo"
            }
            val price = quote.items.singleOrNull { it.ge360Id == old.itemGe360Id }
                ?: error("Lavorazione originale non disponibile")
            require(price.priceListCode == old.workCode && price.unit == "m²" &&
                price.room == old.roomSnapshot &&
                kotlin.math.abs(price.quantity - old.reviewedQuantityM2) < 0.001) {
                "Riga originale diversa dalla fonte: verificare prima della variante"
            }
            require(old.planGe360Id == candidate.planGe360Id &&
                old.roomGe360Id == candidate.roomGe360Id &&
                old.wallGe360Id.orEmpty() == candidate.wallGe360Id.orEmpty() &&
                old.surface == candidate.surface && old.workCode == candidate.workCode) {
                "La variante deve riguardare la stessa lavorazione e superficie"
            }
            require(old.sourceBindingGe360Id != candidate.sourceBindingGe360Id &&
                old.geometryFingerprint != candidate.geometryFingerprint) {
                "Serve una nuova revisione geometrica, non lo stesso rilievo"
            }
            require(kotlin.math.abs(old.reviewedQuantityM2 -
                candidate.reviewedQuantityM2()) >= 0.01) {
                "La nuova quantità deve differire dalla precedente almeno di 0,01 m²"
            }
            require(metricSourcesForEstimateId(quote.id).none {
                it.sourceBindingGe360Id == candidate.sourceBindingGe360Id
            }) { "La nuova revisione è già stata importata come voce del preventivo" }
            require(metricVariants(estimateGe360Id).none {
                it.previousItemGe360Id == previousItemGe360Id ||
                    it.candidateBindingGe360Id == candidate.sourceBindingGe360Id
            }) {
                "Esiste già una variante per questa voce: gestirla prima di crearne un'altra"
            }
            val draft = MetricVariantV3(
                estimateGe360Id = estimateGe360Id,
                previousItemGe360Id = price.ge360Id,
                previousBindingGe360Id = old.sourceBindingGe360Id,
                candidateBindingGe360Id = candidate.sourceBindingGe360Id,
                planGe360Id = candidate.planGe360Id,
                roomGe360Id = candidate.roomGe360Id,
                wallGe360Id = candidate.wallGe360Id,
                workCode = candidate.workCode,
                surface = candidate.surface,
                previousFingerprint = old.geometryFingerprint,
                candidateFingerprint = candidate.geometryFingerprint,
                previousQuantityM2 = old.reviewedQuantityM2,
                proposedQuantityM2 = candidate.reviewedQuantityM2(),
                candidateGrossM2 = candidate.grossM2,
                candidateOpeningDeductionsM2 = candidate.openingDeductionsM2,
                candidateAdditionalDeductionsM2 = candidate.additionalDeductionM2,
                priorUnitPrice = price.unitPrice,
                priorIncluded = price.included && !price.optional,
                reason = reason.trim()
            )
            require(draft.valid()) { "Snapshot della variante non valido" }
            db.insertOrThrow("estimate_metric_variants", null, variantValues(quote.id, draft))
            val event = MetricVariantEventV3(
                variantGe360Id = draft.ge360Id,
                estimateGe360Id = estimateGe360Id,
                action = "PROPOSED",
                reason = reason.trim()
            )
            check(event.valid())
            db.insertOrThrow("estimate_metric_variant_events", null,
                variantEventValues(quote.id, event))
            db.setTransactionSuccessful()
            return draft
        } finally { db.endTransaction() }
    }

    /** Technical review only: original accepted quote and totals remain untouched. */
    fun reviewMetricVariant(
        estimateGe360Id: String,
        variantGe360Id: String,
        reviewReason: String
    ): MetricVariantV3 {
        require(reviewReason.trim().length >= 8) { "Motivare la verifica tecnica" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val quote = getByGe360Id(estimateGe360Id)
                ?: error("Preventivo non trovato")
            val old = metricVariants(estimateGe360Id)
                .singleOrNull { it.ge360Id == variantGe360Id }
                ?: error("Variante non trovata")
            require(old.valid() && old.status == MetricVariantV3.STATUS_PROPOSED) {
                "Variante già verificata o dati non validi"
            }
            val source = metricSourcesForEstimateId(quote.id)
                .singleOrNull { it.itemGe360Id == old.previousItemGe360Id }
                ?: error("Fonte del preventivo originale mancante")
            val originalRow = quote.items.singleOrNull {
                it.ge360Id == old.previousItemGe360Id
            } ?: error("Riga originale assente")
            require(source.trust == MetricProvenanceV2.TRUST_VERIFIED &&
                source.sourceBindingGe360Id == old.previousBindingGe360Id &&
                source.geometryFingerprint == old.previousFingerprint &&
                kotlin.math.abs(originalRow.unitPrice - old.priorUnitPrice) < 0.001 &&
                (originalRow.included && !originalRow.optional) == old.priorIncluded) {
                "Preventivo o condizioni economiche modificati: riesaminare la proposta"
            }
            val updated = old.copy(
                status = MetricVariantV3.STATUS_TECH_REVIEWED,
                reviewReason = reviewReason.trim(),
                reviewedAt = maxOf(System.currentTimeMillis(), old.createdAt)
            )
            require(updated.valid())
            val count = db.update("estimate_metric_variants", variantValues(quote.id, updated),
                "ge360_id=? AND status=?", arrayOf(
                    variantGe360Id, MetricVariantV3.STATUS_PROPOSED))
            check(count == 1) { "La variante è cambiata durante la conferma" }
            val event = MetricVariantEventV3(
                variantGe360Id = updated.ge360Id,
                estimateGe360Id = estimateGe360Id,
                action = "TECH_REVIEWED",
                reason = reviewReason.trim()
            )
            db.insertOrThrow("estimate_metric_variant_events", null,
                variantEventValues(quote.id, event))
            db.setTransactionSuccessful()
            return updated
        } finally { db.endTransaction() }
    }

    fun metricVariantEconomics(estimateGe360Id: String): List<MetricVariantEconomicsV4> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val out = mutableListOf<MetricVariantEconomicsV4>()
        readableDatabase.query("estimate_metric_economics", arrayOf("data_json"),
            "estimate_id=?", arrayOf(estimate.id.toString()), null, null,
            "proposed_at DESC").use { c ->
            while (c.moveToNext()) out +=
                MetricVariantEconomicsV4.fromJson(JSONObject(c.getString(0)))
        }
        return out
    }

    fun metricVariantEconomicEvents(estimateGe360Id: String):
        List<MetricVariantEconomicsEventV4> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val out = mutableListOf<MetricVariantEconomicsEventV4>()
        readableDatabase.query("estimate_metric_economics_events",
            arrayOf("event_json"), "estimate_id=?", arrayOf(estimate.id.toString()),
            null, null, "occurred_at DESC").use { c ->
            while (c.moveToNext()) out +=
                MetricVariantEconomicsEventV4.fromJson(JSONObject(c.getString(0)))
        }
        return out
    }

    /**
     * New addendum proposal, not a quote change. A technical variant can have
     * exactly one proposed commercial adjustment: no duplicated extras/credits.
     */
    fun proposeMetricVariantEconomics(
        estimateGe360Id: String,
        variantGe360Id: String,
        expectedEstimateUpdatedAt: Long,
        mode: String,
        priceEuro: Double,
        reason: String
    ): MetricVariantEconomicsV4 {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val quote = getByGe360Id(estimateGe360Id) ?: error("Preventivo non trovato")
            require(quote.updatedAt == expectedEstimateUpdatedAt) {
                "Preventivo modificato: riaprire la proposta economica"
            }
            require(quote.status != EstimateStatuses.REJECTED) {
                "Non proporre accordi su un preventivo rifiutato"
            }
            val variant = metricVariants(estimateGe360Id)
                .singleOrNull { it.ge360Id == variantGe360Id }
                ?: error("La variante tecnica non esiste")
            require(variant.valid() &&
                variant.status == MetricVariantV3.STATUS_TECH_REVIEWED) {
                "Serve prima la verifica tecnica delle quantità"
            }
            require(variant.priorIncluded && variant.priorUnitPrice > 0.0) {
                "La variante economica richiede una voce originale inclusa e prezzata: " +
                    "altrimenti prepara una nuova voce di preventivo"
            }
            val source = metricSourcesForEstimateId(quote.id)
                .singleOrNull { it.itemGe360Id == variant.previousItemGe360Id }
                ?: error("Fonte metratura originale non disponibile")
            require(source.trust == MetricProvenanceV2.TRUST_VERIFIED &&
                source.sourceBindingGe360Id == variant.previousBindingGe360Id &&
                source.geometryFingerprint == variant.previousFingerprint) {
                "Origine non verificata o modificata"
            }
            val item = quote.items.singleOrNull {
                it.ge360Id == variant.previousItemGe360Id
            } ?: error("Voce del preventivo originale mancante")
            require(kotlin.math.abs(item.quantity - variant.previousQuantityM2) < 0.001 &&
                kotlin.math.abs(item.unitPrice - variant.priorUnitPrice) < 0.001 &&
                (item.included && !item.optional) == variant.priorIncluded) {
                "Quantità o prezzo storico cambiati: ripetere la proposta"
            }
            require(metricVariantEconomics(estimateGe360Id).none {
                it.variantGe360Id == variantGe360Id ||
                    it.sourceItemGe360Id == variant.previousItemGe360Id
            }) { "Una proposta economica è già registrata per questa variazione" }
            val economics = MetricVariantEconomicsV4.create(
                variant, mode, priceEuro, reason
            )
            require(economics.valid() &&
                economics.estimateGe360Id == quote.ge360Id)
            db.insertOrThrow("estimate_metric_economics", null,
                economicValues(quote.id, economics))
            val event = MetricVariantEconomicsEventV4(
                economicsGe360Id = economics.ge360Id,
                estimateGe360Id = quote.ge360Id,
                action = "ECONOMIC_PROPOSAL",
                signedAmountEuro = economics.proposedSignedAmountEuro,
                reason = economics.proposalReason
            )
            check(event.valid())
            db.insertOrThrow("estimate_metric_economics_events", null,
                economicEventValues(quote.id, event))
            db.setTransactionSuccessful()
            return economics
        } finally { db.endTransaction() }
    }

    /**
     * Manual attestation of evidence held outside this app. Does not assert
     * that the attachment was retrieved or digitally signed.
     */
    fun attestMetricVariantAgreement(
        estimateGe360Id: String,
        economicsGe360Id: String,
        externalReference: String,
        agreementDateIso: String,
        evidenceNote: String,
        expresslyConfirmed: Boolean
    ): MetricVariantEconomicsV4 {
        require(expresslyConfirmed) { "Occorre confermare di aver verificato l'accordo esterno" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val quote = getByGe360Id(estimateGe360Id) ?: error("Preventivo non presente")
            require(quote.status != EstimateStatuses.REJECTED) {
                "Preventivo rifiutato: accordo non registrabile"
            }
            val current = metricVariantEconomics(estimateGe360Id)
                .singleOrNull { it.ge360Id == economicsGe360Id }
                ?: error("Proposta economica non trovata")
            require(current.valid() &&
                current.status == MetricVariantEconomicsV4.STATUS_PROPOSED) {
                "Proposta economica già annotata o non valida"
            }
            val variant = metricVariants(estimateGe360Id)
                .singleOrNull { it.ge360Id == current.variantGe360Id }
                ?: error("Variante tecnica non disponibile")
            require(variant.valid() &&
                variant.status == MetricVariantV3.STATUS_TECH_REVIEWED &&
                variant.previousItemGe360Id == current.sourceItemGe360Id &&
                kotlin.math.abs(variant.deltaQuantityM2() - current.deltaM2) < 0.001) {
                "Variante tecnica diversa dalla proposta economica"
            }
            val item = quote.items.singleOrNull {
                it.ge360Id == current.sourceItemGe360Id
            } ?: error("Voce originale non disponibile")
            require(kotlin.math.abs(item.quantity - variant.previousQuantityM2) < 0.001 &&
                kotlin.math.abs(item.unitPrice - variant.priorUnitPrice) < 0.001 &&
                (item.included && !item.optional) == variant.priorIncluded) {
                "Condizioni del preventivo originale cambiate: rivedere l'accordo"
            }
            val updated = current.copy(
                status = MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED,
                externalAgreementReference = externalReference.trim(),
                externalAgreementDate = agreementDateIso.trim(),
                evidenceNote = evidenceNote.trim(),
                attestedAt = maxOf(System.currentTimeMillis(), current.proposedAt + 1)
            )
            require(updated.valid()) {
                "Riferimento accordo, data ISO yyyy-MM-dd e nota di verifica obbligatori"
            }
            val touched = db.update("estimate_metric_economics",
                economicValues(quote.id, updated),
                "ge360_id=? AND status=?", arrayOf(
                    current.ge360Id, MetricVariantEconomicsV4.STATUS_PROPOSED
                ))
            check(touched == 1) { "Accordo già modificato da altra schermata" }
            val event = MetricVariantEconomicsEventV4(
                economicsGe360Id = updated.ge360Id,
                estimateGe360Id = quote.ge360Id,
                action = "AGREEMENT_ATTESTED",
                signedAmountEuro = updated.proposedSignedAmountEuro,
                evidenceReference = updated.externalAgreementReference,
                reason = updated.evidenceNote
            )
            check(event.valid())
            db.insertOrThrow("estimate_metric_economics_events", null,
                economicEventValues(quote.id, event))
            db.setTransactionSuccessful()
            return updated
        } finally { db.endTransaction() }
    }

    fun agreementAttachments(estimateGe360Id: String): List<MetricAgreementAttachmentV5> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val list = mutableListOf<MetricAgreementAttachmentV5>()
        readableDatabase.query("estimate_agreement_attachments", arrayOf("data_json"),
            "estimate_id=?", arrayOf(estimate.id.toString()), null, null,
            "created_at ASC").use { c ->
            while (c.moveToNext()) list +=
                MetricAgreementAttachmentV5.fromJson(JSONObject(c.getString(0)))
        }
        return list
    }

    fun agreementCorrections(estimateGe360Id: String): List<MetricAgreementCorrectionV5> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val list = mutableListOf<MetricAgreementCorrectionV5>()
        readableDatabase.query("estimate_agreement_corrections", arrayOf("data_json"),
            "estimate_id=?", arrayOf(estimate.id.toString()), null, null,
            "created_at ASC").use { c ->
            while (c.moveToNext()) list +=
                MetricAgreementCorrectionV5.fromJson(JSONObject(c.getString(0)))
        }
        return list
    }

    fun agreementCorrectionEvents(estimateGe360Id: String):
        List<MetricAgreementCorrectionEventV5> {
        val estimate = getByGe360Id(estimateGe360Id) ?: return emptyList()
        val list = mutableListOf<MetricAgreementCorrectionEventV5>()
        readableDatabase.query("estimate_agreement_correction_events",
            arrayOf("data_json"), "estimate_id=?", arrayOf(estimate.id.toString()),
            null, null, "occurred_at ASC").use { c ->
            while (c.moveToNext()) list +=
                MetricAgreementCorrectionEventV5.fromJson(JSONObject(c.getString(0)))
        }
        return list
    }

    fun attachAgreementFile(
        estimateGe360Id: String,
        economicsGe360Id: String,
        correctionGe360Id: String,
        uri: Uri
    ): MetricAgreementAttachmentV5 {
        val quote = getByGe360Id(estimateGe360Id) ?: error("Preventivo non presente")
        val economic = metricVariantEconomics(estimateGe360Id)
            .singleOrNull { it.ge360Id == economicsGe360Id }
            ?: error("Variante economica non trovata")
        require(quote.status != EstimateStatuses.REJECTED) {
            "Non aggiungere documenti a un preventivo rifiutato"
        }
        if (correctionGe360Id.isNotBlank()) {
            require(agreementCorrections(estimateGe360Id).any {
                it.ge360Id == correctionGe360Id &&
                    it.economicsGe360Id == economicsGe360Id
            }) { "Rettifica non trovata nella stessa proposta economica" }
        }
        val copied = MetricAgreementFilesV5.copyFromPicker(
            appContext, uri, quote.ge360Id, economic.ge360Id, correctionGe360Id
        )
        val db = writableDatabase
        try {
            db.beginTransaction()
            try {
                require(getByGe360Id(quote.ge360Id)?.id == quote.id &&
                    metricVariantEconomics(quote.ge360Id).any {
                        it.ge360Id == economic.ge360Id
                    }) { "Preventivo cambiato durante l'allegato" }
                if (correctionGe360Id.isNotBlank()) require(
                    agreementCorrections(quote.ge360Id).any {
                        it.ge360Id == correctionGe360Id &&
                            it.economicsGe360Id == economicsGe360Id
                    }
                ) { "Rettifica cancellata durante la selezione" }
                check(copied.valid() && MetricAgreementFilesV5.existsAndMatches(
                    appContext, copied
                ))
                db.insertOrThrow("estimate_agreement_attachments", null,
                    attachmentValues(quote.id, copied))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (error: Throwable) {
            MetricAgreementFilesV5.fileFor(appContext, copied).delete()
            throw error
        }
        return copied
    }

    private fun verifiedEvidence(
        quoteId: String, economicId: String, correctionId: String
    ): Boolean = agreementAttachments(quoteId).any {
        it.economicsGe360Id == economicId &&
            it.correctionGe360Id == correctionId &&
            MetricAgreementFilesV5.existsAndMatches(appContext, it)
    }

    fun proposeAgreementCorrection(
        estimateGe360Id: String,
        economicsGe360Id: String,
        correctedSignedAmount: Double,
        reason: String
    ): MetricAgreementCorrectionV5 {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val quote = getByGe360Id(estimateGe360Id) ?: error("Preventivo non trovato")
            require(quote.status != EstimateStatuses.REJECTED) {
                "Preventivo rifiutato"
            }
            val original = metricVariantEconomics(quote.ge360Id)
                .singleOrNull { it.ge360Id == economicsGe360Id }
                ?: error("Accordo economico non trovato")
            require(original.valid() &&
                original.status == MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED) {
                "Rettifica possibile solo dopo annotazione di un accordo esterno"
            }
            require(agreementCorrections(quote.ge360Id).none {
                it.economicsGe360Id == economicsGe360Id
            }) { "Rettifica già proposta: non generare doppie revisioni" }
            val correction = MetricAgreementCorrectionV5(
                estimateGe360Id = quote.ge360Id,
                economicsGe360Id = original.ge360Id,
                priorSignedAmountEuro = original.proposedSignedAmountEuro,
                correctedSignedAmountEuro = correctedSignedAmount,
                reason = reason.trim()
            )
            require(correction.valid()) {
                "Importo diverso, stesso segno (extra/riduzione) e motivo obbligatorio"
            }
            db.insertOrThrow("estimate_agreement_corrections", null,
                correctionValues(quote.id, correction))
            val event = MetricAgreementCorrectionEventV5(
                estimateGe360Id = quote.ge360Id,
                correctionGe360Id = correction.ge360Id,
                action = "CORRECTION_PROPOSED",
                signedAmountEuro = correction.correctedSignedAmountEuro,
                reason = correction.reason
            )
            db.insertOrThrow("estimate_agreement_correction_events", null,
                correctionEventValues(quote.id, event))
            db.setTransactionSuccessful()
            return correction
        } finally { db.endTransaction() }
    }

    fun attestAgreementCorrection(
        estimateGe360Id: String,
        correctionGe360Id: String,
        externalReference: String,
        agreementDateIso: String,
        note: String,
        expresslyConfirmed: Boolean
    ): MetricAgreementCorrectionV5 {
        require(expresslyConfirmed) { "Conferma esplicita necessaria" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val quote = getByGe360Id(estimateGe360Id) ?: error("Preventivo non trovato")
            require(quote.status != EstimateStatuses.REJECTED) { "Preventivo rifiutato" }
            val correction = agreementCorrections(quote.ge360Id)
                .singleOrNull { it.ge360Id == correctionGe360Id }
                ?: error("Rettifica inesistente")
            require(correction.valid() &&
                correction.status == MetricAgreementCorrectionV5.STATUS_PROPOSED) {
                "Rettifica già annotata o non valida"
            }
            val economic = metricVariantEconomics(quote.ge360Id)
                .singleOrNull { it.ge360Id == correction.economicsGe360Id }
                ?: error("Origine economica mancante")
            require(economic.status == MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED &&
                kotlin.math.abs(economic.proposedSignedAmountEuro -
                    correction.priorSignedAmountEuro) < 0.001) {
                "L'accordo originale non corrisponde alla rettifica"
            }
            require(verifiedEvidence(quote.ge360Id, economic.ge360Id, correction.ge360Id)) {
                "Allegare un PDF, JPG o PNG verificabile alla rettifica prima di annotarla"
            }
            val updated = correction.copy(
                status = MetricAgreementCorrectionV5.STATUS_ATTESTED,
                agreementReference = externalReference.trim(),
                agreementDateIso = agreementDateIso.trim(),
                evidenceNote = note.trim(),
                attestedAt = maxOf(System.currentTimeMillis(), correction.proposedAt + 1)
            )
            require(updated.valid()) {
                "Riferimento, data ISO e motivazione della nuova accettazione obbligatori"
            }
            val affected = db.update("estimate_agreement_corrections",
                correctionValues(quote.id, updated),
                "ge360_id=? AND status=?", arrayOf(
                    correction.ge360Id, MetricAgreementCorrectionV5.STATUS_PROPOSED
                ))
            check(affected == 1) { "Rettifica già cambiata durante il salvataggio" }
            val event = MetricAgreementCorrectionEventV5(
                estimateGe360Id = quote.ge360Id,
                correctionGe360Id = updated.ge360Id,
                action = "CORRECTION_ATTESTED",
                signedAmountEuro = updated.correctedSignedAmountEuro,
                reason = updated.evidenceNote
            )
            db.insertOrThrow("estimate_agreement_correction_events", null,
                correctionEventValues(quote.id, event))
            db.setTransactionSuccessful()
            return updated
        } finally { db.endTransaction() }
    }

    /** Display only. Supersede earlier amount exactly once, never add both. */
    fun effectiveAgreedVariantsEuro(estimateGe360Id: String): Double {
        val corrections = agreementCorrections(estimateGe360Id)
            .filter { it.status == MetricAgreementCorrectionV5.STATUS_ATTESTED }
            .associateBy { it.economicsGe360Id }
        return metricVariantEconomics(estimateGe360Id)
            .filter { it.status == MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED }
            .sumOf { economics ->
                corrections[economics.ge360Id]?.correctedSignedAmountEuro
                    ?: economics.proposedSignedAmountEuro
            }
    }

    /**
     * Migrate old note-only links conservatively. They retain legacy status and
     * cannot be priced as verified without a fresh operator-reviewed binding.
     */
    private fun backfillLegacyMetricNotes(db: SQLiteDatabase) {
        db.rawQuery("""SELECT i.*, e.id AS parent_estimate_id,
                e.ge360_id AS parent_estimate_uuid
            FROM estimates e JOIN estimate_items i ON i.estimate_id=e.id
            WHERE i.internal_note LIKE '%GE360_METRICO_V1:%'""", null).use { c ->
            while (c.moveToNext()) {
                val estimateId = c.getLong(c.getColumnIndexOrThrow("parent_estimate_id"))
                val estimateUuid = c.getString(c.getColumnIndexOrThrow("parent_estimate_uuid"))
                val item = itemFromCursor(c)
                val parsed = MeasuredEstimateDraftV1.sourceOf(item) ?: continue
                val legacy = MetricProvenanceV2.fromLegacyNote(
                    estimateUuid, item, parsed, System.currentTimeMillis()
                ) ?: continue
                // onUpgrade runs while the helper is opening; never recurse through
                // readableDatabase/metricProvenance from here.
                db.insertWithOnConflict("estimate_metric_sources", null,
                    metricSourceValues(estimateId, legacy), SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    fun list(query: String = "", status: String = "Tutti"): List<EstimateRecord> {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (query.isNotBlank()) {
            val like = "%${query.trim()}%"
            clauses += "(number LIKE ? OR client_name LIKE ? OR jobsite_code LIKE ? OR jobsite_title LIKE ? OR title LIKE ? OR work_type LIKE ?)"
            repeat(6) { args += like }
        }
        if (status != "Tutti") { clauses += "status=?"; args += status }
        val out = mutableListOf<EstimateRecord>()
        readableDatabase.query("estimates", null, clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.takeIf { it.isNotEmpty() }?.toTypedArray(), null, null, "updated_at DESC")
            .use { c -> while (c.moveToNext()) out += recordFromCursor(c, true) }
        return out
    }

    fun get(id: Long): EstimateRecord? = readableDatabase.query("estimates", null, "id=?", arrayOf(id.toString()), null, null, null, "1").use { c -> if (c.moveToFirst()) recordFromCursor(c, true) else null }
    fun getByGe360Id(id: String): EstimateRecord? = if (id.isBlank()) null else readableDatabase.query("estimates", null, "ge360_id=?", arrayOf(id), null, null, null, "1").use { c -> if (c.moveToFirst()) recordFromCursor(c, true) else null }
    fun forJobsite(jobsiteId: String): List<EstimateRecord> = if (jobsiteId.isBlank()) emptyList() else {
        val out = mutableListOf<EstimateRecord>()
        readableDatabase.query("estimates", null, "jobsite_id=?", arrayOf(jobsiteId), null, null, "updated_at DESC").use { c -> while (c.moveToNext()) out += recordFromCursor(c, true) }
        out
    }

    /**
     * Explicit operator-confirmed measured draft import. Inserts only one disabled
     * zero-price row, atomically, without rebuilding historical estimate items.
     * This is NOT an accepted estimate modification or a priced quote line.
     */
    fun insertMeasuredReviewLine(
        estimateGe360Id: String,
        expectedEstimateUpdatedAt: Long,
        source: MeasuredEstimateDraftV1
    ): EstimateItem {
        require(source.valid()) { "Controllare quantità, detrazioni e dati della selezione" }
        val catalog = StandardWorkCatalog.byCode(source.workCode)
            ?: error("Lavorazione del catalogo non trovata")
        require(catalog.unit == "m²") { "La lavorazione deve essere misurata a m²" }
        val item = source.toReviewItem(catalog)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val estimate = getByGe360Id(estimateGe360Id)
                ?: error("Preventivo UUID non trovato")
            require(estimate.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW)) {
                "Solo le bozze o i preventivi da controllare possono ricevere quantità"
            }
            require(estimate.updatedAt == expectedEstimateUpdatedAt) {
                "Preventivo aggiornato da un'altra schermata: riaprire la revisione"
            }
            val lines = estimate.items
            require(lines.none { it.ge360Id == item.ge360Id }) {
                "Questa selezione è già presente nel preventivo"
            }
            val existingSources = metricSourcesForEstimateId(estimate.id)
            existingSources.forEach { origin ->
                require(origin.sourceBindingGe360Id != source.sourceBindingGe360Id) {
                    "Questa selezione è già presente nel registro delle origini"
                }
                if (origin.workCode == source.workCode &&
                    origin.planGe360Id == source.planGe360Id &&
                    origin.roomGe360Id == source.roomGe360Id) {
                    require(!MeasuredEstimateDraftV1.surfacesOverlap(
                        origin.surface, origin.wallGe360Id, source.surface, source.wallGe360Id
                    )) {
                        "Lavorazione già presente su una superficie sovrapposta: verificare la variante"
                    }
                }
            }
            lines.forEach { previous ->
                val existingSource = existingSources.firstOrNull {
                    it.itemGe360Id == previous.ge360Id
                }
                if (existingSource == null) {
                    require(!previous.internalNote.contains(MeasuredEstimateDraftV1.SOURCE_MARKER)) {
                        "Riferimento metrico legacy non riconosciuto: verificare prima il backup"
                    }
                    if (previous.priceListCode == source.workCode &&
                        previous.room.trim().equals(source.roomLabel.trim(), ignoreCase = true)) {
                        require(source.manualOverlapAcknowledged &&
                            source.manualOverlapReason.trim().length >= 8) {
                            "Possibile duplicazione con voce manuale della stanza: motivare la verifica"
                        }
                    }
                }
            }
            // The review item has included=false and unitPrice=0, so totals never increase.
            item.estimateId = estimate.id
            db.insertOrThrow("estimate_items", null, itemValues(item))
            val provenance = MetricProvenanceV2.fromApprovedDraft(estimateGe360Id, source)
            require(provenance.valid()) { "Snapshot origine metratura incompleto" }
            db.insertOrThrow("estimate_metric_sources", null,
                metricSourceValues(estimate.id, provenance))
            val importEvent = MetricReviewEventV2(
                estimateGe360Id = estimateGe360Id,
                itemGe360Id = item.ge360Id,
                action = "IMPORT",
                priorPrice = 0.0,
                newPrice = 0.0,
                wasIncluded = false,
                nowIncluded = false,
                reason = "Revisione manuale della metratura; prima importazione fuori totale"
            )
            db.insertOrThrow("estimate_metric_events", null,
                metricEventValues(estimate.id, importEvent))
            val now = maxOf(System.currentTimeMillis(), estimate.updatedAt + 1)
            val touched = db.update("estimates", ContentValues().apply {
                put("updated_at", now)
            }, "id=? AND updated_at=?", arrayOf(
                estimate.id.toString(), estimate.updatedAt.toString()
            ))
            check(touched == 1) { "Il preventivo è cambiato durante il salvataggio" }
            db.setTransactionSuccessful()
            return item
        } finally { db.endTransaction() }
    }

    /**
     * Normal editor still rewrites quote lines. Sources are independent of rows,
     * and any financial change to a measured line requires explicit confirmation.
     */
    fun save(
        record: EstimateRecord,
        metricConfirmations: Map<String, String> = emptyMap()
    ): Long {
        val editorRevision = record.updatedAt
        val now = maxOf(System.currentTimeMillis(), editorRevision + 1)
        if (record.ge360Id.isBlank()) record.ge360Id = UUID.randomUUID().toString()
        if (record.number.isBlank()) record.number = nextNumber()
        if (record.createdAt <= 0) record.createdAt = now
        record.updatedAt = now
        recalculateEstimate(record)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val reviewEvents = mutableListOf<MetricReviewEventV2>()
            if (record.id > 0) {
                val current = get(record.id) ?: error("Preventivo cancellato o non disponibile")
                val sources = metricSourcesForEstimateId(record.id)
                val lockedCommercialItems = metricVariantEconomics(current.ge360Id)
                    .map { it.sourceItemGe360Id }.toSet()
                if (lockedCommercialItems.isNotEmpty()) {
                    val priorItemIds = current.items.map { it.ge360Id }.toSet()
                    val protectedScopes = sources.filter {
                        it.itemGe360Id in lockedCommercialItems
                    }
                    record.items.filter { it.ge360Id !in priorItemIds }.forEach { newcomer ->
                        require(protectedScopes.none { original ->
                            newcomer.priceListCode == original.workCode &&
                                newcomer.room.trim().equals(
                                    original.roomSnapshot.trim(), ignoreCase = true
                                )
                        }) {
                            "Possibile doppio conteggio: nuovo lavoro uguale ad una " +
                                "variante economica già registrata. Usa il registro Varianti."
                        }
                    }
                }
                val hasLegacyWithoutSource = current.items.any {
                    it.internalNote.contains(MeasuredEstimateDraftV1.SOURCE_MARKER) &&
                        sources.none { origin -> origin.itemGe360Id == it.ge360Id }
                }
                require(!hasLegacyWithoutSource) {
                    "Origine della metratura non recuperabile: ripristinare o verificare prima di salvare"
                }
                if (sources.isNotEmpty()) {
                    require(current.updatedAt == editorRevision) {
                        "Preventivo aggiornato da un'altra schermata: riaprire prima di salvare"
                    }
                }
                sources.forEach { origin ->
                    val before = current.items.singleOrNull { it.ge360Id == origin.itemGe360Id }
                        ?: error("Riga con provenienza assente nel preventivo")
                    val after = record.items.singleOrNull { it.ge360Id == origin.itemGe360Id }
                        ?: error("Per eliminare una metratura usare la gestione varianti, non la cancellazione")
                    require(after.priceListCode == origin.workCode &&
                        after.room == origin.roomSnapshot &&
                        after.unit == "m²" && after.priceMode == "unit" &&
                        kotlin.math.abs(after.quantity - origin.reviewedQuantityM2) <= 0.001 &&
                        !after.optional) {
                        "Origine, ambiente e quantità non modificabili: rivedere il rilievo"
                    }
                    val changed = before.included != after.included ||
                        kotlin.math.abs(before.unitPrice - after.unitPrice) > 0.001
                    if (changed) {
                        require(origin.itemGe360Id !in lockedCommercialItems) {
                            "Esiste già una proposta o accordo economico per questa voce: " +
                                "prezzo e inclusione storici bloccati per evitare doppi conteggi"
                        }
                        val reason = metricConfirmations[origin.itemGe360Id].orEmpty().trim()
                        require(origin.trust == MetricProvenanceV2.TRUST_VERIFIED &&
                            current.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW) &&
                            record.status in setOf(EstimateStatuses.DRAFT, EstimateStatuses.REVIEW) &&
                            reason.length >= 8) {
                            "Variazione economica metrata: richiede conferma motivata su bozza e origine verificata"
                        }
                        require(after.unitPrice.isFinite() && after.unitPrice >= 0.0 &&
                            (!after.included || after.unitPrice > 0.0)) {
                            "La voce inclusa nel totale deve avere un prezzo valido e positivo"
                        }
                        reviewEvents += MetricReviewEventV2(
                            estimateGe360Id = record.ge360Id,
                            itemGe360Id = origin.itemGe360Id,
                            action = "PRICE_REVIEW",
                            priorPrice = before.unitPrice,
                            newPrice = after.unitPrice,
                            wasIncluded = before.included,
                            nowIncluded = after.included,
                            reason = reason
                        )
                    }
                }
            }
            val id = if (record.id > 0) {
                val updated = db.update("estimates", recordValues(record),
                    "id=? AND updated_at=?", arrayOf(record.id.toString(), editorRevision.toString()))
                check(updated == 1) { "Preventivo cambiato durante il salvataggio" }
                record.id
            } else db.insertOrThrow("estimates", null, recordValues(record)).also { record.id = it }
            db.delete("estimate_items", "estimate_id=?", arrayOf(id.toString()))
            record.items.forEach { item ->
                item.estimateId = id
                if (item.ge360Id.isBlank()) item.ge360Id = UUID.randomUUID().toString()
                item.total = calculateItemTotal(item)
                item.id = db.insertOrThrow("estimate_items", null, itemValues(item))
            }
            reviewEvents.forEach { event ->
                check(event.valid())
                db.insertOrThrow("estimate_metric_events", null, metricEventValues(id, event))
            }
            db.setTransactionSuccessful()
            return id
        } finally { db.endTransaction() }
    }

    fun delete(id: Long) {
        val current = get(id) ?: return
        require(metricVariants(current.ge360Id).isEmpty() &&
            metricVariantEconomics(current.ge360Id).isEmpty()) {
            "Il preventivo contiene varianti e uno storico economico: non cancellarlo da qui"
        }
        writableDatabase.delete("estimates", "id=?", arrayOf(id.toString()))
    }
    fun duplicate(source: EstimateRecord): EstimateRecord = source.copy(
        id = 0, ge360Id = UUID.randomUUID().toString(), legacyId = "", number = "",
        status = EstimateStatuses.DRAFT,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        items = source.items.map { line ->
            val measured = metricProvenance(line.ge360Id) != null ||
                line.internalNote.contains(MeasuredEstimateDraftV1.SOURCE_MARKER)
            line.copy(
                id = 0, ge360Id = UUID.randomUUID().toString(), estimateId = 0,
                unitPrice = if (measured) 0.0 else line.unitPrice,
                included = if (measured) false else line.included,
                internalNote = if (measured)
                    "Copia informativa: la metratura deve essere nuovamente verificata nel rilievo"
                else line.internalNote,
                description = if (measured)
                    line.description + "\n[METRATURA COPIATA DA RIVERIFICARE]"
                else line.description
            )
        }.toMutableList()
    ).also { save(it) }

    fun priceList(query: String = ""): List<PriceEntry> {
        val out = mutableListOf<PriceEntry>()
        val where = if (query.isBlank()) "active=1" else "active=1 AND (code LIKE ? OR name LIKE ? OR category LIKE ?)"
        val args = if (query.isBlank()) null else Array(3) { "%${query.trim()}%" }
        readableDatabase.query("price_list", null, where, args, null, null, "category,name").use { c -> while (c.moveToNext()) out += priceFromCursor(c) }
        return out
    }

    fun savePrice(entry: PriceEntry): Long {
        if (entry.code.isBlank()) entry.code = "CUS-${UUID.randomUUID().toString().take(8).uppercase()}"
        entry.updatedAt = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("code", entry.code.trim()); put("name", entry.name.trim()); put("category", entry.category.trim().ifBlank { "Personalizzate" }); put("unit", entry.unit.ifBlank { "corpo" })
            if (entry.suggestedPrice == null) putNull("suggested_price") else put("suggested_price", entry.suggestedPrice)
            put("price_mode", entry.priceMode); put("active", if (entry.active) 1 else 0); put("updated_at", entry.updatedAt)
        }
        return if (entry.id > 0) { writableDatabase.update("price_list", v, "id=?", arrayOf(entry.id.toString())); entry.id }
        else writableDatabase.insertWithOnConflict("price_list", null, v, SQLiteDatabase.CONFLICT_REPLACE).also { entry.id = it }
    }

    fun exportJson(): String = JSONObject().apply {
        put("schema", "ge360.preventivi.v1")
        put("exportedAt", System.currentTimeMillis())
        val estimates = list()
        put("estimates", JSONArray().apply { estimates.forEach { put(recordToJson(it)) } })
        put("priceList", JSONArray().apply { priceList().forEach { put(priceToJson(it)) } })
        put("metricProvenance", JSONArray().apply {
            estimates.forEach { estimate ->
                metricSourcesForEstimateId(estimate.id).forEach { put(it.toJson()) }
            }
        })
        put("metricReviewEvents", JSONArray().apply {
            estimates.forEach { estimate ->
                metricEvents(estimate.ge360Id).forEach { put(it.toJson()) }
            }
        })
        put("metricVariants", JSONArray().apply {
            estimates.forEach { estimate ->
                metricVariants(estimate.ge360Id).forEach { put(it.toJson()) }
            }
        })
        put("metricVariantEvents", JSONArray().apply {
            estimates.forEach { estimate ->
                metricVariantEvents(estimate.ge360Id).forEach { put(it.toJson()) }
            }
        })
        put("metricVariantEconomics", JSONArray().apply {
            estimates.forEach { estimate ->
                metricVariantEconomics(estimate.ge360Id).forEach { put(it.toJson()) }
            }
        })
        put("metricVariantEconomicsEvents", JSONArray().apply {
            estimates.forEach { estimate ->
                metricVariantEconomicEvents(estimate.ge360Id).forEach { put(it.toJson()) }
            }
        })
    }.toString(2)

    /**
     * Single database transaction for records + authoritative metric lineage.
     * Old backups with no sidecar metadata are accepted but note-only lineage is
     * rebuilt as LEGACY_NOTE_ONLY, never silently promoted to verified.
     */
    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        val native = root.optString("schema") == "ge360.preventivi.v1"
        val provenanceRows = if (!native) emptyList() else root.optJSONArray("metricProvenance")
            ?.let { a -> (0 until a.length()).map { MetricProvenanceV2.fromJson(a.getJSONObject(it)) } }
            ?: emptyList()
        val eventRows = if (!native) emptyList() else root.optJSONArray("metricReviewEvents")
            ?.let { a -> (0 until a.length()).map { MetricReviewEventV2.fromJson(a.getJSONObject(it)) } }
            ?: emptyList()
        require(provenanceRows.all { it.valid() } &&
            provenanceRows.distinctBy { it.itemGe360Id }.size == provenanceRows.size &&
            provenanceRows.distinctBy {
                it.estimateGe360Id to it.sourceBindingGe360Id
            }.size == provenanceRows.size) { "Duplicazione o origine metrica non valida nel backup" }
        require(eventRows.all { it.valid() } &&
            eventRows.distinctBy { it.ge360Id }.size == eventRows.size) {
            "Storico metrico non valido nel backup"
        }
        val variantRows = if (!native) emptyList() else root.optJSONArray("metricVariants")
            ?.let { a -> (0 until a.length()).map { MetricVariantV3.fromJson(a.getJSONObject(it)) } }
            ?: emptyList()
        val variantEventRows = if (!native) emptyList() else root.optJSONArray("metricVariantEvents")
            ?.let { a -> (0 until a.length()).map { MetricVariantEventV3.fromJson(a.getJSONObject(it)) } }
            ?: emptyList()
        require(variantRows.all { it.valid() } &&
            variantRows.distinctBy { it.ge360Id }.size == variantRows.size &&
            variantRows.distinctBy {
                it.estimateGe360Id to it.candidateBindingGe360Id
            }.size == variantRows.size &&
            variantRows.distinctBy {
                it.estimateGe360Id to it.previousItemGe360Id
            }.size == variantRows.size) {
            "Varianti duplicate o non valide nel backup"
        }
        require(variantEventRows.all { it.valid() } &&
            variantEventRows.distinctBy { it.ge360Id }.size == variantEventRows.size) {
            "Eventi varianti duplicati o non validi"
        }
        val economicsRows = if (!native) emptyList() else root.optJSONArray("metricVariantEconomics")
            ?.let { a ->
                (0 until a.length()).map { MetricVariantEconomicsV4.fromJson(a.getJSONObject(it)) }
            } ?: emptyList()
        val economicsEvents = if (!native) emptyList()
            else root.optJSONArray("metricVariantEconomicsEvents")?.let { a ->
                (0 until a.length()).map {
                    MetricVariantEconomicsEventV4.fromJson(a.getJSONObject(it))
                }
            } ?: emptyList()
        require(economicsRows.all { it.valid() } &&
            economicsRows.distinctBy { it.ge360Id }.size == economicsRows.size &&
            economicsRows.distinctBy {
                it.estimateGe360Id to it.variantGe360Id
            }.size == economicsRows.size) {
            "Proposte economiche duplicate o non valide"
        }
        require(economicsEvents.all { it.valid() } &&
            economicsEvents.distinctBy { it.ge360Id }.size == economicsEvents.size) {
            "Eventi accordi economici duplicati o non validi"
        }
        require(economicsEvents.all { event -> economicsRows.any {
            it.ge360Id == event.economicsGe360Id &&
                it.estimateGe360Id == event.estimateGe360Id &&
                kotlin.math.abs(it.proposedSignedAmountEuro - event.signedAmountEuro) < 0.001
        } }) { "Evento economico senza proposta coerente nel backup" }
        economicsRows.forEach { record ->
            val events = economicsEvents.filter {
                it.economicsGe360Id == record.ge360Id &&
                    it.estimateGe360Id == record.estimateGe360Id
            }
            val agreements = events.filter { it.action == "AGREEMENT_ATTESTED" }
            require(events.count {
                it.action == "ECONOMIC_PROPOSAL" &&
                    it.evidenceReference.isBlank() &&
                    kotlin.math.abs(it.signedAmountEuro - record.proposedSignedAmountEuro) < 0.001
            } == 1 && (if (record.status ==
                MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED)
                    agreements.size == 1 &&
                        agreements.single().evidenceReference ==
                        record.externalAgreementReference &&
                        kotlin.math.abs(agreements.single().signedAmountEuro -
                            record.proposedSignedAmountEuro) < 0.001
                else agreements.isEmpty())
            ) { "Proposta o annotazione senza cronologia economica corrispondente" }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (replaceAll) {
                db.delete("estimate_items", null, null)
                db.delete("estimates", null, null)
            }
            val imported = if (native) importNative(root) else importLegacy(root)
            provenanceRows.forEach { origin ->
                val est = getByGe360Id(origin.estimateGe360Id)
                    ?: error("Origine riferita a preventivo inesistente")
                val item = est.items.singleOrNull { it.ge360Id == origin.itemGe360Id }
                    ?: error("Origine riferita a voce economica inesistente")
                require(item.priceListCode == origin.workCode &&
                    item.room == origin.roomSnapshot && item.unit == "m²" &&
                    kotlin.math.abs(item.quantity - origin.reviewedQuantityM2) <= 0.001) {
                    "La voce importata non coincide con lo snapshot metrico"
                }
                val old = metricProvenance(origin.itemGe360Id)
                if (old == null) db.insertOrThrow("estimate_metric_sources", null,
                    metricSourceValues(est.id, origin))
                else require(old == origin) {
                    "UUID origine metratura presente con contenuto divergente"
                }
            }
            eventRows.forEach { event ->
                val est = getByGe360Id(event.estimateGe360Id)
                    ?: error("Evento di revisione con preventivo inesistente")
                require(metricProvenance(event.itemGe360Id)?.estimateGe360Id == event.estimateGe360Id) {
                    "Evento di revisione senza origine metrica"
                }
                val existing = db.query("estimate_metric_events", arrayOf("event_json"),
                    "ge360_id=?", arrayOf(event.ge360Id), null, null, null, "1").use {
                    c -> if (c.moveToFirst()) MetricReviewEventV2.fromJson(
                        JSONObject(c.getString(0))) else null
                }
                if (existing == null) db.insertOrThrow("estimate_metric_events", null,
                    metricEventValues(est.id, event))
                else require(existing == event) {
                    "UUID evento metrico presente con dati divergenti"
                }
            }
            variantRows.forEach { variant ->
                val estimate = getByGe360Id(variant.estimateGe360Id)
                    ?: error("Variante senza preventivo")
                val source = metricProvenance(variant.previousItemGe360Id)
                    ?: error("Variante senza origine precedente")
                require(source.trust == MetricProvenanceV2.TRUST_VERIFIED &&
                    source.estimateGe360Id == variant.estimateGe360Id &&
                    source.sourceBindingGe360Id == variant.previousBindingGe360Id &&
                    source.geometryFingerprint == variant.previousFingerprint &&
                    source.planGe360Id == variant.planGe360Id &&
                    source.roomGe360Id == variant.roomGe360Id &&
                    source.workCode == variant.workCode &&
                    source.surface == variant.surface &&
                    source.wallGe360Id.orEmpty() == variant.wallGe360Id.orEmpty()) {
                    "Variante incoerente con la fonte originale"
                }
                val prior = estimate.items.singleOrNull {
                    it.ge360Id == variant.previousItemGe360Id
                } ?: error("Variante senza voce storica del preventivo")
                require(kotlin.math.abs(prior.quantity - variant.previousQuantityM2) < 0.001 &&
                    kotlin.math.abs(prior.unitPrice - variant.priorUnitPrice) < 0.001 &&
                    (prior.included && !prior.optional) == variant.priorIncluded) {
                    "Il prezzo o la quantità storica non coincide con la variante importata"
                }
                val current = metricVariants(estimate.ge360Id)
                    .singleOrNull { it.ge360Id == variant.ge360Id }
                if (current == null) db.insertOrThrow("estimate_metric_variants",
                    null, variantValues(estimate.id, variant))
                else require(current == variant) {
                    "Conflitto UUID variante tra archivio locale e backup"
                }
            }
            variantEventRows.forEach { event ->
                val quote = getByGe360Id(event.estimateGe360Id)
                    ?: error("Evento variante senza preventivo")
                require(metricVariants(quote.ge360Id).any {
                    it.ge360Id == event.variantGe360Id
                }) { "Evento variante privo del relativo snapshot" }
                val current = db.query("estimate_metric_variant_events", arrayOf("event_json"),
                    "ge360_id=?", arrayOf(event.ge360Id), null, null, null, "1").use { c ->
                        if (c.moveToFirst()) MetricVariantEventV3.fromJson(
                            JSONObject(c.getString(0))) else null
                    }
                if (current == null) db.insertOrThrow("estimate_metric_variant_events",
                    null, variantEventValues(quote.id, event))
                else require(current == event) { "Conflitto UUID evento variante" }
            }
            economicsRows.forEach { economics ->
                val quote = getByGe360Id(economics.estimateGe360Id)
                    ?: error("Accordo economico senza preventivo")
                val technical = metricVariants(quote.ge360Id).singleOrNull {
                    it.ge360Id == economics.variantGe360Id
                } ?: error("Accordo economico senza variante tecnica")
                require(technical.status == MetricVariantV3.STATUS_TECH_REVIEWED &&
                    technical.previousItemGe360Id == economics.sourceItemGe360Id &&
                    kotlin.math.abs(technical.deltaQuantityM2() - economics.deltaM2) < 0.001) {
                    "Accordo economico incoerente con la variante tecnica"
                }
                val original = quote.items.singleOrNull {
                    it.ge360Id == economics.sourceItemGe360Id
                } ?: error("Voce economica originale non disponibile")
                require(kotlin.math.abs(original.quantity - technical.previousQuantityM2) < 0.001 &&
                    kotlin.math.abs(original.unitPrice - technical.priorUnitPrice) < 0.001) {
                    "Importo originale differente dal backup dell'accordo"
                }
                val existing = metricVariantEconomics(quote.ge360Id).singleOrNull {
                    it.ge360Id == economics.ge360Id
                }
                if (existing == null) db.insertOrThrow("estimate_metric_economics",
                    null, economicValues(quote.id, economics))
                else require(existing == economics) {
                    "Accordo economico già esistente con valori differenti"
                }
            }
            economicsEvents.forEach { event ->
                val quote = getByGe360Id(event.estimateGe360Id)
                    ?: error("Audit economico senza preventivo")
                require(metricVariantEconomics(quote.ge360Id).any {
                    it.ge360Id == event.economicsGe360Id &&
                        kotlin.math.abs(it.proposedSignedAmountEuro - event.signedAmountEuro) < 0.001
                }) { "Evento economico senza proposta coerente" }
                val existing = db.query("estimate_metric_economics_events",
                    arrayOf("event_json"), "ge360_id=?",
                    arrayOf(event.ge360Id), null, null, null, "1").use { c ->
                    if (c.moveToFirst()) MetricVariantEconomicsEventV4.fromJson(
                        JSONObject(c.getString(0))) else null
                }
                if (existing == null) db.insertOrThrow("estimate_metric_economics_events",
                    null, economicEventValues(quote.id, event))
                else require(existing == event) { "Evento economico già esistente ma diverso" }
            }
            // Recreate missing historical note-only sources as *unverified*.
            backfillLegacyMetricNotes(db)
            db.setTransactionSuccessful()
            return imported
        } finally { db.endTransaction() }
    }

    private fun importNative(root: JSONObject): Int {
        root.optJSONArray("priceList")?.let { a -> for (i in 0 until a.length()) savePrice(priceFromJson(a.getJSONObject(i))) }
        val rows = root.optJSONArray("estimates") ?: JSONArray(); var count = 0
        for (i in 0 until rows.length()) {
            val r = recordFromJson(rows.getJSONObject(i)); getByGe360Id(r.ge360Id)?.let { r.id = it.id }; save(r); count++
        }
        return count
    }

    private fun importLegacy(root: JSONObject): Int {
        root.optJSONArray("priceList")?.let { a ->
            for (i in 0 until a.length()) {
                val p = a.getJSONObject(i)
                savePrice(PriceEntry(code=p.optString("code","LEG-${i+1}"), name=p.optString("name","Lavorazione"), category=p.optString("category","Personalizzate"), unit=p.optString("unit","corpo"), suggestedPrice=if(p.has("suggestedPrice")&&!p.isNull("suggestedPrice"))p.optDouble("suggestedPrice")else null, priceMode=p.optString("priceMode","unit")))
            }
        }
        val separate = root.optJSONArray("estimateItems") ?: JSONArray()
        val grouped = mutableMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until separate.length()) { val o=separate.getJSONObject(i); grouped.getOrPut(o.optString("estimateId","")){ mutableListOf() }.add(o) }
        val estimates = root.optJSONArray("estimates") ?: JSONArray(); var count=0
        for (i in 0 until estimates.length()) {
            val src=estimates.getJSONObject(i); val legacyId=src.optString("id",""); val client=src.optJSONObject("client")?:JSONObject(); val est=src.optJSONObject("estimate")?:JSONObject()
            val r=EstimateRecord(
                ge360Id=legacyId.ifBlank{UUID.randomUUID().toString()}, legacyId=legacyId, clientName=client.optString("name",""), title=est.optString("title",""), workType=est.optString("type",""),
                status=EstimateStatuses.fromLegacy(src.optString("status","draft")), documentType=est.optString("documentType","provvisorio").replaceFirstChar{it.uppercase()}, validityDays=est.optInt("validityDays",30),
                generalDescription=est.optString("generalDescription",""), materialsIncluded=est.optString("materialsIncluded",""), materialsClient=est.optString("materialsClient",""), exclusions=est.optString("exclusions",""), finalNotes=est.optString("finalNotes",""),
                discountType=est.optJSONObject("discount")?.optString("type","none")?:"none", discountValue=est.optJSONObject("discount")?.optDouble("value",0.0)?:0.0,
                surchargeType=est.optJSONObject("surcharge")?.optString("type","none")?:"none", surchargeValue=est.optJSONObject("surcharge")?.optDouble("value",0.0)?:0.0, rounding=est.optDouble("rounding",0.0)
            )
            val itemSources=mutableListOf<JSONObject>(); est.optJSONArray("items")?.let{a->for(j in 0 until a.length())itemSources+=a.getJSONObject(j)}
            if(itemSources.isEmpty())itemSources+=grouped[legacyId].orEmpty()
            r.items=itemSources.map(::legacyItem).toMutableList(); getByGe360Id(r.ge360Id)?.let{r.id=it.id}; save(r); count++
        }
        return count
    }

    private fun legacyItem(o: JSONObject)=EstimateItem(
        ge360Id=o.optString("id",UUID.randomUUID().toString()), priceListCode=o.optString("priceListCode",""), section=o.optString("sectionId","Resto della casa"), room=o.optString("roomName",""),
        category=o.optString("category","Personalizzate"), name=o.optString("name","Lavorazione"), description=o.optString("description",""), quantity=o.optDouble("quantity",1.0), unit=o.optString("unit","corpo"),
        unitPrice=o.optDouble("appliedPrice",o.optDouble("suggestedPrice",0.0)), priceMode=o.optString("priceMode","unit"), included=o.optBoolean("active",true)&&o.optString("status","included")=="included", optional=o.optString("status","")=="optional", internalNote=o.optString("internalNote","")
    )

    private fun nextNumber(): String {
        val prefix="PREV-${LocalDate.now().year}-"; var max=0
        readableDatabase.rawQuery("SELECT number FROM estimates WHERE number LIKE ?", arrayOf("$prefix%" )).use { c -> while(c.moveToNext()) max=maxOf(max,c.getString(0).removePrefix(prefix).toIntOrNull()?:0) }
        return prefix+(max+1).toString().padStart(3,'0')
    }

    private fun loadRecordItems(estimateId: Long): MutableList<EstimateItem> {
        val out=mutableListOf<EstimateItem>()
        readableDatabase.query("estimate_items",null,"estimate_id=?",arrayOf(estimateId.toString()),null,null,"id").use{c->while(c.moveToNext())out+=itemFromCursor(c)}
        return out
    }

    private fun recordFromCursor(c: Cursor, withItems: Boolean)=EstimateRecord(
        id=c.long("id"),ge360Id=c.str("ge360_id"),legacyId=c.str("legacy_id"),number=c.str("number"),clientId=c.long("client_id"),clientGe360Id=c.str("client_ge360_id"),clientName=c.str("client_name"),
        jobsiteId=c.str("jobsite_id"),jobsiteCode=c.str("jobsite_code"),jobsiteTitle=c.str("jobsite_title"),title=c.str("title"),workType=c.str("work_type"),status=c.str("status"),documentType=c.str("document_type"),validityDays=c.int("validity_days"),
        generalDescription=c.str("general_description"),materialsIncluded=c.str("materials_included"),materialsClient=c.str("materials_client"),exclusions=c.str("exclusions"),finalNotes=c.str("final_notes"),
        discountType=c.str("discount_type"),discountValue=c.dbl("discount_value"),surchargeType=c.str("surcharge_type"),surchargeValue=c.dbl("surcharge_value"),rounding=c.dbl("rounding"),subtotal=c.dbl("subtotal"),finalTotal=c.dbl("final_total"),createdAt=c.long("created_at"),updatedAt=c.long("updated_at"),items=if(withItems)loadRecordItems(c.long("id"))else mutableListOf()
    )

    private fun itemFromCursor(c:Cursor)=EstimateItem(id=c.long("id"),ge360Id=c.str("ge360_id"),estimateId=c.long("estimate_id"),priceListCode=c.str("price_list_code"),section=c.str("section_name"),room=c.str("room_name"),category=c.str("category"),name=c.str("name"),description=c.str("description"),quantity=c.dbl("quantity"),unit=c.str("unit"),unitPrice=c.dbl("unit_price"),priceMode=c.str("price_mode"),included=c.int("included")==1,optional=c.int("optional")==1,internalNote=c.str("internal_note"),total=c.dbl("total"))
    private fun priceFromCursor(c:Cursor)=PriceEntry(id=c.long("id"),code=c.str("code"),name=c.str("name"),category=c.str("category"),unit=c.str("unit"),suggestedPrice=if(c.isNull(c.getColumnIndexOrThrow("suggested_price")))null else c.dbl("suggested_price"),priceMode=c.str("price_mode"),active=c.int("active")==1,updatedAt=c.long("updated_at"))

    private fun recordValues(r:EstimateRecord)=ContentValues().apply{
        put("ge360_id",r.ge360Id);put("legacy_id",r.legacyId);put("number",r.number);put("client_id",r.clientId);put("client_ge360_id",r.clientGe360Id);put("client_name",r.clientName);put("jobsite_id",r.jobsiteId);put("jobsite_code",r.jobsiteCode);put("jobsite_title",r.jobsiteTitle);put("title",r.title);put("work_type",r.workType);put("status",r.status);put("document_type",r.documentType);put("validity_days",r.validityDays);put("general_description",r.generalDescription);put("materials_included",r.materialsIncluded);put("materials_client",r.materialsClient);put("exclusions",r.exclusions);put("final_notes",r.finalNotes);put("discount_type",r.discountType);put("discount_value",r.discountValue);put("surcharge_type",r.surchargeType);put("surcharge_value",r.surchargeValue);put("rounding",r.rounding);put("subtotal",r.subtotal);put("final_total",r.finalTotal);put("created_at",r.createdAt);put("updated_at",r.updatedAt)
    }
    private fun itemValues(i:EstimateItem)=ContentValues().apply{put("ge360_id",i.ge360Id);put("estimate_id",i.estimateId);put("price_list_code",i.priceListCode);put("section_name",i.section);put("room_name",i.room);put("category",i.category);put("name",i.name);put("description",i.description);put("quantity",i.quantity);put("unit",i.unit);put("unit_price",i.unitPrice);put("price_mode",i.priceMode);put("included",if(i.included)1 else 0);put("optional",if(i.optional)1 else 0);put("internal_note",i.internalNote);put("total",i.total)}

    private fun recordToJson(r:EstimateRecord)=JSONObject().apply{put("ge360Id",r.ge360Id);put("legacyId",r.legacyId);put("number",r.number);put("clientId",r.clientId);put("clientGe360Id",r.clientGe360Id);put("clientName",r.clientName);put("jobsiteId",r.jobsiteId);put("jobsiteCode",r.jobsiteCode);put("jobsiteTitle",r.jobsiteTitle);put("title",r.title);put("workType",r.workType);put("status",r.status);put("documentType",r.documentType);put("validityDays",r.validityDays);put("generalDescription",r.generalDescription);put("materialsIncluded",r.materialsIncluded);put("materialsClient",r.materialsClient);put("exclusions",r.exclusions);put("finalNotes",r.finalNotes);put("discountType",r.discountType);put("discountValue",r.discountValue);put("surchargeType",r.surchargeType);put("surchargeValue",r.surchargeValue);put("rounding",r.rounding);put("createdAt",r.createdAt);put("updatedAt",r.updatedAt);put("items",JSONArray().apply{r.items.forEach{put(itemToJson(it))}})}
    private fun recordFromJson(o:JSONObject)=EstimateRecord(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),legacyId=o.optString("legacyId",""),number=o.optString("number",""),clientId=o.optLong("clientId",0),clientGe360Id=o.optString("clientGe360Id",""),clientName=o.optString("clientName",""),jobsiteId=o.optString("jobsiteId",""),jobsiteCode=o.optString("jobsiteCode",""),jobsiteTitle=o.optString("jobsiteTitle",""),title=o.optString("title",""),workType=o.optString("workType",""),status=o.optString("status",EstimateStatuses.DRAFT),documentType=o.optString("documentType","Provvisorio"),validityDays=o.optInt("validityDays",30),generalDescription=o.optString("generalDescription",""),materialsIncluded=o.optString("materialsIncluded",""),materialsClient=o.optString("materialsClient",""),exclusions=o.optString("exclusions",""),finalNotes=o.optString("finalNotes",""),discountType=o.optString("discountType","none"),discountValue=o.optDouble("discountValue",0.0),surchargeType=o.optString("surchargeType","none"),surchargeValue=o.optDouble("surchargeValue",0.0),rounding=o.optDouble("rounding",0.0),createdAt=o.optLong("createdAt",System.currentTimeMillis()),updatedAt=o.optLong("updatedAt",System.currentTimeMillis()),items=mutableListOf<EstimateItem>().apply{val a=o.optJSONArray("items")?:JSONArray();for(i in 0 until a.length())add(itemFromJson(a.getJSONObject(i)))})
    private fun itemToJson(i:EstimateItem)=JSONObject().apply{put("ge360Id",i.ge360Id);put("priceListCode",i.priceListCode);put("section",i.section);put("room",i.room);put("category",i.category);put("name",i.name);put("description",i.description);put("quantity",i.quantity);put("unit",i.unit);put("unitPrice",i.unitPrice);put("priceMode",i.priceMode);put("included",i.included);put("optional",i.optional);put("internalNote",i.internalNote)}
    private fun itemFromJson(o:JSONObject)=EstimateItem(ge360Id=o.optString("ge360Id",UUID.randomUUID().toString()),priceListCode=o.optString("priceListCode",""),section=o.optString("section","Resto della casa"),room=o.optString("room",""),category=o.optString("category","Personalizzate"),name=o.optString("name","Lavorazione"),description=o.optString("description",""),quantity=o.optDouble("quantity",1.0),unit=o.optString("unit","corpo"),unitPrice=o.optDouble("unitPrice",0.0),priceMode=o.optString("priceMode","unit"),included=o.optBoolean("included",true),optional=o.optBoolean("optional",false),internalNote=o.optString("internalNote",""))
    private fun priceToJson(p:PriceEntry)=JSONObject().apply{put("code",p.code);put("name",p.name);put("category",p.category);put("unit",p.unit);if(p.suggestedPrice==null)put("suggestedPrice",JSONObject.NULL)else put("suggestedPrice",p.suggestedPrice);put("priceMode",p.priceMode);put("active",p.active)}
    private fun priceFromJson(o:JSONObject)=PriceEntry(code=o.optString("code",""),name=o.optString("name","Lavorazione"),category=o.optString("category","Personalizzate"),unit=o.optString("unit","corpo"),suggestedPrice=if(o.has("suggestedPrice")&&!o.isNull("suggestedPrice"))o.optDouble("suggestedPrice")else null,priceMode=o.optString("priceMode","unit"),active=o.optBoolean("active",true))

    private fun seedPriceList(db:SQLiteDatabase){
        val now=System.currentTimeMillis()
        val seeds=listOf(
            arrayOf("RAS-RETE-001","Rasatura completa con rete","Rasature e pitturazioni","m²","20","unit"),arrayOf("PIT-LAV-001","Pitturazione lavabile","Rasature e pitturazioni","m²","10","unit"),arrayOf("PIT-BIA-001","Pitturazione bianca","Rasature e pitturazioni","m²","12","unit"),
            arrayOf("PAV-SPC-001","Posa pavimento SPC","Pavimenti","m²","21","unit"),arrayOf("PAV-PIA-001","Posa piastrelle","Pavimenti","m²","40","unit"),arrayOf("PAV-AUT-001","Autolivellante","Pavimenti","m²","40","unit"),arrayOf("IMP-NAN-001","Impermeabilizzazione Nanoflex","Pavimenti","m²","40","unit"),
            arrayOf("BAT-001","Posa battiscopa","Battiscopa e profili","ml","8","unit"),arrayOf("CAR-CON-001","Controsoffitto completo cartongesso","Cartongesso","m²","70","unit"),arrayOf("CAR-MUR-001","Muro cartongesso con pannelli OSB forniti","Cartongesso","m²","65","unit"),
            arrayOf("BAG-PIC-001","Bagno piccolo completo","Bagni a corpo","corpo","3800","body"),arrayOf("BAG-GRA-001","Bagno grande completo","Bagni a corpo","corpo","4200","body"),arrayOf("BAG-STD-001","Bagno completo standard","Bagni a corpo","corpo","4300","body"),
            arrayOf("TRAC-500","Chiusura tracce — intervento limitato","Chiusura tracce","corpo","500","body"),arrayOf("TRAC-900","Chiusura tracce — intervento medio-esteso","Chiusura tracce","corpo","900","body"),arrayOf("TRAC-1500","Chiusura tracce — intervento esteso","Chiusura tracce","corpo","1500","body")
        )
        seeds.forEach{s->db.insert("price_list",null,ContentValues().apply{put("code",s[0]);put("name",s[1]);put("category",s[2]);put("unit",s[3]);put("suggested_price",s[4].toDouble());put("price_mode",s[5]);put("active",1);put("updated_at",now)})}
    }

    private fun Cursor.str(name:String)=getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name:String)=getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name:String)=getInt(getColumnIndexOrThrow(name))
    private fun Cursor.dbl(name:String)=getDouble(getColumnIndexOrThrow(name))

    companion object {
        val WORK_TYPES=listOf("Ristrutturazione bagno","Ristrutturazione intero appartamento","Pitturazione e rasatura","Pavimenti e battiscopa","Cartongesso e opere murarie","Lavorazioni personalizzate")
        val UNITS=listOf("corpo","m²","ml","cad.","ora","giornata","forfait")
        val SECTIONS=listOf("Bagno","Resto della casa","Esterni","Opere extra")
        private const val DB_NAME="ge360_preventivi.db"
        private const val DB_VERSION=5
    }
}
