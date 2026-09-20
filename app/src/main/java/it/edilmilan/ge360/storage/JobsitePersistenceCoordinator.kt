package it.edilmilan.ge360.storage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import it.edilmilan.ge360.attrezzi.ToolsStore
import it.edilmilan.ge360.attrezzi.VanPreparationStore
import it.edilmilan.ge360.backup.JobsiteReplicaCoordinator
import it.edilmilan.ge360.cantieri.JobsiteRecord
import it.edilmilan.ge360.cantieri.JobsiteStore
import it.edilmilan.ge360.centrocantiere.JobsiteArchiveFile
import it.edilmilan.ge360.centrocantiere.JobsiteArchiveStore
import it.edilmilan.ge360.centrocantiere.JobsiteWorkflowStore
import it.edilmilan.ge360.clienti.ClientStore
import it.edilmilan.ge360.core.common.ContentFingerprint
import it.edilmilan.ge360.galleria.GalleryPhoto
import it.edilmilan.ge360.galleria.GalleryStore
import it.edilmilan.ge360.giornata.DailyStore
import it.edilmilan.ge360.planimetrie.FloorPlanStore
import it.edilmilan.ge360.preventivi.EstimateStore
import it.edilmilan.ge360.preventivi.EstimateLifecycleStore
import it.edilmilan.ge360.preventivi.EstimateRoomStore
import it.edilmilan.ge360.preventivi.PreventiviCustomizationStore
import it.edilmilan.ge360.scanner.DocumentStore
import it.edilmilan.ge360.scanner.FirStore
import it.edilmilan.ge360.scanner.ScannerDocument
import it.edilmilan.ge360.spese.FinanceStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistenza fisica di sicurezza per ogni cantiere.
 *
 * I database SQLite restano la fonte operativa dell'app, ma ogni cantiere possiede anche
 * una cartella completa nella memoria del dispositivo. La cartella contiene snapshot JSON
 * leggibili, cronologia, note, documenti scanner/FIR, planimetrie/documenti cliente e foto.
 *
 * Questo coordinatore e' intenzionalmente non distruttivo: sincronizza e aggiorna i file
 * correnti, ma non elimina automaticamente vecchi allegati dal vault fisico. In questo modo
 * un errore UI o una cancellazione accidentale non rende irrecuperabile un documento.
 */
object JobsitePersistenceCoordinator {
    private const val TAG = "GE360_JOB_VAULT"
    private const val ROOT = "GE360/Cantieri"
    private const val STATE_FILE = "SYNC-STATE.json"
    private const val DEBOUNCE_MS = 900L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ge360-jobsite-persistence").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)
    private var installed = false
    private var appContext: Context? = null

    private val delayedSync = Runnable {
        appContext?.let(::submitSync)
    }

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        appContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                requestSync(application.applicationContext)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                // Quando l'app passa in background non attendiamo il debounce.
                mainHandler.removeCallbacks(delayedSync)
                submitSync(application.applicationContext)
            }
        })
        requestSync(application.applicationContext)
    }

    fun requestSync(context: Context) {
        appContext = context.applicationContext
        mainHandler.removeCallbacks(delayedSync)
        mainHandler.postDelayed(delayedSync, DEBOUNCE_MS)
    }

    private fun submitSync(context: Context) {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true)
            return
        }
        executor.execute {
            var materializedChanges = false
            try {
                materializedChanges = syncAll(context.applicationContext)
            } catch (error: Throwable) {
                Log.e(TAG, "Sincronizzazione cartelle cantiere fallita", error)
                appendGlobalError(context, error)
            } finally {
                running.set(false)
                if (materializedChanges) JobsiteReplicaCoordinator.requestSync(context)
                if (rerun.getAndSet(false)) requestSync(context)
            }
        }
    }

    private fun syncAll(context: Context): Boolean {
        val jobs = JobsiteStore(context)
        val clients = ClientStore(context)
        val workflow = JobsiteWorkflowStore(context)
        val archive = JobsiteArchiveStore(context)
        val daily = DailyStore(context)
        val finances = FinanceStore(context)
        val documents = DocumentStore(context)
        val fir = FirStore(context)
        val estimates = EstimateStore(context)
        val estimateRooms = EstimateRoomStore(context)
        val estimateLifecycle = EstimateLifecycleStore(context)
        val estimateCustomizations = PreventiviCustomizationStore(context)
        val floorPlans = FloorPlanStore(context)
        val tools = ToolsStore(context)
        val vanPreparations = VanPreparationStore(context)
        val gallery = GalleryStore(context)

        try {
            val jobsList = jobs.list()
            if (jobsList.isEmpty()) return false

            // Gli export vengono calcolati una sola volta per ciclo e poi filtrati per cantiere.
            val moduleExports = linkedMapOf(
                "cantieri" to jobs.exportJson(),
                "centro-cantiere" to workflow.exportJson(),
                "cartella-cantiere" to archive.exportJson(),
                "giornata" to daily.exportJson(),
                "spese" to finances.exportJson(),
                "scanner" to documents.exportJson(),
                "fir" to fir.exportJson(),
                "preventivi" to estimates.exportJson(),
                "preventivi-ambienti" to estimateRooms.exportJson(),
                "preventivi-cronologia" to estimateLifecycle.exportJson(),
                "preventivi-personalizzazioni" to estimateCustomizations.exportFullJson(),
                "planimetrie" to floorPlans.exportJson(),
                "attrezzi" to tools.exportJson(),
                "furgone" to vanPreparations.exportJson(),
                "galleria" to gallery.exportJson()
            )
            val clientsExport = clients.exportJson()
            val estimateIdsByJobsite = estimates.list()
                .filter { it.jobsiteId.isNotBlank() }
                .groupBy({ it.jobsiteId }, { it.ge360Id })

            var changed = false
            jobsList.forEach { job ->
                runCatching {
                    val relatedIds = (estimateIdsByJobsite[job.ge360Id].orEmpty() + job.ge360Id).toSet()
                    val snapshots = moduleExports.mapValues { (name, json) ->
                        if (name == "preventivi-personalizzazioni") {
                            JSONObject(json).apply { remove("exportedAt"); remove("exported_at") }
                        } else {
                            filterExport(json, relatedIds)
                        }
                    }
                    val clientSnapshot = if (job.clientGe360Id.isBlank()) JSONObject()
                        else filterExport(clientsExport, job.clientGe360Id)
                    val jobDocuments = documents.forJobsite(job.ge360Id)
                    val jobArchiveFiles = archive.files(job.ge360Id)
                    val jobPhotos = gallery.list(jobsiteId = job.ge360Id)
                    val root = jobsiteRoot(context, job)
                    val fingerprint = fingerprint(snapshots, clientSnapshot, jobDocuments, jobArchiveFiles, jobPhotos)
                    if (storedFingerprint(root) == fingerprint) return@runCatching
                    syncJobsite(
                        context = context,
                        job = job,
                        root = root,
                        moduleSnapshots = snapshots,
                        clientSnapshot = clientSnapshot,
                        documents = jobDocuments,
                        archiveFiles = jobArchiveFiles,
                        photos = jobPhotos,
                        fingerprint = fingerprint
                    )
                    changed = true
                }.onFailure { error ->
                    Log.e(TAG, "Errore archivio cantiere ${job.code}/${job.ge360Id}", error)
                    appendJobError(context, job, error)
                }
            }
            return changed
        } finally {
            runCatching { jobs.close() }
            runCatching { clients.close() }
            runCatching { workflow.close() }
            runCatching { archive.close() }
            runCatching { daily.close() }
            runCatching { finances.close() }
            runCatching { documents.close() }
            runCatching { fir.close() }
            runCatching { estimates.close() }
            runCatching { estimateRooms.close() }
            runCatching { estimateLifecycle.close() }
            runCatching { floorPlans.close() }
            runCatching { tools.close() }
            runCatching { gallery.close() }
        }
    }

    private fun syncJobsite(
        context: Context,
        job: JobsiteRecord,
        root: File,
        moduleSnapshots: Map<String, JSONObject>,
        clientSnapshot: JSONObject,
        documents: List<ScannerDocument>,
        archiveFiles: List<JobsiteArchiveFile>,
        photos: List<GalleryPhoto>,
        fingerprint: String
    ) {
        if (job.ge360Id.isBlank()) return
        val now = System.currentTimeMillis()

        writeAtomic(
            File(root, "cantiere.json"),
            JSONObject().apply {
                put("schema", "ge360.jobsite-folder.v1")
                put("jobsiteId", job.ge360Id)
                put("code", job.code)
                put("title", job.displayTitle())
                put("clientId", job.clientId)
                put("clientGe360Id", job.clientGe360Id)
                put("clientName", job.clientName)
                put("address", job.address)
                put("workType", job.workType)
                put("status", job.status)
                put("startDate", job.startDate)
                put("targetEndDate", job.targetEndDate)
                put("notes", job.notes)
                put("createdAt", job.createdAt)
                put("updatedAt", job.updatedAt)
                put("folderSyncedAt", now)
            }.toString(2)
        )

        val dataDir = File(root, "DATI").apply { mkdirs() }
        moduleSnapshots.forEach { (name, filtered) ->
            writeAtomic(
                File(dataDir, "$name.json"),
                JSONObject().apply {
                    put("schema", "ge360.jobsite-module-snapshot.v1")
                    put("jobsiteId", job.ge360Id)
                    put("module", name)
                    put("syncedAt", now)
                    put("data", JSONObject(filtered.toString()))
                }.toString(2)
            )
        }

        if (job.clientGe360Id.isNotBlank()) {
            writeAtomic(
                File(dataDir, "cliente.json"),
                JSONObject().apply {
                    put("schema", "ge360.jobsite-client-snapshot.v1")
                    put("jobsiteId", job.ge360Id)
                    put("clientGe360Id", job.clientGe360Id)
                    put("syncedAt", now)
                    put("data", JSONObject(clientSnapshot.toString()))
                }.toString(2)
            )
        }

        writeChronology(root, job.ge360Id, moduleSnapshots["centro-cantiere"] ?: JSONObject(), now)
        writeFirRegister(root, job.ge360Id, moduleSnapshots["fir"] ?: JSONObject(), now)
        mirrorScannerDocuments(context, root, documents)
        mirrorArchiveFiles(root, archiveFiles)
        mirrorGallery(context, root, photos)
        writeMediaIndex(root, documents, archiveFiles, photos, now)

        writeAtomic(
            File(root, "LEGGIMI.txt"),
            buildString {
                appendLine("GE360 - CARTELLA CANTIERE")
                appendLine("Codice: ${job.code}")
                appendLine("Cantiere: ${job.displayTitle()}")
                appendLine("ID stabile: ${job.ge360Id}")
                appendLine("Ultima sincronizzazione: ${formatTime(now)}")
                appendLine()
                appendLine("Contenuto:")
                appendLine("- CRONOLOGIA: storico operativo completo")
                appendLine("- DOCUMENTI/FIR: PDF/scansioni FIR e registro strutturato")
                appendLine("- DOCUMENTI/SCANNER: altri documenti acquisiti")
                appendLine("- DOCUMENTI/PLANIMETRIE e DOCUMENTI/CLIENTE: archivio cantiere")
                appendLine("- FOTO: foto per fase del cantiere")
                appendLine("- DATI: snapshot JSON dei moduli GE360 collegati al cantiere")
                appendLine("- DATI/planimetrie.json: rilievo strutturato collegato al cantiere")
                appendLine("- LOG: eventuali errori di sincronizzazione")
                appendLine()
                appendLine("I file storici non vengono cancellati automaticamente dalla sincronizzazione.")
            }
        )
        writeAtomic(
            File(root, STATE_FILE),
            JSONObject()
                .put("schema", "ge360.jobsite-sync-state.v1")
                .put("jobsiteId", job.ge360Id)
                .put("fingerprint", fingerprint)
                .put("materializedAt", now)
                .toString(2)
        )
    }

    private fun writeChronology(root: File, jobsiteId: String, filtered: JSONObject, syncedAt: Long) {
        val folder = File(root, "CRONOLOGIA").apply { mkdirs() }
        writeAtomic(
            File(folder, "cronologia.json"),
            JSONObject().apply {
                put("schema", "ge360.jobsite-history.v1")
                put("jobsiteId", jobsiteId)
                put("syncedAt", syncedAt)
                put("data", filtered)
            }.toString(2)
        )

        val events = filtered.optJSONArray("events") ?: JSONArray()
        val text = buildString {
            appendLine("GE360 - CRONOLOGIA CANTIERE")
            appendLine("Cantiere ID: $jobsiteId")
            appendLine("Aggiornata: ${formatTime(syncedAt)}")
            appendLine("Eventi: ${events.length()}")
            appendLine()
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val whenAt = event.optLong("createdAt", event.optLong("created_at", 0L))
                val type = event.optString("eventType", event.optString("event_type", "INFO"))
                val title = event.optString("title", "")
                val detail = event.optString("detail", "")
                append("[").append(if (whenAt > 0) formatTime(whenAt) else "data non disponibile").append("] ")
                append(type).append(" - ").append(title)
                if (detail.isNotBlank()) append(" | ").append(detail)
                appendLine()
            }
        }
        writeAtomic(File(folder, "cronologia.txt"), text)
    }

    private fun writeFirRegister(root: File, jobsiteId: String, filtered: JSONObject, syncedAt: Long) {
        val folder = File(root, "DOCUMENTI/FIR").apply { mkdirs() }
        writeAtomic(
            File(folder, "registro-fir.json"),
            JSONObject().apply {
                put("schema", "ge360.jobsite-fir-register.v1")
                put("jobsiteId", jobsiteId)
                put("syncedAt", syncedAt)
                put("data", JSONObject(filtered.toString()))
            }.toString(2)
        )
    }

    private fun mirrorScannerDocuments(context: Context, root: File, rows: List<ScannerDocument>) {
        rows.forEach { doc ->
            if (doc.pdfPath.isBlank()) return@forEach
            val isFir = doc.type.equals("FIR", ignoreCase = true)
            val folder = if (isFir) {
                File(root, "DOCUMENTI/FIR")
            } else {
                File(root, "DOCUMENTI/SCANNER/${safe(doc.type.ifBlank { "ALTRO" })}")
            }.apply { mkdirs() }

            val label = if (isFir) {
                listOf("FIR", doc.documentNumber, doc.documentDate).filter { it.isNotBlank() }.joinToString("_")
            } else {
                listOf(doc.type, doc.documentNumber, doc.documentDate).filter { it.isNotBlank() }.joinToString("_")
            }.ifBlank { "documento" }
            val target = File(folder, "${safe(label)}_${safe(doc.ge360Id)}.pdf")
            copySource(context, doc.pdfPath, target, doc.updatedAt)
        }
    }

    private fun mirrorArchiveFiles(root: File, rows: List<JobsiteArchiveFile>) {
        rows.forEach { item ->
            val source = item.filePath.takeIf { it.isNotBlank() }?.let(::File) ?: return@forEach
            if (!source.exists() || !source.isFile) return@forEach
            val category = when (item.category.uppercase(Locale.ROOT)) {
                "PLANIMETRIA" -> "PLANIMETRIE"
                "DOCUMENTO_CLIENTE" -> "CLIENTE"
                else -> safe(item.category.ifBlank { "ALTRO" })
            }
            val folder = File(root, "DOCUMENTI/$category").apply { mkdirs() }
            val ext = source.extension.ifBlank { "pdf" }
            val name = "${safe(item.title.ifBlank { category })}_${safe(item.ge360Id)}.$ext"
            copyFile(source, File(folder, name), item.updatedAt)
        }
    }

    private fun mirrorGallery(context: Context, root: File, rows: List<GalleryPhoto>) {
        rows.forEach { photo ->
            if (photo.localUri.isBlank()) return@forEach
            val folder = File(root, "FOTO/${safe(photo.phase.ifBlank { "DURANTE" })}").apply { mkdirs() }
            val ext = photo.fileName.substringAfterLast('.', "").ifBlank { extensionForMime(photo.mimeType) }
            val base = photo.fileName.substringBeforeLast('.', photo.fileName).ifBlank { "foto" }
            val target = File(folder, "${safe(base)}_${safe(photo.ge360Id)}.${safeExtension(ext)}")
            copySource(context, photo.localUri, target, photo.updatedAt)
        }
    }

    private fun writeMediaIndex(
        root: File,
        documents: List<ScannerDocument>,
        archiveFiles: List<JobsiteArchiveFile>,
        photos: List<GalleryPhoto>,
        syncedAt: Long
    ) {
        val index = JSONObject().apply {
            put("schema", "ge360.jobsite-media-index.v1")
            put("syncedAt", syncedAt)
            put("scannerDocuments", JSONArray().apply {
                documents.forEach { d ->
                    put(JSONObject().apply {
                        put("ge360Id", d.ge360Id)
                        put("type", d.type)
                        put("number", d.documentNumber)
                        put("date", d.documentDate)
                        put("sourcePath", d.pdfPath)
                    })
                }
            })
            put("archiveDocuments", JSONArray().apply {
                archiveFiles.forEach { d ->
                    put(JSONObject().apply {
                        put("ge360Id", d.ge360Id)
                        put("category", d.category)
                        put("title", d.title)
                        put("sourcePath", d.filePath)
                    })
                }
            })
            put("photos", JSONArray().apply {
                photos.forEach { p ->
                    put(JSONObject().apply {
                        put("ge360Id", p.ge360Id)
                        put("phase", p.phase)
                        put("fileName", p.fileName)
                        put("sourceUri", p.localUri)
                        put("sha256", p.sha256)
                    })
                }
            })
        }
        writeAtomic(File(root, "INDICE-FILE.json"), index.toString(2))
    }

    private fun filterExport(json: String, wantedId: String): JSONObject {
        return filterExport(json, setOf(wantedId))
    }

    private fun filterExport(json: String, wantedIds: Set<String>): JSONObject {
        if (json.isBlank() || wantedIds.none { it.isNotBlank() }) return JSONObject()
        return runCatching {
            val source = JSONObject(json)
            val filtered = JSONObject()
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = filterNode(source.opt(key), wantedIds)
                if (child != null) filtered.put(key, child)
            }
            filtered
        }.getOrElse { JSONObject().put("filterError", it.message ?: "JSON non leggibile") }
    }

    private fun fingerprint(
        moduleSnapshots: Map<String, JSONObject>,
        clientSnapshot: JSONObject,
        documents: List<ScannerDocument>,
        archiveFiles: List<JobsiteArchiveFile>,
        photos: List<GalleryPhoto>
    ): String {
        val parts = mutableListOf<String>()
        moduleSnapshots.toSortedMap().forEach { (name, data) ->
            parts += "module:$name"
            parts += data.toString()
        }
        parts += "client"
        parts += clientSnapshot.toString()
        documents.sortedBy { it.ge360Id }.forEach { row ->
            parts += "scanner:${row.ge360Id}:${row.updatedAt}:${sourceStamp(row.pdfPath)}"
        }
        archiveFiles.sortedBy { it.ge360Id }.forEach { row ->
            parts += "archive:${row.ge360Id}:${row.updatedAt}:${sourceStamp(row.filePath)}"
        }
        photos.sortedBy { it.ge360Id }.forEach { row ->
            parts += "gallery:${row.ge360Id}:${row.updatedAt}:${row.sizeBytes}:${row.sha256}:${row.localUri}"
        }
        return ContentFingerprint.sha256(parts)
    }

    private fun sourceStamp(path: String): String {
        if (path.isBlank() || path.startsWith("content://", true)) return path
        val source = if (path.startsWith("file://", true)) Uri.parse(path).path?.let(::File) else File(path)
        return source?.takeIf { it.exists() && it.isFile }
            ?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
            ?: path
    }

    private fun storedFingerprint(root: File): String = runCatching {
        JSONObject(File(root, STATE_FILE).readText(Charsets.UTF_8)).optString("fingerprint")
    }.getOrDefault("")

    private fun filterNode(value: Any?, wantedIds: Set<String>): Any? = when (value) {
        is JSONObject -> {
            if (matchesId(value, wantedIds)) {
                JSONObject(value.toString())
            } else {
                val out = JSONObject()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = filterNode(value.opt(key), wantedIds)
                    if (child != null) out.put(key, child)
                }
                out.takeIf { it.length() > 0 }
            }
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) {
                filterNode(value.opt(i), wantedIds)?.let(out::put)
            }
            out.takeIf { it.length() > 0 }
        }
        else -> null
    }

    private fun matchesId(value: JSONObject, wantedIds: Set<String>): Boolean {
        val keys = arrayOf(
            "jobsiteId", "jobsite_id", "jobsiteGe360Id", "jobsite_ge360_id",
            "estimateGe360Id", "estimate_ge360_id", "childGe360Id", "parentGe360Id",
            "ge360Id", "ge360_id"
        )
        return keys.any { key -> value.has(key) && value.optString(key, "") in wantedIds }
    }

    private fun jobsiteRoot(context: Context, job: JobsiteRecord): File {
        val documentsBase = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val cantieri = File(documentsBase, ROOT).apply { mkdirs() }
        val suffix = safe(job.ge360Id.take(8).ifBlank { "NOID" })
        val desiredName = "CANTIERE-${safe(job.code.ifBlank { "SENZA-CODICE" })}_$suffix"
        val existing = cantieri.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith("_$suffix") }
        if (existing != null) {
            if (existing.name != desiredName) {
                val desired = File(cantieri, desiredName)
                if (!desired.exists() && existing.renameTo(desired)) return desired
            }
            return existing
        }
        return File(cantieri, desiredName).apply { mkdirs() }
    }

    private fun copySource(context: Context, sourceText: String, target: File, sourceUpdatedAt: Long) {
        when {
            sourceText.startsWith("content://", true) || sourceText.startsWith("file://", true) -> {
                val uri = Uri.parse(sourceText)
                val length = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull()?.takeIf { it >= 0 }
                val sameLength = length == null || target.length() == length
                if (target.exists() && target.length() > 0 && sameLength && sourceUpdatedAt > 0 && target.lastModified() >= sourceUpdatedAt) return
                context.contentResolver.openInputStream(uri)?.use { input ->
                    copyInput(input, target)
                } ?: error("Sorgente non leggibile: $sourceText")
            }
            else -> {
                val source = File(sourceText)
                if (source.exists() && source.isFile) copyFile(source, target, sourceUpdatedAt)
            }
        }
    }

    private fun copyFile(source: File, target: File, sourceUpdatedAt: Long = source.lastModified()) {
        if (source.absolutePath == target.absolutePath) return
        val requiredTimestamp = maxOf(source.lastModified(), sourceUpdatedAt)
        if (target.exists() && target.length() == source.length() && target.length() > 0 && target.lastModified() >= requiredTimestamp) return
        FileInputStream(source).use { input -> copyInput(input, target) }
    }

    private fun copyInput(input: InputStream, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(tmp, false).use { output -> input.copyTo(output, 64 * 1024) }
        if (target.exists() && !target.delete()) error("Impossibile aggiornare ${target.absolutePath}")
        if (!tmp.renameTo(target)) {
            FileInputStream(tmp).use { src -> FileOutputStream(target, false).use { dst -> src.copyTo(dst, 64 * 1024) } }
            tmp.delete()
        }
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) error("Impossibile aggiornare ${target.absolutePath}")
        if (!tmp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun appendJobError(context: Context, job: JobsiteRecord, error: Throwable) {
        runCatching {
            val root = jobsiteRoot(context, job)
            appendError(File(root, "LOG/sync-errors.log"), error)
        }
    }

    private fun appendGlobalError(context: Context, error: Throwable) {
        runCatching {
            val documentsBase = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            appendError(File(documentsBase, "GE360/LOG/jobsite-sync-errors.log"), error)
        }
    }

    private fun appendError(file: File, error: Throwable) {
        file.parentFile?.mkdirs()
        file.appendText("[${formatTime(System.currentTimeMillis())}] ${error::class.java.simpleName}: ${error.message.orEmpty()}\n", Charsets.UTF_8)
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(Date(timestamp))

    private fun safe(value: String): String = value
        .replace(Regex("[^a-zA-Z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(90)
        .ifBlank { "dato" }

    private fun safeExtension(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
        .take(8)
        .ifBlank { "bin" }

    private fun extensionForMime(mime: String): String = when {
        mime.contains("jpeg", true) || mime.contains("jpg", true) -> "jpg"
        mime.contains("png", true) -> "png"
        mime.contains("webp", true) -> "webp"
        mime.contains("heic", true) || mime.contains("heif", true) -> "heic"
        mime.contains("pdf", true) -> "pdf"
        else -> "bin"
    }
}
