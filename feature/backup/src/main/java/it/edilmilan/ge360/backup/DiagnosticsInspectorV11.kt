package it.edilmilan.ge360.backup

import android.content.Context
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.centrocantiere.JobsiteArchiveStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.backup.BackupArchive
import it.edilmilan.ge360.core.backup.BackupPassphraseStoreV8
import it.edilmilan.ge360.core.backup.BackupScratchV9
import it.edilmilan.ge360.core.backup.EncryptedBackupV8
import it.edilmilan.ge360.galleria.GalleryArchive
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.planimetrie.FloorPlanStore
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.MetricAgreementFilesV5
import it.edilmilan.ge360.scanner.DocumentStore
import org.json.JSONObject
import java.io.File

/**
 * On-demand, read-only diagnostics. Runs from a background thread, never as
 * part of the shell bootstrap or a backup job. No exports leave the device.
 *
 * Results deliberately exclude client identifiers, names, paths, ZIP contents,
 * encryption secrets, document filenames and raw exception messages.
 */
object DiagnosticsInspectorV11 {
    enum class Level { OK, ATTENZIONE, ERRORE, INFO }

    data class Finding(
        val section: String,
        val title: String,
        val level: Level,
        val detail: String,
        val nextStep: String = ""
    )

    data class Report(val checkedAt: Long, val findings: List<Finding>) {
        val errors: Int get() = findings.count { it.level == Level.ERRORE }
        val warnings: Int get() = findings.count { it.level == Level.ATTENZIONE }
        val successful: Int get() = findings.count { it.level == Level.OK }
        val headline: String get() = when {
            errors > 0 -> "Problemi da verificare"
            warnings > 0 -> "Richiede attenzione"
            else -> "Nessun problema nei controlli eseguiti"
        }

        fun publicSummary(): String = buildString {
            appendLine("GE360 · Rapporto diagnostico sintetico V11")
            appendLine("Verifica locale: ${java.util.Date(checkedAt)}")
            appendLine("Controlli OK: $successful · Attenzioni: $warnings · Errori: $errors")
            findings.forEach {
                appendLine("[${it.level}] ${it.section} / ${it.title}: ${it.detail}")
                if (it.nextStep.isNotBlank()) appendLine("Azione: ${it.nextStep}")
            }
            appendLine("Nota: controllo istantaneo e parziale, non certifica integrità globale.")
            appendLine("Non comprende dati di clienti, percorsi privati o credenziali.")
        }
    }

    fun inspect(context: Context): Report {
        val app = context.applicationContext
        val findings = mutableListOf<Finding>()
        fun add(section: String, title: String, level: Level, detail: String, step: String = "") {
            findings += Finding(section, title, level, detail, step)
        }
        fun capture(name: String, action: () -> String): String? =
            runCatching { action() }.fold(
                onSuccess = { json ->
                    add("Archivi", name, Level.OK, "Archivio leggibile")
                    json
                },
                onFailure = {
                    add("Archivi", name, Level.ERRORE, "Lettura non riuscita",
                        "Apri il modulo e verifica i dati; non cancellare il database")
                    null
                }
            )

        val clients = capture("Clienti") {
            val s = ClientStore(app); try { s.exportJson() } finally { s.close() }
        }
        val jobs = capture("Cantieri") {
            val s = JobsiteStore(app); try { s.exportJson() } finally { s.close() }
        }
        val estimates = capture("Preventivi") {
            val s = EstimateStore(app); try { s.exportJson() } finally { s.close() }
        }
        val scanner = capture("Scanner") {
            val s = DocumentStore(app); try { s.exportJson() } finally { s.close() }
        }
        val gallery = capture("Galleria") {
            val s = GalleryStore(app); try { s.exportJson() } finally { s.close() }
        }
        val plans = capture("Rilievi") {
            val s = FloorPlanStore(app); try { s.exportJson() } finally { s.close() }
        }
        val archives = capture("Cartella Cantiere") {
            val s = JobsiteArchiveStore(app); try { s.exportJson() } finally { s.close() }
        }

        if (listOf(clients, jobs, estimates, scanner, gallery).all { it != null }) {
            runCatching {
                RestoreLinksV10.check(
                    RestoreLinksV10.Snapshot(
                        clients = requireNotNull(clients), jobs = requireNotNull(jobs),
                        estimates = requireNotNull(estimates), scanner = requireNotNull(scanner),
                        gallery = requireNotNull(gallery),
                        floorPlans = plans, jobsiteArchive = archives
                    ), replaceAll = true
                )
            }.onSuccess { result ->
                val level = if (result.historicalLinksSkipped > 0) Level.ATTENZIONE else Level.OK
                add("Collegamenti", "Clienti · cantieri · preventivi", level,
                    "${result.checkedLinks} relazioni UUID controllate" +
                        if (result.historicalLinksSkipped > 0)
                            " · ${result.historicalLinksSkipped} riferimenti storici non verificati" else "",
                    if (level == Level.ATTENZIONE)
                        "Verifica i collegamenti storici nei moduli interessati" else "")
            }.onFailure {
                add("Collegamenti", "Clienti · cantieri · preventivi", Level.ERRORE,
                    "Almeno un collegamento UUID è incoerente oppure il formato non è valido",
                    "Controlla le associazioni nei moduli prima di ripristinare dati")
            }
        } else add("Collegamenti", "Controllo incrociato", Level.ATTENZIONE,
            "Non disponibile: uno o più archivi non leggibili")

        fun missingFiles(json: String?, array: String, field: String, title: String) {
            if (json == null) return
            runCatching {
                val rows = JSONObject(json).optJSONArray(array)
                var missing = 0
                var linked = 0
                if (rows != null) for (i in 0 until rows.length()) {
                    val location = rows.getJSONObject(i).optString(field)
                    if (location.isNotBlank()) {
                        linked++
                        if (!File(location).isFile) missing++
                    }
                }
                add("Documenti", title,
                    if (missing > 0) Level.ATTENZIONE else Level.OK,
                    "${linked} file locali referenziati · ${missing} non trovati",
                    if (missing > 0) "Rivedi il documento originale e il backup, senza eliminare i metadati" else "")
            }.onFailure {
                add("Documenti", title, Level.ERRORE, "Indice documentale non leggibile")
            }
        }
        missingFiles(scanner, "documents", "pdf_path", "PDF Scanner")
        missingFiles(archives, "files", "filePath", "File Cartella Cantiere")

        runCatching {
            val s = EstimateStore(app)
            try {
                var files = 0
                var invalid = 0
                s.list().forEach { quote ->
                    s.agreementAttachments(quote.ge360Id).forEach { evidence ->
                        files++
                        if (!MetricAgreementFilesV5.existsAndMatches(app, evidence)) invalid++
                    }
                }
                add("Documenti", "Accordi economici",
                    if (invalid > 0) Level.ERRORE else Level.OK,
                    "${files} allegati con verifica SHA-256 · ${invalid} mancanti o alterati",
                    if (invalid > 0) "Non confermare accordi: recupera una copia integra" else "")
            } finally { s.close() }
        }.onFailure {
            add("Documenti", "Accordi economici", Level.ERRORE, "Verifica allegati non riuscita")
        }

        if (gallery != null) runCatching {
            val rows = JSONObject(gallery).optJSONArray("photos")
            val archive = GalleryArchive(app)
            var unreadable = 0
            var checked = 0
            if (rows != null) for (i in 0 until rows.length()) {
                val uri = rows.getJSONObject(i).optString("localUri")
                if (uri.isNotBlank()) {
                    checked++
                    if (!archive.readable(uri)) unreadable++
                }
            }
            add("Documenti", "Foto Galleria",
                if (unreadable > 0) Level.ATTENZIONE else Level.OK,
                "${checked} originali locali controllati · ${unreadable} non leggibili",
                if (unreadable > 0) "Controlla i permessi della cartella foto e le copie" else "")
        }.onFailure {
            add("Documenti", "Foto Galleria", Level.ATTENZIONE,
                "Verifica accessibilità foto incompleta")
        }

        val backup = BackupArchive(app)
        runCatching {
            val latest = backup.latestLocal()
            if (latest == null) {
                add("Backup", "Copia locale", Level.ATTENZIONE, "Nessuna copia GE360 trovata",
                    "Crea un backup e conserva una copia esterna al telefono")
            } else if (latest.name.endsWith(".ge360.enc")) {
                add("Backup", "Ultima copia", if (EncryptedBackupV8.isEncrypted(latest)) Level.INFO else Level.ERRORE,
                    if (EncryptedBackupV8.isEncrypted(latest))
                        "Formato cifrato rilevato; autenticità e recuperabilità NON verificate"
                    else "Header cifrato assente o non leggibile",
                    "Per una verifica completa serve un ripristino di prova autorizzato")
            } else {
                backup.inspect(latest, verifyHashes = true)
                add("Backup", "Ultima copia", Level.OK,
                    "Integrità ZIP e checksum interni verificati; non è una prova di autenticità")
            }
        }.onFailure {
            add("Backup", "Ultima copia", Level.ERRORE,
                "Copia locale non leggibile o checksum interni non validi",
                "Non cancellare copie precedenti; verifica il recupero da backup")
        }
        if (backup.encryptionEnabled()) {
            runCatching {
                val pass = BackupPassphraseStoreV8(app).readForBackup()
                pass.fill('\u0000')
            }.onSuccess {
                add("Backup", "Chiave locale V8", Level.OK,
                    "Accesso Android Keystore disponibile per backup automatici")
            }.onFailure {
                add("Backup", "Chiave locale V8", Level.ERRORE,
                    "Password locale cifrata non accessibile",
                    "Usa la funzione di riparazione chiave in Backup con copia e password")
            }
        } else {
            add("Backup", "Cifratura", Level.ATTENZIONE,
                "Non attiva: i nuovi ZIP rimangono leggibili in chiaro",
                "Attiva la cifratura in Backup e custodisci la password")
        }

        val cleanup = BackupScratchV9.lastSummary(app)
        if (cleanup.failed > 0) add("Sistema", "File temporanei", Level.ATTENZIONE,
            "${cleanup.failed} residui non eliminati all'ultimo avvio",
            "Verifica spazio disponibile e ripeti il controllo dopo il riavvio")
        else add("Sistema", "File temporanei", Level.OK,
            "Nessun errore di pulizia registrato all'ultimo avvio")

        val schedulerStatus = BackupScheduler.lastStatus(app)
        if (schedulerStatus.contains("fallito", ignoreCase = true)) {
            add("Backup", "Backup automatico", Level.ATTENZIONE,
                "Ultima esecuzione automatica segnalata come fallita",
                "Apri Backup e controlla credenziale e destinazioni")
        } else if (!BackupScheduler.isEnabled(app)) {
            add("Backup", "Backup automatico", Level.ATTENZIONE,
                "Pianificazione automatica disattivata")
        } else add("Backup", "Backup automatico", Level.INFO,
            "Pianificazione abilitata; esecuzione futura non garantita")

        return Report(System.currentTimeMillis(), findings.toList())
    }
}
