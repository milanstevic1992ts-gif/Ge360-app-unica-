package it.edilmilan.ge360.giornata

import it.edilmilan.ge360.spese.FinanceRecord
import it.edilmilan.ge360.spese.FinanceScope
import it.edilmilan.ge360.spese.FinanceType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules. Regression cases prepared, not executed during no-build development.
 */
class CompensationFinanceReconciliationTest {
    private val payment = CompensationPaymentEventV1(
        ge360Id = "event-a", accrualGe360Id = "accrual-a",
        kind = CompensationPaymentKind.PAYMENT, amountCents = 25_000L,
        recordedAt = LocalDate.parse("2026-10-31")
    )

    private fun expense(
        id: String = "finance-a",
        amount: Double = 250.0,
        date: String = "2026-10-31",
        jobsiteId: String = "jobsite-a",
        type: FinanceType = FinanceType.EXPENSE,
        scope: FinanceScope = FinanceScope.BUSINESS
    ) = FinanceRecord(
        ge360Id = id, amount = amount, date = date,
        jobsiteId = jobsiteId, type = type, scope = scope
    )

    @Test fun exact_amount_date_and_cantiere_allow_linking() {
        val matched = expense()
        CompensationFinanceReconciliation.validateLink(payment, matched, "jobsite-a")
        assertEquals(25_000L, CompensationFinanceReconciliation.financeCents(matched))
        assertTrue(CompensationFinanceReconciliation.linkedFinanceValid(
            payment.copy(financeRecordGe360Id = "finance-a"), matched, "jobsite-a"
        ))
    }

    @Test fun wrong_amount_or_date_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompensationFinanceReconciliation.validateLink(payment, expense(amount=300.0), "jobsite-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompensationFinanceReconciliation.validateLink(payment, expense(date="2026-11-01"), "jobsite-a")
        }
    }

    @Test fun personal_expense_income_or_wrong_jobsite_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompensationFinanceReconciliation.validateLink(payment, expense(scope=FinanceScope.PRIVATE), "jobsite-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompensationFinanceReconciliation.validateLink(payment, expense(type=FinanceType.INCOME), "jobsite-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompensationFinanceReconciliation.validateLink(payment, expense(jobsiteId="jobsite-b"), "jobsite-a")
        }
    }

    @Test fun matching_excludes_already_used_finance_uuids() {
        val candidates = CompensationFinanceReconciliation.matching(
            payment, "jobsite-a",
            listOf(expense(id="finance-a"), expense(id="finance-b")),
            setOf("finance-a")
        )
        assertEquals(listOf("finance-b"), candidates.map { it.ge360Id })
    }

    @Test fun a_missing_finance_row_cannot_be_claimed_verified() {
        val linked = payment.copy(financeRecordGe360Id = "finance-a")
        assertFalse(CompensationFinanceReconciliation.linkedFinanceValid(linked, null, "jobsite-a"))
        assertFalse(CompensationFinanceReconciliation.linkedFinanceValid(
            linked, expense(amount=300.0), "jobsite-a"
        ))
    }

    @Test fun invalid_precision_is_not_silently_rounded() {
        assertThrows(ArithmeticException::class.java) {
            CompensationFinanceReconciliation.financeCents(expense(amount=250.001))
        }
    }
}
