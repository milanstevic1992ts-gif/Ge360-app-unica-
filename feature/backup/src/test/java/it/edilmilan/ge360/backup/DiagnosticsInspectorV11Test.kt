package it.edilmilan.ge360.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts for V11; only saved to GitHub, NEVER executed in this task.
 * All examples are synthetic and contain no personal or business information.
 */
class DiagnosticsInspectorV11Test {
    @Test fun errorAndWarningTakePriorityOverSuccessfulReadChecks() {
        val report = DiagnosticsInspectorV11.Report(12345L, listOf(
            DiagnosticsInspectorV11.Finding(
                "Archivi", "Clienti", DiagnosticsInspectorV11.Level.OK, "Archivio leggibile"),
            DiagnosticsInspectorV11.Finding(
                "Backup", "Ultima copia", DiagnosticsInspectorV11.Level.ATTENZIONE, "Copia non disponibile"),
            DiagnosticsInspectorV11.Finding(
                "Collegamenti", "Verifica UUID", DiagnosticsInspectorV11.Level.ERRORE,
                "Collegamento non coerente")
        ))
        assertEquals(1, report.successful)
        assertEquals(1, report.warnings)
        assertEquals(1, report.errors)
        assertEquals("Problemi da verificare", report.headline)
    }

    @Test fun unverifiedEncryptedBackupIsNotCountedAsOk() {
        val report = DiagnosticsInspectorV11.Report(12345L, listOf(
            DiagnosticsInspectorV11.Finding(
                "Backup", "Ultima copia", DiagnosticsInspectorV11.Level.INFO,
                "Formato cifrato rilevato; autenticità e recuperabilità NON verificate")
        ))
        assertEquals(0, report.successful)
        assertEquals(0, report.errors)
        assertEquals(0, report.warnings)
        assertTrue(report.publicSummary().contains("NON verificate"))
        assertFalse(report.publicSummary().contains("Controlli OK: 1"))
    }

    @Test fun reportProvidesOnlyProvidedSummaryInformation() {
        val report = DiagnosticsInspectorV11.Report(12345L, listOf(
            DiagnosticsInspectorV11.Finding(
                "Sistema", "Spazio disponibile", DiagnosticsInspectorV11.Level.OK,
                "Oltre 256 MB liberi")
        ))
        val export = report.publicSummary()
        assertTrue(export.contains("Rapporto diagnostico sintetico V11"))
        assertTrue(export.contains("Spazio disponibile"))
        assertFalse(export.contains("/storage/emulated/"))
        assertFalse(export.contains("content://"))
        assertFalse(export.contains("java.lang.Throwable"))
    }
}
