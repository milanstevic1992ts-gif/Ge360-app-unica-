package it.edilmilan.ge360.preventivi

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/**
 * Separate commercial addendum for an already technically reviewed metric variant.
 *
 * IMPORTANT: Neither a quote item nor a receivable, SAL, invoice or payment.
 * "Accordo annotato" is a manual operator attestation referencing external evidence,
 * never an electronic signature or proof of customer acceptance.
 */
data class MetricVariantEconomicsV4(
    val ge360Id: String = UUID.randomUUID().toString(),
    val estimateGe360Id: String,
    val variantGe360Id: String,
    val sourceItemGe360Id: String,
    val deltaM2: Double,
    val kind: String,
    val pricingMode: String,
    val proposedUnitRateEuro: Double,
    val proposedLumpSumEuro: Double,
    val proposedSignedAmountEuro: Double,
    val proposalReason: String,
    val proposedAt: Long = System.currentTimeMillis(),
    val status: String = STATUS_PROPOSED,
    val externalAgreementReference: String = "",
    val externalAgreementDate: String = "",
    val evidenceNote: String = "",
    val attestedAt: Long = 0L
) {
    fun valid(): Boolean = runCatching {
        require(ge360Id.isNotBlank() && estimateGe360Id.isNotBlank() &&
            variantGe360Id.isNotBlank() && sourceItemGe360Id.isNotBlank())
        require(deltaM2.isFinite() && abs(deltaM2) >= 0.01 &&
            abs(deltaM2) < 1_000_000.0)
        require(kind == if (deltaM2 > 0) KIND_EXTRA else KIND_REDUCTION)
        require(pricingMode in setOf(MODE_UNIT, MODE_LUMP_SUM))
        require(proposedUnitRateEuro.isFinite() && proposedLumpSumEuro.isFinite() &&
            proposedUnitRateEuro >= 0.0 && proposedLumpSumEuro >= 0.0)
        require((pricingMode == MODE_UNIT && proposedLumpSumEuro == 0.0) ||
            (pricingMode == MODE_LUMP_SUM && proposedUnitRateEuro == 0.0))
        val expected = if (pricingMode == MODE_UNIT)
            BigDecimal.valueOf(abs(deltaM2))
                .multiply(BigDecimal.valueOf(proposedUnitRateEuro))
        else BigDecimal.valueOf(proposedLumpSumEuro)
        val signed = expected.setScale(2, RoundingMode.HALF_UP).let {
            if (deltaM2 > 0) it else it.negate()
        }
        require(proposedSignedAmountEuro.isFinite() &&
            BigDecimal.valueOf(proposedSignedAmountEuro)
                .compareTo(signed) == 0 &&
            signed.signum() != 0)
        require(proposalReason.trim().length >= 8 && proposedAt > 0)
        require(status in setOf(STATUS_PROPOSED, STATUS_EVIDENCE_RECORDED))
        if (status == STATUS_PROPOSED) {
            require(externalAgreementReference.isBlank() &&
                externalAgreementDate.isBlank() && evidenceNote.isBlank() && attestedAt == 0L)
        } else {
            require(externalAgreementReference.trim().length >= 8 &&
                evidenceNote.trim().length >= 8 && attestedAt >= proposedAt)
            LocalDate.parse(externalAgreementDate)
        }
        true
    }.getOrDefault(false)

    fun toJson(): JSONObject = JSONObject()
        .put("ge360Id", ge360Id)
        .put("estimateGe360Id", estimateGe360Id)
        .put("variantGe360Id", variantGe360Id)
        .put("sourceItemGe360Id", sourceItemGe360Id)
        .put("deltaM2", deltaM2).put("kind", kind)
        .put("pricingMode", pricingMode)
        .put("proposedUnitRateEuro", proposedUnitRateEuro)
        .put("proposedLumpSumEuro", proposedLumpSumEuro)
        .put("proposedSignedAmountEuro", proposedSignedAmountEuro)
        .put("proposalReason", proposalReason)
        .put("proposedAt", proposedAt)
        .put("status", status)
        .put("externalAgreementReference", externalAgreementReference)
        .put("externalAgreementDate", externalAgreementDate)
        .put("evidenceNote", evidenceNote)
        .put("attestedAt", attestedAt)

    companion object {
        const val KIND_EXTRA = "LAVORO_AGGIUNTIVO"
        const val KIND_REDUCTION = "RIDUZIONE"
        const val MODE_UNIT = "PREZZO_AL_M2"
        const val MODE_LUMP_SUM = "IMPORTO_A_CORPO"
        const val STATUS_PROPOSED = "PROPOSTA_ECONOMICA"
        const val STATUS_EVIDENCE_RECORDED = "ACCORDO_ANNOTATO"

        fun create(
            variant: MetricVariantV3,
            pricingMode: String,
            price: Double,
            reason: String
        ): MetricVariantEconomicsV4 {
            require(variant.valid() &&
                variant.status == MetricVariantV3.STATUS_TECH_REVIEWED) {
                "Serve la verifica tecnica della variante"
            }
            require(price.isFinite() && price > 0.0 && price < 100_000_000.0)
            require(reason.trim().length >= 8)
            val magnitude = if (pricingMode == MODE_UNIT) {
                BigDecimal.valueOf(abs(variant.deltaQuantityM2()))
                    .multiply(BigDecimal.valueOf(price))
            } else {
                require(pricingMode == MODE_LUMP_SUM) { "Prezzo a corpo o al metro" }
                BigDecimal.valueOf(price)
            }
            val amount = magnitude.setScale(2, RoundingMode.HALF_UP)
            require(amount.signum() > 0)
            val signed = if (variant.deltaQuantityM2() > 0) amount else amount.negate()
            return MetricVariantEconomicsV4(
                estimateGe360Id = variant.estimateGe360Id,
                variantGe360Id = variant.ge360Id,
                sourceItemGe360Id = variant.previousItemGe360Id,
                deltaM2 = variant.deltaQuantityM2(),
                kind = if (signed.signum() > 0) KIND_EXTRA else KIND_REDUCTION,
                pricingMode = pricingMode,
                proposedUnitRateEuro = if (pricingMode == MODE_UNIT) price else 0.0,
                proposedLumpSumEuro = if (pricingMode == MODE_LUMP_SUM) price else 0.0,
                proposedSignedAmountEuro = signed.toDouble(),
                proposalReason = reason.trim()
            ).also { require(it.valid()) }
        }

        fun fromJson(o: JSONObject) = MetricVariantEconomicsV4(
            ge360Id = o.getString("ge360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            variantGe360Id = o.getString("variantGe360Id"),
            sourceItemGe360Id = o.getString("sourceItemGe360Id"),
            deltaM2 = o.getDouble("deltaM2"),
            kind = o.getString("kind"),
            pricingMode = o.getString("pricingMode"),
            proposedUnitRateEuro = o.getDouble("proposedUnitRateEuro"),
            proposedLumpSumEuro = o.getDouble("proposedLumpSumEuro"),
            proposedSignedAmountEuro = o.getDouble("proposedSignedAmountEuro"),
            proposalReason = o.getString("proposalReason"),
            proposedAt = o.getLong("proposedAt"),
            status = o.getString("status"),
            externalAgreementReference = o.optString("externalAgreementReference", ""),
            externalAgreementDate = o.optString("externalAgreementDate", ""),
            evidenceNote = o.optString("evidenceNote", ""),
            attestedAt = o.optLong("attestedAt", 0L)
        )
    }
}

data class MetricVariantEconomicsEventV4(
    val ge360Id: String = UUID.randomUUID().toString(),
    val economicsGe360Id: String,
    val estimateGe360Id: String,
    val action: String,
    val signedAmountEuro: Double,
    val evidenceReference: String = "",
    val reason: String,
    val occurredAt: Long = System.currentTimeMillis()
) {
    fun valid(): Boolean = ge360Id.isNotBlank() && economicsGe360Id.isNotBlank() &&
        estimateGe360Id.isNotBlank() &&
        action in setOf("ECONOMIC_PROPOSAL", "AGREEMENT_ATTESTED") &&
        signedAmountEuro.isFinite() && signedAmountEuro != 0.0 &&
        reason.trim().length >= 8 && occurredAt > 0 &&
        (action != "AGREEMENT_ATTESTED" || evidenceReference.trim().length >= 8)

    fun toJson(): JSONObject = JSONObject()
        .put("ge360Id", ge360Id).put("economicsGe360Id", economicsGe360Id)
        .put("estimateGe360Id", estimateGe360Id).put("action", action)
        .put("signedAmountEuro", signedAmountEuro)
        .put("evidenceReference", evidenceReference)
        .put("reason", reason).put("occurredAt", occurredAt)

    companion object {
        fun fromJson(o: JSONObject) = MetricVariantEconomicsEventV4(
            ge360Id = o.getString("ge360Id"),
            economicsGe360Id = o.getString("economicsGe360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            action = o.getString("action"),
            signedAmountEuro = o.getDouble("signedAmountEuro"),
            evidenceReference = o.optString("evidenceReference", ""),
            reason = o.getString("reason"),
            occurredAt = o.getLong("occurredAt")
        )
    }
}
