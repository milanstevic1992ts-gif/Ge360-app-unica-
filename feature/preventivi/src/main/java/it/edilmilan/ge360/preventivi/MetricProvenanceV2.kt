package it.edilmilan.ge360.preventivi

import org.json.JSONObject
import java.util.UUID

/**
 * Immutable source snapshot stored in a dedicated SQLite table. The internal note
 * remains human-readable, but is NOT authoritative for pricing or provenance.
 * Legacy note-only sources are intentionally unverified.
 */
data class MetricProvenanceV2(
    val itemGe360Id: String,
    val estimateGe360Id: String,
    val sourceBindingGe360Id: String,
    val planGe360Id: String,
    val roomGe360Id: String,
    val wallGe360Id: String?,
    val workCode: String,
    val surface: String,
    val geometryFingerprint: String,
    val roomSnapshot: String,
    val grossM2: Double,
    val openingDeductionsM2: Double,
    val additionalDeductionM2: Double,
    val reviewedQuantityM2: Double,
    val deductionReason: String,
    val trust: String = TRUST_VERIFIED,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun valid(): Boolean = itemGe360Id.isNotBlank() && estimateGe360Id.isNotBlank() &&
        sourceBindingGe360Id.isNotBlank() && planGe360Id.isNotBlank() &&
        roomGe360Id.isNotBlank() && roomSnapshot.isNotBlank() &&
        workCode.isNotBlank() && surface in MeasuredEstimateDraftV1.SURFACES &&
        geometryFingerprint.matches(Regex("[0-9a-f]{64}")) &&
        listOf(grossM2, openingDeductionsM2, additionalDeductionM2,
            reviewedQuantityM2).all { it.isFinite() && it >= 0.0 } &&
        reviewedQuantityM2 > 0.0 &&
        kotlin.math.abs(grossM2 - openingDeductionsM2 -
            additionalDeductionM2 - reviewedQuantityM2) <= 0.02 &&
        (surface.startsWith("PARETE_SINGOLA_") == !wallGe360Id.isNullOrBlank()) &&
        (additionalDeductionM2 == 0.0 || deductionReason.isNotBlank()) &&
        trust in setOf(TRUST_VERIFIED, TRUST_LEGACY) && createdAt > 0

    fun toJson(): JSONObject = JSONObject()
        .put("itemGe360Id", itemGe360Id)
        .put("estimateGe360Id", estimateGe360Id)
        .put("sourceBindingGe360Id", sourceBindingGe360Id)
        .put("planGe360Id", planGe360Id)
        .put("roomGe360Id", roomGe360Id)
        .put("wallGe360Id", wallGe360Id.orEmpty())
        .put("workCode", workCode)
        .put("surface", surface)
        .put("geometryFingerprint", geometryFingerprint)
        .put("roomSnapshot", roomSnapshot)
        .put("grossM2", grossM2)
        .put("openingDeductionsM2", openingDeductionsM2)
        .put("additionalDeductionM2", additionalDeductionM2)
        .put("reviewedQuantityM2", reviewedQuantityM2)
        .put("deductionReason", deductionReason)
        .put("trust", trust)
        .put("createdAt", createdAt)

    companion object {
        const val TRUST_VERIFIED = "REVIEWED_FROM_GEOMETRY"
        const val TRUST_LEGACY = "LEGACY_NOTE_ONLY"

        fun fromApprovedDraft(estimateId: String, source: MeasuredEstimateDraftV1) =
            MetricProvenanceV2(
                itemGe360Id = source.stableItemId(),
                estimateGe360Id = estimateId,
                sourceBindingGe360Id = source.sourceBindingGe360Id,
                planGe360Id = source.planGe360Id,
                roomGe360Id = source.roomGe360Id,
                wallGe360Id = source.wallGe360Id,
                workCode = source.workCode,
                surface = source.surface,
                geometryFingerprint = source.geometryFingerprint,
                roomSnapshot = source.roomLabel.trim(),
                grossM2 = source.grossM2,
                openingDeductionsM2 = source.openingDeductionsM2,
                additionalDeductionM2 = source.additionalDeductionM2,
                reviewedQuantityM2 = source.reviewedQuantityM2(),
                deductionReason = source.deductionReason
            )

        fun fromJson(o: JSONObject) = MetricProvenanceV2(
            itemGe360Id = o.getString("itemGe360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            sourceBindingGe360Id = o.getString("sourceBindingGe360Id"),
            planGe360Id = o.getString("planGe360Id"),
            roomGe360Id = o.getString("roomGe360Id"),
            wallGe360Id = o.optString("wallGe360Id").ifBlank { null },
            workCode = o.getString("workCode"),
            surface = o.getString("surface"),
            geometryFingerprint = o.getString("geometryFingerprint"),
            roomSnapshot = o.getString("roomSnapshot"),
            grossM2 = o.getDouble("grossM2"),
            openingDeductionsM2 = o.getDouble("openingDeductionsM2"),
            additionalDeductionM2 = o.getDouble("additionalDeductionM2"),
            reviewedQuantityM2 = o.getDouble("reviewedQuantityM2"),
            deductionReason = o.optString("deductionReason", ""),
            trust = o.getString("trust"),
            createdAt = o.getLong("createdAt")
        )

        /** Backfill existing older note-only imports without asserting trust. */
        fun fromLegacyNote(
            estimateId: String, item: EstimateItem,
            original: JSONObject, createdAt: Long
        ): MetricProvenanceV2? = runCatching {
            MetricProvenanceV2(
                itemGe360Id = item.ge360Id,
                estimateGe360Id = estimateId,
                sourceBindingGe360Id = original.getString("binding"),
                planGe360Id = original.getString("plan"),
                roomGe360Id = original.getString("room"),
                wallGe360Id = original.optString("wall").ifBlank { null },
                workCode = original.getString("work"),
                surface = original.getString("surface"),
                geometryFingerprint = original.getString("geometry"),
                roomSnapshot = item.room,
                grossM2 = original.getDouble("grossM2"),
                openingDeductionsM2 = original.getDouble("apertureM2"),
                additionalDeductionM2 = original.optDouble("deduzioneManualeM2", 0.0),
                reviewedQuantityM2 = item.quantity,
                deductionReason = original.optString("motivoDeduzione", ""),
                trust = TRUST_LEGACY,
                createdAt = createdAt
            ).takeIf { it.valid() }
        }.getOrNull()
    }
}

/** Persisted audit, not a digital signature or invoice. Rows are append-only in the app. */
data class MetricReviewEventV2(
    val ge360Id: String = UUID.randomUUID().toString(),
    val estimateGe360Id: String,
    val itemGe360Id: String,
    val action: String,
    val priorPrice: Double,
    val newPrice: Double,
    val wasIncluded: Boolean,
    val nowIncluded: Boolean,
    val reason: String,
    val occurredAt: Long = System.currentTimeMillis()
) {
    fun valid(): Boolean = ge360Id.isNotBlank() && estimateGe360Id.isNotBlank() &&
        itemGe360Id.isNotBlank() && action in setOf("IMPORT", "PRICE_REVIEW") &&
        priorPrice.isFinite() && priorPrice >= 0.0 &&
        newPrice.isFinite() && newPrice >= 0.0 &&
        reason.isNotBlank() && occurredAt > 0

    fun toJson(): JSONObject = JSONObject()
        .put("ge360Id", ge360Id).put("estimateGe360Id", estimateGe360Id)
        .put("itemGe360Id", itemGe360Id).put("action", action)
        .put("priorPrice", priorPrice).put("newPrice", newPrice)
        .put("wasIncluded", wasIncluded).put("nowIncluded", nowIncluded)
        .put("reason", reason).put("occurredAt", occurredAt)

    companion object {
        fun fromJson(o: JSONObject) = MetricReviewEventV2(
            ge360Id = o.getString("ge360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            itemGe360Id = o.getString("itemGe360Id"),
            action = o.getString("action"),
            priorPrice = o.getDouble("priorPrice"),
            newPrice = o.getDouble("newPrice"),
            wasIncluded = o.getBoolean("wasIncluded"),
            nowIncluded = o.getBoolean("nowIncluded"),
            reason = o.getString("reason"),
            occurredAt = o.getLong("occurredAt")
        )
    }
}
