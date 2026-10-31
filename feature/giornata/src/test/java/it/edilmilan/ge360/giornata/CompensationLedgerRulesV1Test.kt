package it.edilmilan.ge360.giornata

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance examples for Compensi V1, intentionally NOT executed during code-only work.
 */
class CompensationLedgerRulesV1Test {
    private fun accrual(amount: Long = 80_000L) = CompensationAccrualV1(
        ge360Id = "accrual-a",
        agreementGe360Id = "agreement-a",
        amountCents = amount,
        status = CompensationAccrualStatus.CONFIRMED,
        periodStart = LocalDate.parse("2026-10-26"),
        periodEnd = LocalDate.parse("2026-11-01")
    )

    private fun payment(id: String, amount: Long, finance: String? = null) =
        CompensationPaymentEventV1(
            ge360Id = id,
            accrualGe360Id = "accrual-a",
            kind = CompensationPaymentKind.PAYMENT,
            amountCents = amount,
            recordedAt = LocalDate.parse("2026-10-31"),
            financeRecordGe360Id = finance
        )

    @Test
    fun weekly_period_is_monday_to_sunday() {
        val data = accrual()
        CompensationLedgerRulesV1.validatePeriod(CompensationMode.WEEKLY, data)
        assertEquals(LocalDate.parse("2026-10-26"),
            CompensationLedgerRulesV1.isoWeekStart(LocalDate.parse("2026-10-31")))
        assertEquals(LocalDate.parse("2026-11-01"),
            CompensationLedgerRulesV1.isoWeekEnd(LocalDate.parse("2026-10-31")))
    }

    @Test
    fun partial_payment_keeps_remaining_balance() {
        val result = CompensationLedgerRulesV1.balance(accrual(), listOf(payment("p1", 25_000L)))
        assertEquals(25_000L, result.paidCents)
        assertEquals(55_000L, result.remainingCents)
        assertFalse(result.fullyPaid)
    }

    @Test
    fun complete_payment_is_registered_not_inferred() {
        val result = CompensationLedgerRulesV1.balance(accrual(), listOf(payment("p1", 80_000L)))
        assertEquals(0L, result.remainingCents)
        assertTrue(result.fullyPaid)
    }

    @Test
    fun full_reversal_preserves_both_events_but_restores_balance() {
        val reversal = CompensationPaymentEventV1(
            ge360Id = "rev1", accrualGe360Id = "accrual-a",
            kind = CompensationPaymentKind.REVERSAL, amountCents = 25_000L,
            recordedAt = LocalDate.parse("2026-11-01"), reversesPaymentGe360Id = "p1"
        )
        val result = CompensationLedgerRulesV1.balance(accrual(), listOf(payment("p1", 25_000L), reversal))
        assertEquals(0L, result.paidCents)
        assertEquals(80_000L, result.remainingCents)
        assertEquals(1, result.reversedPaymentCount)
    }

    @Test
    fun excessive_payments_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompensationLedgerRulesV1.balance(accrual(), listOf(payment("p1", 85_000L)))
        }
    }

    @Test
    fun duplicate_payment_events_and_finance_links_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompensationLedgerRulesV1.balance(accrual(), listOf(payment("p1", 10_000L), payment("p1", 20_000L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompensationLedgerRulesV1.balance(accrual(), listOf(
                payment("p1", 10_000L, "finance-x"), payment("p2", 20_000L, "finance-x")
            ))
        }
    }

    @Test
    fun single_accrual_may_be_shared_across_jobsites_without_double_counting() {
        val allocations = listOf(
            CompensationJobsiteAllocationV1("a1", "accrual-a", "jobsite-1", 48_000L),
            CompensationJobsiteAllocationV1("a2", "accrual-a", "jobsite-2", 32_000L)
        )
        assertEquals(0L, CompensationLedgerRulesV1.validateAllocations(accrual(), allocations))
    }

    @Test
    fun allocation_and_cottimo_may_have_no_jobsite() {
        val data = accrual()
        assertEquals(40_000L, CompensationLedgerRulesV1.validateAllocations(
            data, listOf(CompensationJobsiteAllocationV1("a1", "accrual-a", null, 40_000L))
        ))
        CompensationLedgerRulesV1.validatePeriod(CompensationMode.PIECEWORK, data)
    }

    @Test
    fun zero_accrual_does_not_mean_payment_was_made() {
        assertFalse(CompensationLedgerRulesV1.balance(accrual(0L), emptyList()).fullyPaid)
    }
}
