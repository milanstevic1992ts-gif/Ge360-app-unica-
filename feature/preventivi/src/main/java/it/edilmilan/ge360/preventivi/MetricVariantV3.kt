package it.edilmilan.ge360.preventivi

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.abs

/**
 * Non-destructive variation proposal. The old priced item is never replaced;
 * no quote totals, payments, invoices or SAL are changed by this entity.
 */
data class MetricVariantV3(
    val ge360Id: String = UUID.randomUUID().toString(),
    val estimateGe360Id: String,
    val previousItemGe360Id: String,
    val previousBindingGe360Id: String,
    val candidateBindingGe360Id: String,
    val planGe360Id: String,
    val roomGe360Id: String,
    val wallGe360Id: String?,
    val workCode: String,
    val surface: String,
    val previousFingerprint: String,
    val candidateFingerprint: String,
    val previousQuantityM2: Double,
    val proposedQuantityM2: Double,
    val candidateGrossM2: Double,
    val candidateOpeningDeductionsM2: Double,
    val candidateAdditionalDeductionsM2: Double,
    val priorUnitPrice: Double,
    val priorIncluded: Boolean,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = STATUS_PROPOSED,
    val reviewReason: String = "",
    val reviewedAt: Long = 0L
) {
    fun deltaQuantityM2(): Double = round2(proposedQuantityM2 - previousQuantityM2)

    /** Reference only; never a booked or invoiced delta. */
    fun referenceDeltaEuro(): Double = if (!priorIncluded) 0.0 else
        BigDecimal.valueOf(deltaQuantityM2())
            .multiply(BigDecimal.valueOf(priorUnitPrice))
            .setScale(2, RoundingMode.HALF_UP).toDouble()

    fun valid(): Boolean = ge360Id.isNotBlank() && estimateGe360Id.isNotBlank() &&
        previousItemGe360Id.isNotBlank() && previousBindingGe360Id.isNotBlank() &&
        candidateBindingGe360Id.isNotBlank() &&
        previousBindingGe360Id != candidateBindingGe360Id &&
        planGe360Id.isNotBlank() && roomGe360Id.isNotBlank() &&
        workCode.isNotBlank() && surface in MeasuredEstimateDraftV1.SURFACES &&
        ((surface.startsWith("PARETE_SINGOLA_")) == !wallGe360Id.isNullOrBlank()) &&
        previousFingerprint.matches(Regex("[0-9a-f]{64}")) &&
        candidateFingerprint.matches(Regex("[0-9a-f]{64}")) &&
        previousFingerprint != candidateFingerprint &&
        listOf(previousQuantityM2, proposedQuantityM2, candidateGrossM2,
            candidateOpeningDeductionsM2, candidateAdditionalDeductionsM2,
            priorUnitPrice).all { it.isFinite() && it >= 0.0 } &&
        previousQuantityM2 > 0.0 && proposedQuantityM2 > 0.0 &&
        abs(deltaQuantityM2()) >= 0.01 &&
        abs(candidateGrossM2 - candidateOpeningDeductionsM2 -
            candidateAdditionalDeductionsM2 - proposedQuantityM2) <= 0.02 &&
        reason.trim().length >= 8 && createdAt > 0 &&
        status in setOf(STATUS_PROPOSED, STATUS_TECH_REVIEWED) &&
        (status == STATUS_PROPOSED && reviewedAt == 0L && reviewReason.isBlank() ||
            status == STATUS_TECH_REVIEWED && reviewedAt >= createdAt &&
                reviewReason.trim().length >= 8)

    fun toJson(): JSONObject = JSONObject()
        .put("ge360Id", ge360Id).put("estimateGe360Id", estimateGe360Id)
        .put("previousItemGe360Id", previousItemGe360Id)
        .put("previousBindingGe360Id", previousBindingGe360Id)
        .put("candidateBindingGe360Id", candidateBindingGe360Id)
        .put("planGe360Id", planGe360Id).put("roomGe360Id", roomGe360Id)
        .put("wallGe360Id", wallGe360Id.orEmpty())
        .put("workCode", workCode).put("surface", surface)
        .put("previousFingerprint", previousFingerprint)
        .put("candidateFingerprint", candidateFingerprint)
        .put("previousQuantityM2", previousQuantityM2)
        .put("proposedQuantityM2", proposedQuantityM2)
        .put("candidateGrossM2", candidateGrossM2)
        .put("candidateOpeningDeductionsM2", candidateOpeningDeductionsM2)
        .put("candidateAdditionalDeductionsM2", candidateAdditionalDeductionsM2)
        .put("priorUnitPrice", priorUnitPrice).put("priorIncluded", priorIncluded)
        .put("reason", reason).put("createdAt", createdAt)
        .put("status", status).put("reviewReason", reviewReason)
        .put("reviewedAt", reviewedAt)

    companion object {
        const val STATUS_PROPOSED = "PROPOSTA"
        const val STATUS_TECH_REVIEWED = "VERIFICATA_TECNICAMENTE"
        private fun round2(value: Double): Double =
            BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()

        fun fromJson(o: JSONObject) = MetricVariantV3(
            ge360Id = o.getString("ge360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            previousItemGe360Id = o.getString("previousItemGe360Id"),
            previousBindingGe360Id = o.getString("previousBindingGe360Id"),
            candidateBindingGe360Id = o.getString("candidateBindingGe360Id"),
            planGe360Id = o.getString("planGe360Id"),
            roomGe360Id = o.getString("roomGe360Id"),
            wallGe360Id = o.optString("wallGe360Id").ifBlank { null },
            workCode = o.getString("workCode"),
            surface = o.getString("surface"),
            previousFingerprint = o.getString("previousFingerprint"),
            candidateFingerprint = o.getString("candidateFingerprint"),
            previousQuantityM2 = o.getDouble("previousQuantityM2"),
            proposedQuantityM2 = o.getDouble("proposedQuantityM2"),
            candidateGrossM2 = o.getDouble("candidateGrossM2"),
            candidateOpeningDeductionsM2 = o.getDouble("candidateOpeningDeductionsM2"),
            candidateAdditionalDeductionsM2 = o.getDouble("candidateAdditionalDeductionsM2"),
            priorUnitPrice = o.getDouble("priorUnitPrice"),
            priorIncluded = o.getBoolean("priorIncluded"),
            reason = o.getString("reason"),
            createdAt = o.getLong("createdAt"),
            status = o.getString("status"),
            reviewReason = o.optString("reviewReason", ""),
            reviewedAt = o.optLong("reviewedAt", 0)
        )
    }
}

/** Append-only proof of user actions, not a digital signature. */
data class MetricVariantEventV3(
    val ge360Id: String = UUID.randomUUID().toString(),
    val variantGe360Id: String,
    val estimateGe360Id: String,
    val action: String,
    val reason: String,
    val occurredAt: Long = System.currentTimeMillis()
) {
    fun valid(): Boolean = ge360Id.isNotBlank() && variantGe360Id.isNotBlank() &&
        estimateGe360Id.isNotBlank() && action in setOf("PROPOSED", "TECH_REVIEWED") &&
        reason.trim().length >= 8 && occurredAt > 0
    fun toJson(): JSONObject = JSONObject()
        .put("ge360Id", ge360Id).put("variantGe360Id", variantGe360Id)
        .put("estimateGe360Id", estimateGe360Id).put("action", action)
        .put("reason", reason).put("occurredAt", occurredAt)
    companion object {
        fun fromJson(o: JSONObject) = MetricVariantEventV3(
            ge360Id = o.getString("ge360Id"),
            variantGe360Id = o.getString("variantGe360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            action = o.getString("action"),
            reason = o.getString("reason"),
            occurredAt = o.getLong("occurredAt")
        )
    }
}
