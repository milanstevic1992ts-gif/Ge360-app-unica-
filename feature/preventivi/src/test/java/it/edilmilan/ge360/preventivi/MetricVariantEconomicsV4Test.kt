package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prepared QA contracts. Tests intentionally NOT executed. */
class MetricVariantEconomicsV4Test {
    private fun variant(delta: Double = 2.5): MetricVariantV3 {
        val previous = 10.0
        val next = previous + delta
        return MetricVariantV3(
            estimateGe360Id = "estimate-1",
            previousItemGe360Id = "item-1",
            previousBindingGe360Id = "binding-original",
            candidateBindingGe360Id = "binding-revised",
            planGe360Id = "plan-1",
            roomGe360Id = "room-1",
            wallGe360Id = null,
            workCode = "CAP-03",
            surface = "PARETI_NETTE",
            previousFingerprint = "a".repeat(64),
            candidateFingerprint = "b".repeat(64),
            previousQuantityM2 = previous,
            proposedQuantityM2 = next,
            candidateGrossM2 = next,
            candidateOpeningDeductionsM2 = 0.0,
            candidateAdditionalDeductionsM2 = 0.0,
            priorUnitPrice = 40.0,
            priorIncluded = true,
            reason = "Variazione delle dimensioni della stanza",
            createdAt = 1_000L,
            status = MetricVariantV3.STATUS_TECH_REVIEWED,
            reviewedAt = 1_001L,
            reviewReason = "Rilievo rivisto e confermato"
        )
    }

    @Test fun positiveDeltaIsOnlyProposedExtra() {
        val extra = MetricVariantEconomicsV4.create(
            variant(), MetricVariantEconomicsV4.MODE_UNIT, 35.0,
            "Superficie extra in contratto integrativo"
        )
        assertTrue(extra.valid())
        assertEquals(MetricVariantEconomicsV4.KIND_EXTRA, extra.kind)
        assertEquals(87.5, extra.proposedSignedAmountEuro, 0.001)
        assertEquals(MetricVariantEconomicsV4.STATUS_PROPOSED, extra.status)
    }

    @Test fun negativeDeltaIsCreditNotSecondCharge() {
        val reduction = MetricVariantEconomicsV4.create(
            variant(-1.5), MetricVariantEconomicsV4.MODE_LUMP_SUM, 120.0,
            "Riduzione superfici di lavorazione"
        )
        assertTrue(reduction.valid())
        assertEquals(MetricVariantEconomicsV4.KIND_REDUCTION, reduction.kind)
        assertEquals(-120.0, reduction.proposedSignedAmountEuro, 0.001)
    }

    @Test fun proposalCannotPretendToBeAgreementWithoutEvidence() {
        val proposed = MetricVariantEconomicsV4.create(
            variant(), MetricVariantEconomicsV4.MODE_UNIT, 40.0,
            "Confronto con la misura originale"
        )
        assertFalse(proposed.copy(status =
            MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED).valid())
        assertFalse(proposed.copy(
            status = MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED,
            externalAgreementReference = "mail-2026-12",
            externalAgreementDate = "2026-11-01",
            evidenceNote = "Email confermata"
        ).valid())
        assertTrue(proposed.copy(
            status = MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED,
            externalAgreementReference = "mail-2026-12",
            externalAgreementDate = "2026-11-01",
            evidenceNote = "Email controllata",
            attestedAt = proposed.proposedAt + 1
        ).valid())
    }

    @Test fun corruptedSignedAmountsAndMissingPriceAreRejected() {
        val proposed = MetricVariantEconomicsV4.create(
            variant(), MetricVariantEconomicsV4.MODE_UNIT, 40.0,
            "Prezzo extra concordabile"
        )
        assertFalse(proposed.copy(proposedSignedAmountEuro = -100.0).valid())
        assertFalse(proposed.copy(proposedSignedAmountEuro = 100.01).valid())
        assertFalse(proposed.copy(proposedUnitRateEuro = Double.NaN).valid())
        assertFalse(proposed.copy(deltaM2 = -2.5).valid())
    }

    @Test fun externalAuditRequiresRecordedReference() {
        val audit = MetricVariantEconomicsEventV4(
            economicsGe360Id = "economic-1",
            estimateGe360Id = "estimate-1",
            action = "AGREEMENT_ATTESTED",
            signedAmountEuro = -120.0,
            evidenceReference = "email-cliente-2026-11-01",
            reason = "Controllata conferma scritta del cliente"
        )
        assertTrue(audit.valid())
        assertFalse(audit.copy(evidenceReference = "").valid())
        assertFalse(audit.copy(action = "INVOICE_PAID").valid())
    }
}
