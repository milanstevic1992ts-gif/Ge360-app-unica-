package it.edilmilan.ge360.attrezzi

/**
 * Operator-confirmed allocation of a material quantity appearing in BOTH an
 * invoice line and a DDT line. It does not create a purchase, delivery, stock
 * movement, payment, expense, or assertion of document authenticity.
 *
 * A single invoice may be matched to many DDTs and vice versa, but the total
 * active allocations cannot exceed either original confirmed quantity.
 */
data class MaterialInvoiceDdtMatchV14(
    val ge360Id: String,
    val invoiceLineGe360Id: String,
    val ddtLineGe360Id: String,
    val quantity: Double,
    val unit: String,
    val confirmedAt: Long,
    val voidedAt: Long = 0L
) {
    init {
        require(ge360Id.isNotBlank() && invoiceLineGe360Id.isNotBlank() &&
            ddtLineGe360Id.isNotBlank() && invoiceLineGe360Id != ddtLineGe360Id)
        require(quantity.isFinite() && quantity > 0.0 && unit.isNotBlank())
        require(confirmedAt > 0L && (voidedAt == 0L || voidedAt >= confirmedAt))
    }
    val active: Boolean get() = voidedAt == 0L
}

object MaterialReconciliationRulesV14 {
    private const val EPSILON = 0.0000001

    data class Availability(
        val invoicedRemaining: Double,
        val deliveredRemaining: Double,
        val maximumMatch: Double
    )

    /**
     * Reject stale, duplicate, cross-material/jobsite and unit-incompatible
     * claims, including over-allocation across multiple distinct documents.
     * Historical voided rows are retained but do not consume any capacity.
     */
    fun validate(
        lines: List<MaterialDocumentLineV13>,
        existing: List<MaterialInvoiceDdtMatchV14>,
        candidate: MaterialInvoiceDdtMatchV14
    ): Availability {
        val byId = lines.associateBy { it.ge360Id }
        require(byId.size == lines.size) { "Righe documentali V13 duplicate" }
        val invoice = byId[candidate.invoiceLineGe360Id]
            ?: error("Riga FATTURA non presente")
        val ddt = byId[candidate.ddtLineGe360Id]
            ?: error("Riga DDT non presente")
        require(invoice.active && ddt.active) { "Riga annullata: abbinamento non consentito" }
        require(invoice.documentKind == "FATTURA" && ddt.documentKind == "DDT") {
            "Le righe selezionate non sono una fattura e un DDT"
        }
        require(invoice.documentGe360Id != ddt.documentGe360Id) {
            "Un documento non può essere abbinato a se stesso"
        }
        require(invoice.materialGe360Id == ddt.materialGe360Id &&
            invoice.jobsiteGe360Id == ddt.jobsiteGe360Id) {
            "Materiale o cantiere differenti: riconciliazione rifiutata"
        }
        val unit = invoice.unit.trim()
        require(unit.equals(ddt.unit.trim(), ignoreCase = true) &&
            unit.equals(candidate.unit.trim(), ignoreCase = true)) {
            "Unità incompatibili: indicare la stessa unità"
        }
        require(existing.none { it.ge360Id == candidate.ge360Id }) {
            "UUID riconciliazione già esistente"
        }
        require(existing.none {
            it.active && it.invoiceLineGe360Id == candidate.invoiceLineGe360Id &&
                it.ddtLineGe360Id == candidate.ddtLineGe360Id
        }) { "Coppia fattura–DDT già abbinata: annullare prima la precedente" }

        val invoicedUsed = existing.filter {
            it.active && it.invoiceLineGe360Id == invoice.ge360Id
        }.sumOf { it.quantity }
        val deliveredUsed = existing.filter {
            it.active && it.ddtLineGe360Id == ddt.ge360Id
        }.sumOf { it.quantity }
        val invoiceAvailable = (invoice.quantity - invoicedUsed).coerceAtLeast(0.0)
        val deliveryAvailable = (ddt.quantity - deliveredUsed).coerceAtLeast(0.0)
        require(candidate.quantity <= invoiceAvailable + EPSILON &&
            candidate.quantity <= deliveryAvailable + EPSILON) {
            "Quantità superiore al residuo abbinabile di fattura o DDT"
        }
        return Availability(invoiceAvailable, deliveryAvailable,
            minOf(invoiceAvailable, deliveryAvailable))
    }

    fun matchedQuantity(
        lines: List<MaterialDocumentLineV13>,
        matches: List<MaterialInvoiceDdtMatchV14>,
        materialGe360Id: String,
        jobsiteGe360Id: String,
        unit: String
    ): Double {
        val byId = lines.filter {
            it.active && it.materialGe360Id == materialGe360Id &&
                it.jobsiteGe360Id == jobsiteGe360Id &&
                it.unit.trim().equals(unit.trim(), ignoreCase = true)
        }.associateBy { it.ge360Id }
        return matches.filter {
            it.active && byId[it.invoiceLineGe360Id]?.documentKind == "FATTURA" &&
                byId[it.ddtLineGe360Id]?.documentKind == "DDT" &&
                it.unit.trim().equals(unit.trim(), ignoreCase = true)
        }.sumOf { it.quantity }
    }
}
