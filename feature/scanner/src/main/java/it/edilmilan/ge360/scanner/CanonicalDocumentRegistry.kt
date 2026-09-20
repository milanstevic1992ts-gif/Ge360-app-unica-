package it.edilmilan.ge360.scanner

import java.util.Locale

/**
 * Vista canonica dei documenti GE360.
 *
 * Regola:
 * - FATTURA/DDT/etc. esistono una sola volta in DocumentStore e possono essere visti sia
 *   nell'archivio generale sia nel cantiere tramite jobsiteId.
 * - FIR può avere due componenti tecniche: dati strutturati in FirStore + PDF in DocumentStore.
 *   Queste due componenti rappresentano un solo formulario e devono essere conteggiate una volta sola.
 */
data class CanonicalDocumentSummary(
    val total: Int,
    val fir: Int,
    val invoices: Int,
    val ddt: Int,
    val receipts: Int,
    val other: Int
)

class CanonicalDocumentRegistry(
    private val documents: DocumentStore,
    private val firStore: FirStore
) {
    fun summary(jobsiteId: String = ""): CanonicalDocumentSummary {
        val docs = if (jobsiteId.isBlank()) documents.list() else documents.forJobsite(jobsiteId)
        val firRows = if (jobsiteId.isBlank()) firStore.list() else firStore.forJobsite(jobsiteId)

        // ge360Id e' la chiave canonica per i documenti Scanner: una stessa fattura/DDT
        // visualizzata in Archivio e Cantiere resta un unico record.
        val uniqueDocs = docs.distinctBy { it.ge360Id.ifBlank { "legacy:${it.id}" } }
        val nonFirDocs = uniqueDocs.filterNot { it.type.equals(TYPE_FIR, ignoreCase = true) }

        val invoices = nonFirDocs.count { it.type.equals(TYPE_INVOICE, ignoreCase = true) }
        val ddt = nonFirDocs.count { it.type.equals(TYPE_DDT, ignoreCase = true) }
        val receipts = nonFirDocs.count {
            it.type.equals(TYPE_RECEIPT, ignoreCase = true) ||
                it.type.equals(TYPE_TICKET, ignoreCase = true) ||
                ToolWarrantyArchive.isWarrantyReceipt(it)
        }
        val firCount = canonicalFirCount(
            uniqueDocs.filter { it.type.equals(TYPE_FIR, ignoreCase = true) },
            firRows
        )
        val other = nonFirDocs.size - invoices - ddt - receipts

        return CanonicalDocumentSummary(
            total = nonFirDocs.size + firCount,
            fir = firCount,
            invoices = invoices,
            ddt = ddt,
            receipts = receipts,
            other = other.coerceAtLeast(0)
        )
    }

    /**
     * Dati FIR e scansione FIR sono un unico documento quando possiamo identificarli con certezza.
     * Matching forte: stesso cantiere + stesso numero FIR.
     * Matching prudente per formulari senza numero: uno-a-uno sulla stessa data.
     */
    private fun canonicalFirCount(pdfRows: List<ScannerDocument>, firRows: List<FirRecord>): Int {
        if (pdfRows.isEmpty()) return firRows.distinctBy(::firIdentity).size
        if (firRows.isEmpty()) return pdfRows.distinctBy(::pdfIdentity).size

        val uniqueFir = firRows.distinctBy(::firIdentity)
        val uniquePdf = pdfRows.distinctBy(::pdfIdentity)
        val matchedPdfIds = mutableSetOf<String>()

        val firByStrongKey = uniqueFir
            .mapNotNull { fir -> strongFirKey(fir.jobsiteId, fir.firNumber)?.let { it to fir } }
            .groupBy({ it.first }, { it.second })

        uniquePdf.forEach { pdf ->
            val key = strongFirKey(pdf.jobsiteId, pdf.documentNumber)
            if (key != null && firByStrongKey[key]?.isNotEmpty() == true) {
                matchedPdfIds += pdfIdentity(pdf)
            }
        }

        // Se numero FIR non e' ancora presente, uniamo solo il caso non ambiguo:
        // esattamente una scheda FIR e una scansione FIR senza numero nella stessa data/cantiere.
        val remainingPdf = uniquePdf.filter { pdfIdentity(it) !in matchedPdfIds && it.documentNumber.isBlank() }
        val blankFir = uniqueFir.filter { it.firNumber.isBlank() }
        val firGroups = blankFir.groupBy { weakKey(it.jobsiteId, it.issueDate) }
        val pdfGroups = remainingPdf.groupBy { weakKey(it.jobsiteId, it.documentDate) }
        pdfGroups.forEach { (key, rows) ->
            if (key.isNotBlank() && rows.size == 1 && firGroups[key]?.size == 1) {
                matchedPdfIds += pdfIdentity(rows.first())
            }
        }

        // Ogni FirRecord conta una volta; aggiungiamo solo le scansioni FIR che non hanno
        // una corrispondente scheda strutturata.
        return uniqueFir.size + uniquePdf.count { pdfIdentity(it) !in matchedPdfIds }
    }

    private fun strongFirKey(jobsiteId: String, number: String): String? {
        val n = normalize(number)
        if (n.isBlank()) return null
        return "${jobsiteId.trim()}|$n"
    }

    private fun weakKey(jobsiteId: String, date: String): String {
        if (date.isBlank()) return ""
        return "${jobsiteId.trim()}|${date.trim()}"
    }

    private fun firIdentity(row: FirRecord): String = row.ge360Id.ifBlank {
        strongFirKey(row.jobsiteId, row.firNumber)
            ?: "fir:${row.id}:${row.jobsiteId}:${row.issueDate}:${row.createdAt}"
    }

    private fun pdfIdentity(row: ScannerDocument): String = row.ge360Id.ifBlank {
        "pdf:${row.id}:${row.jobsiteId}:${row.documentNumber}:${row.createdAt}"
    }

    private fun normalize(value: String): String = value
        .trim()
        .uppercase(Locale.ITALY)
        .replace(Regex("[^A-Z0-9]"), "")

    companion object {
        private const val TYPE_FIR = "FIR"
        private const val TYPE_INVOICE = "FATTURA"
        private const val TYPE_DDT = "DDT"
        private const val TYPE_RECEIPT = "RICEVUTA"
        private const val TYPE_TICKET = "SCONTRINO"
    }
}
