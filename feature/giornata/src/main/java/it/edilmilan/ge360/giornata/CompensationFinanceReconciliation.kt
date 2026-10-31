package it.edilmilan.ge360.giornata

import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Explicit UUID link only. The FinanceStore remains the one and only source of expenses.
 * Linking an existing entry does not create, modify, delete or "pay" a FinanceRecord.
 */
object CompensationFinanceReconciliation {
    fun financeCents(record: FinanceRecord): Long {
        require(record.amount.isFinite() && record.amount > 0) { "Importo Spese non valido" }
        return BigDecimal.valueOf(record.amount).movePointRight(2)
            .setScale(0, RoundingMode.UNNECESSARY).longValueExact()
    }

    fun validateLink(
        payment: CompensationPaymentEventV1,
        finance: FinanceRecord,
        agreementJobsiteId: String?
    ) {
        require(payment.kind == CompensationPaymentKind.PAYMENT) {
            "Soltanto un pagamento può essere associato a un movimento Spese"
        }
        require(payment.financeRecordGe360Id.isNullOrBlank() ||
            payment.financeRecordGe360Id == finance.ge360Id) {
            "Il pagamento è già associato a un altro movimento"
        }
        require(finance.ge360Id.isNotBlank() &&
            finance.type == FinanceType.EXPENSE && finance.scope == FinanceScope.BUSINESS) {
            "Seleziona un'uscita aziendale esistente"
        }
        require(financeCents(finance) == payment.amountCents) {
            "L'importo del movimento Spese è differente dal pagamento dichiarato"
        }
        require(finance.date == payment.recordedAt.toString()) {
            "La data del movimento Spese è differente dal pagamento dichiarato"
        }
        val jobsite = agreementJobsiteId.orEmpty()
        if (jobsite.isNotBlank() && finance.jobsiteId.isNotBlank()) {
            require(finance.jobsiteId == jobsite) {
                "Il movimento Spese appartiene a un altro cantiere"
            }
        }
    }

    fun matching(
        payment: CompensationPaymentEventV1,
        agreementJobsiteId: String?,
        finances: List<FinanceRecord>,
        alreadyUsedFinanceIds: Set<String>
    ): List<FinanceRecord> = finances.filter { item ->
        item.ge360Id !in alreadyUsedFinanceIds && runCatching {
            validateLink(payment, item, agreementJobsiteId)
        }.isSuccess
    }.sortedWith(compareBy<FinanceRecord> { it.date }.thenBy { it.ge360Id })

    fun linkedFinanceValid(
        payment: CompensationPaymentEventV1,
        finance: FinanceRecord?,
        agreementJobsiteId: String?
    ): Boolean = finance != null && payment.financeRecordGe360Id == finance.ge360Id &&
        runCatching { validateLink(payment, finance, agreementJobsiteId) }.isSuccess
}
