package it.edilmilan.ge360.backup

import android.content.Context
import android.net.Uri
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.attrezzi.CustomWorkPackStore
import it.edilmilan.ge360.attrezzi.VanPreparationStore
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.centrocantiere.JobsiteArchiveStore
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.backup.BackupArchive
import it.edilmilan.ge360.core.backup.BackupEntrySource
import it.edilmilan.ge360.core.backup.BackupFileInfo
import it.edilmilan.ge360.galleria.GalleryArchive
import it.edilmilan.ge360.galleria.GalleryPhoto
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.giornata.CompensationStore
import it.edilmilan.ge360.messaggi.TemplateStore
import it.edilmilan.ge360.planimetrie.FloorPlanStore
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.MetricAgreementFilesV5
import it.edilmilan.ge360.preventivi.MetricAgreementAttachmentV5
import it.edilmilan.ge360.preventivi.EstimateLifecycleStore
import it.edilmilan.ge360.preventivi.EstimateRoomStore
import it.edilmilan.ge360.preventivi.PreventiviCustomizationStore
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.FirStore
import it.edilmilan.ge360.scanner.ScannerDocument
import it.edilmilan.ge360.scanner.ToolWarrantyArchive
import it.edilmilan.ge360.spese.FinanceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class BackupCoordinator(private val context: Context) {
    val archive = BackupArchive(context)

    data class CreateResult(
        val info: BackupFileInfo,
        val copiedToDrive: Boolean,
        val galleryFilesIncluded: Int,
        val galleryFilesSkipped: Int,
        val scannerFilesIncluded: Int
    )

    data class RestoreResult(
        val counts: Map<String, Int>,
        val scannerFiles: Int,
        val galleryFiles: Int,
        val warnings: List<String>
    )

    fun createBackup(includeGalleryFiles: Boolean, copyToDrive: Boolean): CreateResult {
        val clients = ClientStore(context)
        val jobs = JobsiteStore(context)
        val workflow = JobsiteWorkflowStore(context)
        val jobsiteArchive = JobsiteArchiveStore(context)
        val daily = DailyStore(context)
        val compensi = CompensationStore(context)
        val messages = TemplateStore(context)
        val finances = FinanceStore(context)
        val documents = DocumentStore(context)
        val fir = FirStore(context)
        val estimates = EstimateStore(context)
        val estimateRooms = EstimateRoomStore(context)
        val estimateLifecycle = EstimateLifecycleStore(context)
        val estimateCustomizations = PreventiviCustomizationStore(context)
        val floorPlans = FloorPlanStore(context)
        val tools = ToolsStore(context)
        val customWorkPacks = CustomWorkPackStore(context)
        val vanPreparations = VanPreparationStore(context)
        val gallery = GalleryStore(context)
        try {
            val textEntries = linkedMapOf(
                "modules/clienti.json" to clients.exportJson(),
                "modules/cantieri.json" to jobs.exportJson(),
                "modules/centro-cantiere.json" to workflow.exportJson(),
                "modules/jobsite-archive.json" to jobsiteArchive.exportJson(),
                "modules/giornata.json" to daily.exportJson(),
                "modules/compensi.json" to compensi.exportJson(),
                "modules/messaggi.json" to messages.exportJson(),
                "modules/spese.json" to finances.exportJson(),
                "modules/scanner.json" to documents.exportJson(),
                "modules/fir.json" to fir.exportJson(),
                "modules/preventivi.json" to estimates.exportJson(),
                "modules/preventivi-rooms.json" to estimateRooms.exportJson(),
                "modules/preventivi-lifecycle.json" to estimateLifecycle.exportJson(),
                "modules/preventivi-customizations.json" to estimateCustomizations.exportFullJson(),
                "modules/planimetrie.json" to floorPlans.exportJson(),
                "modules/attrezzi.json" to tools.exportJson(),
                "modules/attrezzi-custom-packs.json" to customWorkPacks.exportJson(),
                "modules/furgone.json" to vanPreparations.exportJson(),
                "modules/galleria.json" to gallery.exportJson(),
                "modules/fisco-settings.json" to fiscalPrefsJson()
            )
            val entries = mutableListOf<BackupEntrySource>()
            textEntries.forEach { (path, value) ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                entries += BackupEntrySource(path) { ByteArrayInputStream(bytes) }
            }

            // All private agreement evidence is included even in automatic backups:
            // missing or corrupted evidence fails the backup instead of producing
            // a seemingly complete but unusable archive.
            val evidenceMap = JSONObject()
            estimates.list().forEach { quote ->
                estimates.agreementAttachments(quote.ge360Id).forEach { attachment ->
                    require(MetricAgreementFilesV5.existsAndMatches(context, attachment)) {
                        "Backup sospeso: allegato accordo mancante o alterato (${attachment.fileName})"
                    }
                    val original = MetricAgreementFilesV5.fileFor(context, attachment)
                    val archivePath = "files/metric-agreements/${attachment.ge360Id}." +
                        attachment.mimeType.substringAfter('/').let {
                            when (it) { "pdf" -> "pdf"; "jpeg" -> "jpg"; "png" -> "png"
                                else -> error("Tipo allegato non supportato") }
                        }
                    evidenceMap.put(attachment.ge360Id, archivePath)
                    entries += BackupEntrySource(archivePath) { FileInputStream(original) }
                }
            }

            val scannerMap = JSONObject()
            var scannerFiles = 0
            documents.list().forEach { doc ->
                val source = doc.pdfPath.takeIf { it.isNotBlank() }?.let(::File)
                if (source != null && source.exists() && source.isFile) {
                    val path = "files/scanner/${safe(doc.ge360Id)}.pdf"
                    scannerMap.put(doc.ge360Id, path)
                    entries += BackupEntrySource(path) { FileInputStream(source) }
                    scannerFiles++
                }
            }

            val jobsiteArchiveMap = JSONObject()
            jobsiteArchive.allFiles().forEach { item ->
                val source = item.filePath.takeIf { it.isNotBlank() }?.let(::File)
                if (source != null && source.exists() && source.isFile) {
                    val path = "files/jobsite-archive/${safe(item.ge360Id)}.pdf"
                    jobsiteArchiveMap.put(item.ge360Id, path)
                    entries += BackupEntrySource(path) { FileInputStream(source) }
                }
            }

            val galleryMap = JSONObject()
            var galleryIncluded = 0
            var gallerySkipped = 0
            if (includeGalleryFiles) {
                gallery.list().forEach { photo ->
                    val uri = runCatching { Uri.parse(photo.localUri) }.getOrNull()
                    val canOpen = uri != null && runCatching {
                        context.contentResolver.openInputStream(uri).use { it != null }
                    }.getOrDefault(false)
                    if (uri != null && canOpen) {
                        val ext = photo.fileName.substringAfterLast('.', "bin").lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "bin" }
                        val path = "files/gallery/${safe(photo.ge360Id)}.$ext"
                        galleryMap.put(photo.ge360Id, path)
                        entries += BackupEntrySource(path) {
                            context.contentResolver.openInputStream(uri) ?: error("Foto non leggibile: ${photo.fileName}")
                        }
                        galleryIncluded++
                    } else gallerySkipped++
                }
            }

            val manifest = JSONObject()
                .put("schema", "ge360.backup.v1")
                .put("createdAt", System.currentTimeMillis())
                .put("appVersion", installedVersion())
                .put("automaticMediaPolicy", "daily=allData+scannerPDF+jobsiteArchivePDF+galleryFiles")
                .put("includesGalleryFiles", includeGalleryFiles)
                .put("scannerFiles", scannerMap)
                .put("metricAgreementFiles", evidenceMap)
                .put("jobsiteArchiveFiles", jobsiteArchiveMap)
                .put("galleryFiles", galleryMap)
                .put("galleryFilesSkipped", gallerySkipped)
                .put("modules", JSONArray(textEntries.keys.toList()))

            val info = archive.create(entries, manifest, if (includeGalleryFiles) "GE360_FULL" else "GE360_AUTO")
            val driveDone = copyToDrive && archive.driveReady() && runCatching { archive.copyToDrive(info.file) }.isSuccess
            return CreateResult(info, driveDone, galleryIncluded, gallerySkipped, scannerFiles)
        } finally {
            closeAll(clients, jobs, workflow, jobsiteArchive, daily, compensi, messages, finances, documents, fir, estimates, estimateRooms, estimateLifecycle, floorPlans, tools, gallery)
        }
    }

    fun importFromUri(uri: Uri): File {
        val target = File(context.cacheDir, "restore_${System.currentTimeMillis()}.ge360.zip")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Backup non leggibile" }
            FileOutputStream(target).use { input.copyTo(it, 64 * 1024) }
        }
        archive.inspect(target, verifyHashes = true)
        return target
    }

    fun restore(file: File, replaceAll: Boolean): RestoreResult = restoreInternal(file, replaceAll, guardRollback = true)

    private fun restoreInternal(file: File, replaceAll: Boolean, guardRollback: Boolean): RestoreResult {
        archive.inspect(file, verifyHashes = true)
        val manifest = archive.readManifest(file)
        require(manifest.optString("schema") == "ge360.backup.v1") { "Versione backup non supportata" }
        preflightRestore(file)

        val counts = linkedMapOf<String, Int>()
        val warnings = mutableListOf<String>()
        val safety = if (guardRollback) {
            runCatching { createBackup(includeGalleryFiles = true, copyToDrive = false) }
                .getOrElse { throw IllegalStateException("Ripristino annullato: impossibile creare il backup completo di sicurezza dei dati correnti (${it.message})", it) }
        } else null
        safety?.let { warnings += "Backup completo di sicurezza creato prima del ripristino: ${it.info.file.name}" }

        val clients = ClientStore(context)
        val jobs = JobsiteStore(context)
        val workflow = JobsiteWorkflowStore(context)
        val jobsiteArchive = JobsiteArchiveStore(context)
        val daily = DailyStore(context)
        val compensi = CompensationStore(context)
        val messages = TemplateStore(context)
        val finances = FinanceStore(context)
        val documents = DocumentStore(context)
        val fir = FirStore(context)
        val estimates = EstimateStore(context)
        val estimateRooms = EstimateRoomStore(context)
        val estimateLifecycle = EstimateLifecycleStore(context)
        val estimateCustomizations = PreventiviCustomizationStore(context)
        val floorPlans = FloorPlanStore(context)
        val tools = ToolsStore(context)
        val customWorkPacks = CustomWorkPackStore(context)
        val vanPreparations = VanPreparationStore(context)
        val gallery = GalleryStore(context)
        try {
            counts["Clienti"] = clients.importJson(archive.readText(file, "modules/clienti.json"), replaceAll)
            val clientIds = clients.search().filter { it.ge360Id.isNotBlank() }.associate { it.ge360Id to it.id }

            val jobsJson = remapJobsiteClientIds(archive.readText(file, "modules/cantieri.json"), clientIds)
            counts["Cantieri"] = jobs.importJson(jobsJson, replaceAll)

            val workflowJson = runCatching { archive.readText(file, "modules/centro-cantiere.json") }.getOrNull()
            if (workflowJson != null) counts["Centro Cantiere"] = workflow.importJson(workflowJson, replaceAll)
            else warnings += "Backup precedente al Centro Cantiere operativo: checklist e note operative non presenti nel pacchetto."

            val jobsiteArchiveJson = runCatching { archive.readText(file, "modules/jobsite-archive.json") }.getOrNull()
            if (jobsiteArchiveJson != null) {
                counts["Cartella Cantiere"] = restoreJobsiteArchive(file, manifest, jobsiteArchiveJson, jobsiteArchive, replaceAll, warnings)
            } else {
                warnings += "Backup precedente a Planimetrie/Appunti cliente: nessun dato Cartella Cantiere da ripristinare."
            }

            val dailyJson = runCatching { archive.readText(file, "modules/giornata.json") }.getOrNull()
            if (dailyJson != null) counts["Giornata"] = daily.importJson(dailyJson, replaceAll)
            else warnings += "Backup precedente a Giornata: nessun dato ore/rapportini da ripristinare."

            val compensiJson = runCatching { archive.readText(file, "modules/compensi.json") }.getOrNull()
            if (compensiJson != null) counts["Compensi"] = compensi.importJson(compensiJson, replaceAll)
            else warnings += "Backup precedente al nuovo registro Compensi: archivio Compensi attuale conservato, nessun pagamento storico dedotto."

            counts["Messaggi"] = messages.importJson(archive.readText(file, "modules/messaggi.json"), replaceAll)
            counts["Spese"] = finances.importJson(archive.readText(file, "modules/spese.json"), replaceAll)

            val estimatesJson = remapEstimateClientIds(archive.readText(file, "modules/preventivi.json"), clientIds)
            // Metadata of agreements is imported first; every file must then be
            // restored from a ZIP entry and SHA-256 verified before continuing.
            counts["Preventivi"] = estimates.importJson(
                estimatesJson, replaceAll, allowEvidenceBytesFromZip = true
            )
            restoreMetricAgreementFiles(file, manifest, estimates)

            val roomsJson = runCatching { archive.readText(file, "modules/preventivi-rooms.json") }.getOrNull()
            if (roomsJson != null) counts["Ambienti preventivo"] = estimateRooms.importJson(roomsJson, replaceAll)
            else warnings += "Backup precedente agli ambienti completi del preventivo: ricostruzione disponibile solo dai metadati delle voci."

            val lifecycleJson = runCatching { archive.readText(file, "modules/preventivi-lifecycle.json") }.getOrNull()
            if (lifecycleJson != null) counts["Cronologia preventivi"] = estimateLifecycle.importJson(lifecycleJson, replaceAll)
            else warnings += "Backup precedente alla cronologia preventivi: revisioni e snapshot accettati non presenti."

            val customizationsJson = runCatching { archive.readText(file, "modules/preventivi-customizations.json") }.getOrNull()
            if (customizationsJson != null) counts["Personalizzazioni preventivi"] = estimateCustomizations.importFullJson(customizationsJson, replaceAll)
            else warnings += "Backup precedente alle personalizzazioni complete dei preventivi."

            val floorPlansJson = runCatching { archive.readText(file, "modules/planimetrie.json") }.getOrNull()
            if (floorPlansJson != null) counts["Planimetrie"] = floorPlans.importJson(floorPlansJson, replaceAll)
            else warnings += "Backup precedente al modulo Rilievi / Planimetrie: nessun rilievo da ripristinare."

            counts["Attrezzi"] = tools.importJson(archive.readText(file, "modules/attrezzi.json"), replaceAll)

            val customPacksJson = runCatching { archive.readText(file, "modules/attrezzi-custom-packs.json") }.getOrNull()
            if (customPacksJson != null) counts["Pacchetti personalizzati"] = customWorkPacks.importJson(customPacksJson, replaceAll)
            else warnings += "Backup precedente ai pacchetti Attrezzi/Materiali personalizzati."

            val vanJson = runCatching { archive.readText(file, "modules/furgone.json") }.getOrNull()
            if (vanJson != null) counts["Preparazioni furgone"] = vanPreparations.importJson(vanJson, replaceAll)
            else warnings += "Backup precedente alle preparazioni Furgone."

            val scannerRestored = restoreScanner(file, manifest, documents, replaceAll)
            counts["Scanner"] = scannerRestored.first
            if (scannerRestored.second < scannerRestored.first) warnings += "Alcuni record Scanner non avevano il PDF nel pacchetto."

            val firJson = runCatching { archive.readText(file, "modules/fir.json") }.getOrNull()
            if (firJson != null) counts["FIR"] = fir.importJson(firJson, replaceAll)
            else warnings += "Backup precedente al modulo FIR: nessun formulario rifiuti da ripristinare."

            val galleryRestored = restoreGallery(file, manifest, gallery, jobs, replaceAll, warnings)
            counts["Galleria"] = galleryRestored.first
            restoreFiscalPrefs(archive.readText(file, "modules/fisco-settings.json"))
            return RestoreResult(counts, scannerRestored.second, galleryRestored.second, warnings.distinct())
        } catch (error: Throwable) {
            if (guardRollback && safety != null) {
                val rollback = runCatching { restoreInternal(safety.info.file, replaceAll = true, guardRollback = false) }
                if (rollback.isFailure) {
                    throw IllegalStateException(
                        "Ripristino interrotto e rollback automatico fallito. Backup di sicurezza: ${safety.info.file.name}. Errore iniziale: ${error.message}. Errore rollback: ${rollback.exceptionOrNull()?.message}",
                        error
                    )
                }
                throw IllegalStateException("Ripristino interrotto; i dati precedenti sono stati ripristinati automaticamente. Motivo: ${error.message}", error)
            }
            throw error
        } finally {
            closeAll(clients, jobs, workflow, jobsiteArchive, daily, compensi, messages, finances, documents, fir, estimates, estimateRooms, estimateLifecycle, floorPlans, tools, gallery)
        }
    }

    private fun preflightRestore(file: File) {
        val required = listOf(
            "modules/clienti.json",
            "modules/cantieri.json",
            "modules/messaggi.json",
            "modules/spese.json",
            "modules/scanner.json",
            "modules/preventivi.json",
            "modules/attrezzi.json",
            "modules/galleria.json",
            "modules/fisco-settings.json"
        )
        required.forEach { path ->
            val parsed = JSONObject(archive.readText(file, path))
            require(parsed.length() > 0) { "Modulo backup vuoto o non valido: $path" }
        }
        runCatching { archive.readText(file, "modules/giornata.json") }.getOrNull()?.let {
            require(JSONObject(it).length() > 0) { "Modulo Giornata non valido" }
        }
        runCatching { archive.readText(file, "modules/fir.json") }.getOrNull()?.let {
            require(JSONObject(it).length() > 0) { "Modulo FIR non valido" }
        }
        runCatching { archive.readText(file, "modules/centro-cantiere.json") }.getOrNull()?.let {
            require(JSONObject(it).length() > 0) { "Modulo Centro Cantiere non valido" }
        }
        runCatching { archive.readText(file, "modules/jobsite-archive.json") }.getOrNull()?.let {
            require(JSONObject(it).length() > 0) { "Modulo Cartella Cantiere non valido" }
        }
        runCatching { archive.readText(file, "modules/planimetrie.json") }.getOrNull()?.let {
            val parsed = JSONObject(it)
            require(parsed.optString("schema") == FloorPlanStore.BACKUP_SCHEMA) { "Modulo Planimetrie non valido" }
        }
        val optionalSchemas = mapOf(
            "modules/preventivi-rooms.json" to EstimateRoomStore.BACKUP_SCHEMA,
            "modules/preventivi-lifecycle.json" to EstimateLifecycleStore.BACKUP_SCHEMA,
            "modules/preventivi-customizations.json" to PreventiviCustomizationStore.FULL_BACKUP_SCHEMA,
            "modules/attrezzi-custom-packs.json" to CustomWorkPackStore.BACKUP_SCHEMA,
            "modules/compensi.json" to CompensationStore.SCHEMA,
            "modules/furgone.json" to VanPreparationStore.BACKUP_SCHEMA
        )
        optionalSchemas.forEach { (path, schema) ->
            runCatching { archive.readText(file, path) }.getOrNull()?.let { json ->
                require(JSONObject(json).optString("schema") == schema) { "Modulo backup non valido: $path" }
            }
        }
    }

    private fun restoreMetricAgreementFiles(
        file: File, manifest: JSONObject, estimates: EstimateStore
    ) {
        val paths = manifest.optJSONObject("metricAgreementFiles") ?: JSONObject()
        estimates.list().forEach { quote ->
            estimates.agreementAttachments(quote.ge360Id).forEach { attachment ->
                val target = MetricAgreementFilesV5.fileFor(context, attachment)
                val zipPath = paths.optString(attachment.ge360Id)
                if (zipPath.isBlank()) {
                    // Keep pre-existing evidence on a non-destructive import only if
                    // its SHA is already correct. Otherwise reject incomplete ZIP.
                    require(MetricAgreementFilesV5.existsAndMatches(context, attachment)) {
                        "Backup privo del file dell'accordo: ${attachment.fileName}"
                    }
                } else {
                    val exactPath = "files/metric-agreements/" + target.name
                    require(zipPath == exactPath) {
                        "Percorso allegato non attendibile o formato differente"
                    }
                    if (!MetricAgreementFilesV5.existsAndMatches(context, attachment)) {
                        require(target.parentFile?.mkdirs() == true ||
                            target.parentFile?.isDirectory == true)
                        val temp = File(target.parentFile,
                            "${attachment.ge360Id}.restore-partial")
                        try {
                            archive.extractEntry(file, zipPath, temp)
                            require(temp.isFile && temp.length() == attachment.sizeBytes)
                            require(!target.exists() || target.delete())
                            check(temp.renameTo(target)) {
                                "Impossibile ripristinare copia privata accordo"
                            }
                            require(MetricAgreementFilesV5.existsAndMatches(context, attachment)) {
                                "SHA-256 dell'allegato non coincide con il registro"
                            }
                        } finally { temp.delete() }
                    }
                }
            }
        }
    }

    private fun remapJobsiteClientIds(json: String, clientIds: Map<String, Long>): String {
        val root = JSONObject(json)
        val rows = root.optJSONArray("jobsites") ?: return json
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val uuid = row.optString("clientGe360Id", "")
            row.put("clientId", clientIds[uuid] ?: 0L)
        }
        return root.toString()
    }

    private fun remapEstimateClientIds(json: String, clientIds: Map<String, Long>): String {
        val root = JSONObject(json)
        val rows = root.optJSONArray("estimates") ?: return json
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val uuid = row.optString("clientGe360Id", "")
            row.put("clientId", clientIds[uuid] ?: 0L)
        }
        return root.toString()
    }

    private fun restoreScanner(
        file: File,
        manifest: JSONObject,
        store: DocumentStore,
        replaceAll: Boolean
    ): Pair<Int, Int> {
        if (replaceAll) store.list().forEach { old ->
            if (old.pdfPath.isNotBlank()) runCatching { File(old.pdfPath).takeIf { it.exists() }?.delete() }
            store.delete(old.id)
        }
        val root = JSONObject(archive.readText(file, "modules/scanner.json"))
        val rows = root.optJSONArray("documents") ?: JSONArray()
        val paths = manifest.optJSONObject("scannerFiles") ?: JSONObject()
        var count = 0
        var files = 0
        val current = store.list().associateBy { it.ge360Id }.toMutableMap()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val id = o.optString("ge360_id", UUID.randomUUID().toString())
            val type = o.optString("type", "ALTRO")
            val purchaseDate = o.optString("document_date", "")
            val toolName = o.optString("tool_name", "")
            val existing = current[id]
            var pdfPath = existing?.pdfPath.orEmpty()
            val zipPath = paths.optString(id, "")
            if (zipPath.isNotBlank()) {
                val target = if (type.equals(ToolWarrantyArchive.DOCUMENT_TYPE, ignoreCase = true)) {
                    ToolWarrantyArchive.targetFile(context, purchaseDate, toolName, id)
                } else {
                    File(context.filesDir, "scanner/restored_${safe(id)}.pdf")
                }
                archive.extractEntry(file, zipPath, target)
                pdfPath = target.absolutePath
                files++
            }
            val d = ScannerDocument(
                id = existing?.id ?: 0,
                ge360Id = id,
                type = type,
                documentDate = purchaseDate,
                documentNumber = o.optString("document_number", ""),
                supplier = o.optString("supplier", ""),
                category = o.optString("category", ""),
                taxableAmount = o.optNullableDouble("taxable_amount"),
                vatAmount = o.optNullableDouble("vat_amount"),
                totalAmount = o.optNullableDouble("total_amount"),
                confidence = o.optDouble("confidence", 0.0),
                ocrText = o.optString("ocr_text", ""),
                pdfPath = pdfPath,
                jobsiteId = o.optString("jobsite_id", ""),
                jobsiteCode = o.optString("jobsite_code", ""),
                jobsiteTitle = o.optString("jobsite_title", ""),
                expenseGe360Id = o.optString("expense_ge360_id", ""),
                status = o.optString("status", ScannerDocument.STATUS_REVIEWED),
                createdAt = o.optLong("created_at", System.currentTimeMillis()),
                updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
                toolName = toolName,
                toolType = o.optString("tool_type", "")
            )
            store.save(d)
            current[id] = d
            count++
        }
        ToolWarrantyArchive.writeIndex(context, store.list())
        return count to files
    }

    private fun restoreJobsiteArchive(
        file: File,
        manifest: JSONObject,
        json: String,
        store: JobsiteArchiveStore,
        replaceAll: Boolean,
        warnings: MutableList<String>
    ): Int {
        val root = JSONObject(json)
        val rows = root.optJSONArray("files") ?: JSONArray()
        val paths = manifest.optJSONObject("jobsiteArchiveFiles") ?: JSONObject()
        val restoredPaths = mutableMapOf<String, String>()

        if (replaceAll) {
            store.allFiles().forEach { old ->
                if (old.filePath.isNotBlank()) runCatching { File(old.filePath).takeIf { it.exists() }?.delete() }
            }
        }

        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.optString("ge360Id", "")
            if (id.isBlank()) continue
            restoredPaths[id] = ""
            val zipPath = paths.optString(id, "")
            if (zipPath.isBlank()) {
                warnings += "Cartella Cantiere: file ${row.optString("title", id)} non presente nel backup; metadati conservati."
                continue
            }
            val target = File(context.filesDir, "scanner/jobsite_archive/restored_${safe(id)}.pdf")
            archive.extractEntry(file, zipPath, target)
            restoredPaths[id] = target.absolutePath
        }
        return store.importJson(json, replaceAll, restoredPaths)
    }

    private fun restoreGallery(
        file: File,
        manifest: JSONObject,
        store: GalleryStore,
        jobs: JobsiteStore,
        replaceAll: Boolean,
        warnings: MutableList<String>
    ): Pair<Int, Int> {
        val galleryJson = archive.readText(file, "modules/galleria.json")
        val root = JSONObject(galleryJson)
        val rows = root.optJSONArray("photos") ?: JSONArray()
        val fileMap = manifest.optJSONObject("galleryFiles") ?: JSONObject()
        val hasFiles = manifest.optBoolean("includesGalleryFiles", false) && fileMap.length() > 0
        val galleryArchive = GalleryArchive(context)

        if (hasFiles && !galleryArchive.localReady()) {
            warnings += "Il backup contiene le fotografie originali, ma la cartella archivio Galleria non è configurata. Galleria è stata lasciata invariata: configura la cartella locale e riesegui il restore per non perdere i file."
            return 0 to 0
        }

        if (!hasFiles) {
            val sanitized = sanitizeGalleryMetadata(galleryJson, galleryArchive, warnings)
            return store.importMetadata(sanitized, replaceAll) to 0
        }

        if (replaceAll) {
            val empty = JSONObject().put("schema", "ge360.galleria.v1").put("photos", JSONArray()).toString()
            store.importMetadata(empty, true)
        }
        var records = 0
        var files = 0
        val existingById = store.list().associateBy { it.ge360Id }.toMutableMap()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val id = o.optString("ge360Id", UUID.randomUUID().toString())
            val jobId = o.optString("jobsiteId", "")
            val job = jobs.getByGe360Id(jobId)
            val zipPath = fileMap.optString(id, "")
            val existing = existingById[id]
            if (job != null && zipPath.isNotBlank()) {
                val stored = archive.openEntry(file, zipPath).use { input ->
                    galleryArchive.copyStreamToLocal(
                        input,
                        job,
                        o.optString("phase", "DURANTE"),
                        o.optString("fileName", "foto.jpg"),
                        o.optString("mimeType", "image/jpeg")
                    )
                }
                val photo = GalleryPhoto(
                    id = existing?.id ?: 0,
                    ge360Id = id,
                    jobsiteId = jobId,
                    jobsiteCode = o.optString("jobsiteCode", job.code),
                    jobsiteTitle = o.optString("jobsiteTitle", job.displayTitle()),
                    phase = o.optString("phase", "DURANTE"),
                    localUri = stored.uri,
                    fileName = stored.fileName,
                    mimeType = stored.mimeType,
                    sizeBytes = stored.sizeBytes,
                    sha256 = stored.sha256,
                    note = o.optString("note", ""),
                    tags = o.optString("tags", ""),
                    favorite = o.optBoolean("favorite", false),
                    driveUri = "",
                    driveStatus = GalleryPhoto.DRIVE_PENDING,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
                store.save(photo)
                existingById[id] = photo
                files++
            } else {
                if (job == null && jobId.isNotBlank()) warnings += "Foto ${o.optString("fileName")}: cantiere GE360 non trovato; ripristinati solo i metadati disponibili."
                if (zipPath.isBlank()) warnings += "Foto ${o.optString("fileName")}: originale non presente nel pacchetto completo."
                val sanitizedOne = sanitizeGalleryMetadata(
                    JSONObject().put("schema", "ge360.galleria.v1").put("photos", JSONArray().put(o)).toString(),
                    galleryArchive,
                    warnings
                )
                store.importMetadata(sanitizedOne, false)
            }
            records++
        }
        return records to files
    }

    private fun sanitizeGalleryMetadata(
        json: String,
        galleryArchive: GalleryArchive,
        warnings: MutableList<String>
    ): String {
        val root = JSONObject(json)
        val rows = root.optJSONArray("photos") ?: JSONArray()
        var unavailableLocal = 0
        var unavailableDrive = 0
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val localUri = row.optString("localUri", "")
            if (!galleryArchive.readable(localUri)) {
                if (localUri.isNotBlank()) unavailableLocal++
                row.put("localUri", "")
            }
            val driveUri = row.optString("driveUri", "")
            if (!galleryArchive.readable(driveUri)) {
                if (driveUri.isNotBlank()) unavailableDrive++
                row.put("driveUri", "")
                row.put("driveStatus", GalleryPhoto.DRIVE_PENDING)
            }
        }
        if (unavailableLocal > 0) {
            warnings += "$unavailableLocal riferimenti foto locali appartenevano a un archivio non accessibile su questo dispositivo: note/tag sono stati conservati, ma il file locale è segnato come mancante."
        }
        if (unavailableDrive > 0) {
            warnings += "$unavailableDrive riferimenti Drive non sono accessibili su questo dispositivo e sono stati rimessi in attesa di sincronizzazione."
        }
        return root.toString()
    }

    private fun fiscalPrefsJson(): String {
        val p = context.getSharedPreferences("ge360_fisco_settings", Context.MODE_PRIVATE)
        return JSONObject()
            .put("schema", "ge360.fisco.settings.v1")
            .put("inps35", p.getBoolean("inps35", false))
            .put("passVat", p.getBoolean("passVat", true))
            .put("rate", p.getInt("rate", 15))
            .put("privateShare", p.getFloat("privateShare", 95f).toDouble())
            .put("stamps", p.getInt("stamps", 0))
            .toString(2)
    }

    private fun restoreFiscalPrefs(json: String) {
        val o = JSONObject(json)
        context.getSharedPreferences("ge360_fisco_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("inps35", o.optBoolean("inps35", false))
            .putBoolean("passVat", o.optBoolean("passVat", true))
            .putInt("rate", o.optInt("rate", 15))
            .putFloat("privateShare", o.optDouble("privateShare", 95.0).toFloat())
            .putInt("stamps", o.optInt("stamps", 0))
            .apply()
    }

    private fun installedVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("").ifBlank { "unknown" }

    private fun JSONObject.optNullableDouble(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun safe(value: String) = value.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80).ifBlank { UUID.randomUUID().toString() }
    private fun closeAll(vararg closeables: AutoCloseable) = closeables.forEach { runCatching { it.close() } }
}
