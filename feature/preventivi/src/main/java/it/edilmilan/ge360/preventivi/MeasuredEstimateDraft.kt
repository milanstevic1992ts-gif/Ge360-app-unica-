package it.edilmilan.ge360.preventivi

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * One confirmed transfer of a geometrical quantity into an EXCLUDED zero-price
 * review line. No authoritative price, billing event or accepted quote mutation.
 */
data class MeasuredEstimateDraftV1(
    val sourceBindingGe360Id: String,
    val planGe360Id: String,
    val roomGe360Id: String,
    val wallGe360Id: String?,
    val roomLabel: String,
    val workCode: String,
    val surface: String,
    val geometryFingerprint: String,
    val measuredQuantityM2: Double,
    val grossM2: Double,
    val openingDeductionsM2: Double,
    val additionalDeductionM2: Double,
    val deductionReason: String,
    val manualOverlapAcknowledged: Boolean = false,
    val manualOverlapReason: String = ""
) {
    fun reviewedQuantityM2(): Double {
        fun two(v: Double): BigDecimal {
            require(v.isFinite() && v >= 0.0) { "Quantità non valida" }
            return BigDecimal.valueOf(v).setScale(2, RoundingMode.UNNECESSARY)
        }
        val base = two(measuredQuantityM2)
        val extra = two(additionalDeductionM2)
        require(extra < base) { "La detrazione manuale deve essere inferiore alla quantità misurata" }
        return base.subtract(extra).toDouble()
    }

    fun valid(): Boolean = runCatching {
        require(sourceBindingGe360Id.isNotBlank() && planGe360Id.isNotBlank() &&
            roomGe360Id.isNotBlank() && roomLabel.isNotBlank() &&
            workCode.isNotBlank() && surface in SURFACES &&
            geometryFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(grossM2.isFinite() && grossM2 > 0 &&
            openingDeductionsM2.isFinite() && openingDeductionsM2 >= 0 &&
            kotlin.math.abs(grossM2 - openingDeductionsM2 - measuredQuantityM2) <= 0.02)
        require((surface in SINGLE_WALL_SURFACES) == !wallGe360Id.isNullOrBlank())
        reviewedQuantityM2()
        require(additionalDeductionM2 == 0.0 || deductionReason.isNotBlank()) {
            "Motivo della detrazione obbligatorio"
        }
        require(!manualOverlapAcknowledged || manualOverlapReason.isNotBlank()) {
            "Motivo di conferma della possibile duplicazione obbligatorio"
        }
        true
    }.getOrDefault(false)

    fun stableItemId(): String = UUID.nameUUIDFromBytes(
        ("GE360_ESTIMATE_MEASURED_V1:" + sourceBindingGe360Id)
            .toByteArray(StandardCharsets.UTF_8)
    ).toString()

    fun sourceNote(): String = SOURCE_MARKER + JSONObject()
        .put("binding", sourceBindingGe360Id)
        .put("plan", planGe360Id)
        .put("room", roomGe360Id)
        .put("wall", wallGe360Id.orEmpty())
        .put("work", workCode)
        .put("surface", surface)
        .put("geometry", geometryFingerprint)
        .put("grossM2", grossM2)
        .put("apertureM2", openingDeductionsM2)
        .put("misuratoM2", measuredQuantityM2)
        .put("deduzioneManualeM2", additionalDeductionM2)
        .put("motivoDeduzione", deductionReason)
        .put("verificaSovrapposizione", manualOverlapAcknowledged)
        .put("motivoVerifica", manualOverlapReason)
        .toString()

    fun toReviewItem(entry: StandardWorkEntry): EstimateItem {
        require(valid() && entry.code == workCode && entry.unit == "m²")
        return entry.toEstimateItem().apply {
            ge360Id = stableItemId()
            room = roomLabel.trim()
            quantity = reviewedQuantityM2()
            unit = "m²"
            unitPrice = 0.0
            priceMode = "unit"
            included = false
            optional = false
            total = 0.0
            internalNote = sourceNote() +
                "\nIMPORT INIZIALE: voce fuori totale e prezzo zero. " +
                    "Qualsiasi successivo prezzo o inclusione richiede controllo e conferma manuale."
        }
    }

    companion object {
        const val SOURCE_MARKER = "GE360_METRICO_V1:"
        val SURFACES = setOf(
            "PAVIMENTO", "SOFFITTO", "PARETI", "PARETI_NETTE",
            "PARETE_SINGOLA_LORDA", "PARETE_SINGOLA_NETTA"
        )
        private val SINGLE_WALL_SURFACES =
            setOf("PARETE_SINGOLA_LORDA", "PARETE_SINGOLA_NETTA")
        private val ROOM_WALL_SURFACES = setOf("PARETI", "PARETI_NETTE")
        fun sourceOf(item: EstimateItem): JSONObject? {
            val start = item.internalNote.indexOf(SOURCE_MARKER)
            if (start < 0) return null
            val encoded = item.internalNote.substring(start + SOURCE_MARKER.length)
                .lineSequence().firstOrNull().orEmpty()
            return runCatching { JSONObject(encoded) }.getOrNull()
        }
        fun surfacesOverlap(a: String, aWall: String?, b: String, bWall: String?): Boolean {
            if (a == "PAVIMENTO" || b == "PAVIMENTO") return a == b
            if (a == "SOFFITTO" || b == "SOFFITTO") return a == b
            if (a in ROOM_WALL_SURFACES || b in ROOM_WALL_SURFACES) return true
            if (a in SINGLE_WALL_SURFACES && b in SINGLE_WALL_SURFACES)
                return aWall.orEmpty() == bWall.orEmpty()
            return true // Unknown scopes fail closed.
        }
    }
}
