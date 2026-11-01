package it.edilmilan.ge360.preventivi

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/** Immutable record; path is derived from UUID, not restored from arbitrary JSON. */
data class MetricAgreementAttachmentV5(
    val ge360Id: String = UUID.randomUUID().toString(),
    val estimateGe360Id: String,
    val economicsGe360Id: String,
    val correctionGe360Id: String = "",
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val capturedAt: Long = System.currentTimeMillis()
) {
    fun valid() = runCatching {
        UUID.fromString(ge360Id).toString() == ge360Id.lowercase()
    }.getOrDefault(false) &&
        estimateGe360Id.isNotBlank() && economicsGe360Id.isNotBlank() &&
        fileName.isNotBlank() && fileName.length <= 120 &&
        mimeType in setOf("application/pdf", "image/jpeg", "image/png") &&
        sizeBytes in 1L..MetricAgreementFilesV5.MAX_BYTES &&
        sha256.matches(Regex("[0-9a-f]{64}")) && capturedAt > 0

    fun toJson() = JSONObject()
        .put("ge360Id", ge360Id).put("estimateGe360Id", estimateGe360Id)
        .put("economicsGe360Id", economicsGe360Id)
        .put("correctionGe360Id", correctionGe360Id)
        .put("fileName", fileName).put("mimeType", mimeType)
        .put("sizeBytes", sizeBytes).put("sha256", sha256)
        .put("capturedAt", capturedAt)

    companion object {
        fun fromJson(o: JSONObject) = MetricAgreementAttachmentV5(
            ge360Id = o.getString("ge360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            economicsGe360Id = o.getString("economicsGe360Id"),
            correctionGe360Id = o.optString("correctionGe360Id"),
            fileName = o.getString("fileName"),
            mimeType = o.getString("mimeType"),
            sizeBytes = o.getLong("sizeBytes"),
            sha256 = o.getString("sha256"),
            capturedAt = o.getLong("capturedAt")
        )
    }
}

/** Append-only commercial correction; the earlier agreement remains untouched. */
data class MetricAgreementCorrectionV5(
    val ge360Id: String = UUID.randomUUID().toString(),
    val estimateGe360Id: String,
    val economicsGe360Id: String,
    val priorSignedAmountEuro: Double,
    val correctedSignedAmountEuro: Double,
    val reason: String,
    val proposedAt: Long = System.currentTimeMillis(),
    val status: String = STATUS_PROPOSED,
    val agreementReference: String = "",
    val agreementDateIso: String = "",
    val evidenceNote: String = "",
    val attestedAt: Long = 0
) {
    fun adjustmentEuro(): Double = BigDecimal.valueOf(correctedSignedAmountEuro)
        .subtract(BigDecimal.valueOf(priorSignedAmountEuro))
        .setScale(2, RoundingMode.HALF_UP).toDouble()

    fun valid(): Boolean = runCatching {
        require(ge360Id.isNotBlank() && estimateGe360Id.isNotBlank() &&
            economicsGe360Id.isNotBlank() && reason.trim().length >= 8 &&
            proposedAt > 0 && priorSignedAmountEuro.isFinite() &&
            correctedSignedAmountEuro.isFinite())
        require(priorSignedAmountEuro != 0.0 &&
            correctedSignedAmountEuro != 0.0 &&
            kotlin.math.abs(priorSignedAmountEuro) < 100_000_000.0 &&
            kotlin.math.abs(correctedSignedAmountEuro) < 100_000_000.0 &&
            (priorSignedAmountEuro > 0) == (correctedSignedAmountEuro > 0) &&
            kotlin.math.abs(adjustmentEuro()) >= 0.01)
        require(status in setOf(STATUS_PROPOSED, STATUS_ATTESTED))
        if (status == STATUS_PROPOSED) {
            require(agreementReference.isBlank() && agreementDateIso.isBlank() &&
                evidenceNote.isBlank() && attestedAt == 0L)
        } else {
            require(agreementReference.trim().length >= 8 &&
                evidenceNote.trim().length >= 8 && attestedAt >= proposedAt &&
                !LocalDate.parse(agreementDateIso).isAfter(LocalDate.now()))
        }
        true
    }.getOrDefault(false)

    fun toJson() = JSONObject()
        .put("ge360Id", ge360Id).put("estimateGe360Id", estimateGe360Id)
        .put("economicsGe360Id", economicsGe360Id)
        .put("priorSignedAmountEuro", priorSignedAmountEuro)
        .put("correctedSignedAmountEuro", correctedSignedAmountEuro)
        .put("reason", reason).put("proposedAt", proposedAt)
        .put("status", status).put("agreementReference", agreementReference)
        .put("agreementDateIso", agreementDateIso)
        .put("evidenceNote", evidenceNote).put("attestedAt", attestedAt)

    companion object {
        const val STATUS_PROPOSED = "RETTIFICA_PROPOSTA"
        const val STATUS_ATTESTED = "RETTIFICA_ACCORDO_ANNOTATO"
        fun fromJson(o: JSONObject) = MetricAgreementCorrectionV5(
            ge360Id = o.getString("ge360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            economicsGe360Id = o.getString("economicsGe360Id"),
            priorSignedAmountEuro = o.getDouble("priorSignedAmountEuro"),
            correctedSignedAmountEuro = o.getDouble("correctedSignedAmountEuro"),
            reason = o.getString("reason"),
            proposedAt = o.getLong("proposedAt"),
            status = o.getString("status"),
            agreementReference = o.optString("agreementReference"),
            agreementDateIso = o.optString("agreementDateIso"),
            evidenceNote = o.optString("evidenceNote"),
            attestedAt = o.optLong("attestedAt", 0)
        )
    }
}

/** Separate append-only events documenting both proposal and later operator attestation. */
data class MetricAgreementCorrectionEventV5(
    val ge360Id: String = UUID.randomUUID().toString(),
    val estimateGe360Id: String,
    val correctionGe360Id: String,
    val action: String,
    val signedAmountEuro: Double,
    val reason: String,
    val occurredAt: Long = System.currentTimeMillis()
) {
    fun valid(): Boolean = ge360Id.isNotBlank() && estimateGe360Id.isNotBlank() &&
        correctionGe360Id.isNotBlank() &&
        action in setOf("CORRECTION_PROPOSED", "CORRECTION_ATTESTED") &&
        signedAmountEuro.isFinite() && signedAmountEuro != 0.0 &&
        reason.trim().length >= 8 && occurredAt > 0

    fun toJson() = JSONObject().put("ge360Id", ge360Id)
        .put("estimateGe360Id", estimateGe360Id)
        .put("correctionGe360Id", correctionGe360Id)
        .put("action", action).put("signedAmountEuro", signedAmountEuro)
        .put("reason", reason).put("occurredAt", occurredAt)
    companion object {
        fun fromJson(o: JSONObject) = MetricAgreementCorrectionEventV5(
            ge360Id = o.getString("ge360Id"),
            estimateGe360Id = o.getString("estimateGe360Id"),
            correctionGe360Id = o.getString("correctionGe360Id"),
            action = o.getString("action"),
            signedAmountEuro = o.getDouble("signedAmountEuro"),
            reason = o.getString("reason"),
            occurredAt = o.getLong("occurredAt")
        )
    }
}
