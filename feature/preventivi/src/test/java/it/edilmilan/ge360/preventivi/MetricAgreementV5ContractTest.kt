package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract scenarios prepared for later authorized QA; NOT executed here. */
class MetricAgreementV5ContractTest {
    private fun correction(amount: Double = 105.0) = MetricAgreementCorrectionV5(
        estimateGe360Id = "quote-01",
        economicsGe360Id = "commercial-01",
        priorSignedAmountEuro = 120.0,
        correctedSignedAmountEuro = amount,
        reason = "Rettifica concordata sui materiali"
    )

    @Test fun proposalDoesNotSupersedeEarlierSignedAmountUntilAttested() {
        val proposed = correction(105.0)
        assertTrue(proposed.valid())
        assertEquals(-15.0, proposed.adjustmentEuro(), 0.001)
        assertEquals(MetricAgreementCorrectionV5.STATUS_PROPOSED, proposed.status)
        assertFalse(proposed.copy(status = MetricAgreementCorrectionV5.STATUS_ATTESTED).valid())
    }

    @Test fun attestationNeedsReferenceDateAndRationale() {
        val proposed = correction()
        val confirmed = proposed.copy(
            status = MetricAgreementCorrectionV5.STATUS_ATTESTED,
            agreementReference = "mail-cliente-2026-11-01",
            agreementDateIso = "2026-11-01",
            evidenceNote = "Verificata comunicazione ricevuta dal cliente",
            attestedAt = proposed.proposedAt + 1
        )
        assertTrue(confirmed.valid())
        assertFalse(confirmed.copy(agreementReference = "").valid())
        assertFalse(confirmed.copy(agreementDateIso = "2099-01-01").valid())
    }

    @Test fun correctionMustKeepOriginalExtraOrReductionSign() {
        assertFalse(correction(-105.0).valid())
        assertFalse(correction(120.0).valid())
        val credit = correction(-105.0).copy(priorSignedAmountEuro = -120.0)
        assertTrue(credit.valid())
        assertEquals(15.0, credit.adjustmentEuro(), 0.001)
    }

    @Test fun realEvidenceRequiresChecksumAndSafeMimeAndSize() {
        val a = MetricAgreementAttachmentV5(
            estimateGe360Id = "quote-01",
            economicsGe360Id = "commercial-01",
            fileName = "accordo-cliente.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2048,
            sha256 = "a".repeat(64)
        )
        assertTrue(a.valid())
        assertFalse(a.copy(sha256 = "").valid())
        assertFalse(a.copy(mimeType = "application/x-msdownload").valid())
        assertFalse(a.copy(sizeBytes = MetricAgreementFilesV5.MAX_BYTES + 1).valid())
    }

    @Test fun auditMustBeTraceableAndNeverClaimPayment() {
        val e = MetricAgreementCorrectionEventV5(
            estimateGe360Id = "quote-01",
            correctionGe360Id = "correction-01",
            action = "CORRECTION_ATTESTED",
            signedAmountEuro = 105.0,
            reason = "Ricevuto riscontro dal cliente"
        )
        assertTrue(e.valid())
        assertFalse(e.copy(action = "SAL_PAID").valid())
    }
}
