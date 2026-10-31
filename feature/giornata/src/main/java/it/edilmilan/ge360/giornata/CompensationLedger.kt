package it.edilmilan.ge360.giornata

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * GE360 Compensi V1: pure domain rules, independent from hourly timers and FinanceStore.
 *
 * No DB, HTTP, notifications, or automatic payment creation in this file.
 * Legacy weekly payments and external collaborator FinanceRecords are not modified.
 * Only explicitly confirmed accruals have a calculated payable balance.
 */
enum class CompensationMode { WEEKLY, PIECEWORK }
enum class CompensationAccrualStatus { DRAFT, CONFIRMED, VOIDED }
enum class CompensationPaymentKind { PAYMENT, REVERSAL }

data class CompensationAgreementV1(
    val ge360Id: String,
    val collaboratorGe360Id: String,
    val mode: CompensationMode,
    val agreedAmountCents: Long,
    val description: String,
    val currency: String = "EUR",
    val jobsiteGe360Id: String? = null
) {
    init {
        require(ge360Id.isNotBlank() && collaboratorGe360Id.isNotBlank()) {
            "Identificativi GE360 obbligatori"
        }
        require(agreedAmountCents >= 0) { "Importo concordato non valido" }
        require(currency == "EUR") { "Valuta non supportata" }
    }
}

/** A WEEKLY accrual uses an ISO Monday-Sunday period; piecework uses a work milestone. */
data class CompensationAccrualV1(
    val ge360Id: String,
    val agreementGe360Id: String,
    val amountCents: Long,
    val status: CompensationAccrualStatus,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val description: String = ""
) {
    init {
        require(ge360Id.isNotBlank() && agreementGe360Id.isNotBlank())
        require(amountCents >= 0) { "Importo maturato non valido" }
        require(!periodEnd.isBefore(periodStart)) { "Periodo non valido" }
    }
}

/**
 * Each PAYMENT has its own UUID and may optionally link a FinanceRecord by its GE360 ID.
 * REVERSAL fully cancels one existing PAYMENT, preserving the original event.
 * An attachment/reference does not prove bank settlement: the event means user-recorded.
 */
data class CompensationPaymentEventV1(
    val ge360Id: String,
    val accrualGe360Id: String,
    val kind: CompensationPaymentKind,
    val amountCents: Long,
    val recordedAt: LocalDate,
    val proofDocumentGe360Id: String? = null,
    val financeRecordGe360Id: String? = null,
    val reversesPaymentGe360Id: String? = null
) {
    init {
        require(ge360Id.isNotBlank() && accrualGe360Id.isNotBlank())
        require(amountCents > 0) { "Importo pagamento non valido" }
        require(
            (kind == CompensationPaymentKind.REVERSAL) == !reversesPaymentGe360Id.isNullOrBlank()
        ) { "Un riferimento di storno è necessario soltanto per REVERSAL" }
    }
}

data class CompensationJobsiteAllocationV1(
    val ge360Id: String,
    val accrualGe360Id: String,
    val jobsiteGe360Id: String?,
    val amountCents: Long
) {
    init {
        require(ge360Id.isNotBlank() && accrualGe360Id.isNotBlank())
        require(amountCents >= 0) { "Ripartizione negativa non ammessa" }
    }
}

data class CompensationBalanceV1(
    val accruedCents: Long,
    val paidCents: Long,
    val remainingCents: Long,
    val paymentCount: Int,
    val reversedPaymentCount: Int
) {
    val fullyPaid: Boolean get() = remainingCents == 0L
}

object CompensationLedgerRulesV1 {
    private val rome = ZoneId.of("Europe/Rome")

    fun currentWeekStart(): LocalDate =
        LocalDate.now(rome).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun isoWeekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun isoWeekEnd(date: LocalDate): LocalDate = isoWeekStart(date).plusDays(6)

    fun validatePeriod(mode: CompensationMode, accrual: CompensationAccrualV1) {
        if (mode == CompensationMode.WEEKLY) {
            require(accrual.periodStart == isoWeekStart(accrual.periodStart)) {
                "La settimana deve iniziare lunedì"
            }
            require(accrual.periodEnd == isoWeekEnd(accrual.periodStart)) {
                "La settimana deve terminare domenica"
            }
        }
    }

    /**
     * No payment is deduced from a document, a work session, or a FinanceRecord.
     * Duplicate payment events, invalid reversals and overpayments are rejected.
     */
    fun balance(
        accrual: CompensationAccrualV1,
        events: List<CompensationPaymentEventV1>
    ): CompensationBalanceV1 {
        require(accrual.status == CompensationAccrualStatus.CONFIRMED) {
            "Solo un maturato confermato può avere saldo pagabile"
        }
        require(events.all { it.accrualGe360Id == accrual.ge360Id }) {
            "Pagamento riferito a un altro maturato"
        }
        require(events.map { it.ge360Id }.toSet().size == events.size) {
            "Evento di pagamento duplicato"
        }

        val payments = events.filter { it.kind == CompensationPaymentKind.PAYMENT }
        val paymentsById = payments.associateBy { it.ge360Id }
        val reversals = events.filter { it.kind == CompensationPaymentKind.REVERSAL }
        val reversedIds = mutableSetOf<String>()

        reversals.forEach { reversal ->
            val original = paymentsById[reversal.reversesPaymentGe360Id]
                ?: throw IllegalArgumentException("Pagamento originale dello storno mancante")
            require(original.amountCents == reversal.amountCents) {
                "Uno storno V1 annulla l'intero pagamento originale"
            }
            require(reversedIds.add(original.ge360Id)) { "Pagamento già stornato" }
        }

        var paid = 0L
        payments.filterNot { it.ge360Id in reversedIds }.forEach { payment ->
            paid = Math.addExact(paid, payment.amountCents)
        }
        require(paid <= accrual.amountCents) {
            "Pagamenti registrati superiori al maturato confermato"
        }
        return CompensationBalanceV1(
            accruedCents = accrual.amountCents,
            paidCents = paid,
            remainingCents = accrual.amountCents - paid,
            paymentCount = payments.size,
            reversedPaymentCount = reversals.size
        )
    }

    /** An accrual may be unallocated; an allocation never creates a new cost. */
    fun validateAllocations(
        accrual: CompensationAccrualV1,
        allocations: List<CompensationJobsiteAllocationV1>
    ): Long {
        require(allocations.all { it.accrualGe360Id == accrual.ge360Id }) {
            "Ripartizione riferita a un altro maturato"
        }
        require(allocations.map { it.ge360Id }.toSet().size == allocations.size) {
            "Ripartizione duplicata"
        }
        var total = 0L
        allocations.forEach { item -> total = Math.addExact(total, item.amountCents) }
        require(total <= accrual.amountCents) { "Ripartizioni superiori al maturato" }
        return accrual.amountCents - total
    }
}
