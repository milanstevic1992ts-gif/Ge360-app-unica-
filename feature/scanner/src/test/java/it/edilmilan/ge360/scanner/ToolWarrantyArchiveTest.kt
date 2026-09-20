package it.edilmilan.ge360.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolWarrantyArchiveTest {
    @Test
    fun safeNameKeepsReceiptFileReadableAndPortable() {
        assertEquals(
            "2026-09-04_tassellatore-makita-hr2470_12345678.pdf",
            ToolWarrantyArchive.fileName(
                purchaseDate = "2026-09-04",
                toolName = "Tassellatore Makita HR2470",
                ge360Id = "12345678-abcd-4321-abcd-1234567890ab"
            )
        )
    }

    @Test
    fun warrantyDocumentsUseDedicatedCanonicalType() {
        val document = ScannerDocument(type = ToolWarrantyArchive.DOCUMENT_TYPE)
        assertEquals(true, ToolWarrantyArchive.isWarrantyReceipt(document))
    }
}
