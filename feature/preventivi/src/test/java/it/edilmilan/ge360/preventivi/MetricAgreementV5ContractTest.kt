package it.edilmilan.ge360.preventivi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

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

    private fun agreedEconomic() = MetricVariantEconomicsV4(
        ge360Id = "commercial-01",
        estimateGe360Id = "quote-01",
        variantGe360Id = "variant-01",
        sourceItemGe360Id = "item-01",
        deltaM2 = 1.0,
        kind = MetricVariantEconomicsV4.KIND_EXTRA,
        pricingMode = MetricVariantEconomicsV4.MODE_LUMP_SUM,
        proposedUnitRateEuro = 0.0,
        proposedLumpSumEuro = 120.0,
        proposedSignedAmountEuro = 120.0,
        proposalReason = "Accordo esterno per lavorazione aggiuntiva",
        proposedAt = 100,
        status = MetricVariantEconomicsV4.STATUS_EVIDENCE_RECORDED,
        externalAgreementReference = "mail-cliente-originale",
        externalAgreementDate = "2026-10-31",
        evidenceNote = "Accordo verificato nella corrispondenza",
        attestedAt = 101
    )

    @Test fun v6ChainedAttestationsReplaceRatherThanSum() {
        val initial = correction(105.0).copy(proposedAt = 200)
        val first = initial.copy(
            status = MetricAgreementCorrectionV5.STATUS_ATTESTED,
            agreementReference = "mail-prima-rettifica",
            agreementDateIso = "2026-10-31",
            evidenceNote = "Prima modifica economica documentata",
            attestedAt = 201
        )
        val second = first.copy(
            ge360Id = java.util.UUID.randomUUID().toString(),
            priorSignedAmountEuro = 105.0,
            correctedSignedAmountEuro = 95.0,
            proposedAt = 202,
            attestedAt = 203,
            agreementReference = "mail-seconda-rettifica",
            evidenceNote = "Seconda modifica economica documentata"
        )
        val econ = agreedEconomic()
        assertEquals(95.0, MetricAgreementRevisionChainV6.effectiveSignedAmount(
            econ, listOf(second, first)), 0.001)
        assertEquals(120.0, MetricAgreementRevisionChainV6.effectiveSignedAmount(
            econ, listOf(first.copy(status = MetricAgreementCorrectionV5.STATUS_PROPOSED,
                attestedAt = 0, agreementReference = "",
                agreementDateIso = "", evidenceNote = ""))), 0.001)
    }

    @Test fun v6RejectsGapsAndMultiplePendingProposals() {
        val econ = agreedEconomic()
        val first = correction(105.0).copy(proposedAt = 200)
        val second = correction(95.0).copy(
            ge360Id = java.util.UUID.randomUUID().toString(), proposedAt = 202)
        assertTrue(runCatching {
            MetricAgreementRevisionChainV6.ordered(econ, listOf(first, second))
        }.isFailure)
        assertTrue(runCatching {
            MetricAgreementRevisionChainV6.ordered(econ, listOf(second))
        }.isFailure)
    }

    @Test fun v7EvidenceStreamRejectsWrongDigestMissingBytesAndWrongFileHeader() {
        val pdf = "%PDF-1.7\\nprivate document".toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(pdf)
            .joinToString("") { "%02x".format(it) }
        val attachment = MetricAgreementAttachmentV5(
            estimateGe360Id = "quote-01",
            economicsGe360Id = "commercial-01",
            fileName = "accordo.pdf",
            mimeType = "application/pdf",
            sizeBytes = pdf.size.toLong(),
            sha256 = hash
        )
        assertTrue(MetricAgreementFilesV5.matchesBackupStream(
            attachment, ByteArrayInputStream(pdf)))
        assertFalse(MetricAgreementFilesV5.matchesBackupStream(
            attachment.copy(sha256 = "0".repeat(64)), ByteArrayInputStream(pdf)))
        assertFalse(MetricAgreementFilesV5.matchesBackupStream(
            attachment, ByteArrayInputStream(pdf.copyOf(pdf.size - 1))))
        assertFalse(MetricAgreementFilesV5.matchesBackupStream(
            attachment, ByteArrayInputStream(pdf + byteArrayOf(7))))
        val misleading = "wrong header".toByteArray(Charsets.UTF_8)
        val misleadingHash = MessageDigest.getInstance("SHA-256").digest(misleading)
            .joinToString("") { "%02x".format(it) }
        assertFalse(MetricAgreementFilesV5.matchesBackupStream(
            attachment.copy(sizeBytes = misleading.size.toLong(), sha256 = misleadingHash),
            ByteArrayInputStream(misleading)))
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
