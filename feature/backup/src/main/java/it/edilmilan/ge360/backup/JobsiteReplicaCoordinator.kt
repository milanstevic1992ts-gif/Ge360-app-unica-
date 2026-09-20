package it.edilmilan.ge360.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import it.edilmilan.ge360.core.backup.BackupArchive
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replica la cartella viva dei cantieri su due destinazioni indipendenti:
 * 1) cartella persistente scelta sul telefono;
 * 2) cartella Google Drive scelta dall'utente.
 *
 * La sorgente operativa resta la cartella app Documents/GE360/Cantieri materializzata da
 * JobsitePersistenceCoordinator. La replica e' non distruttiva: aggiorna/crea i file ma non
 * cancella automaticamente file gia' presenti nelle destinazioni.
 *
 * La stessa gerarchia e gli stessi nomi stabili sono pensati per il futuro mirror su GE360
 * Backend Linux: GE360/Cantieri/<cartella-cantiere>/...
 */
object JobsiteReplicaCoordinator {
    private const val TAG = "GE360_JOB_REPLICA"
    private const val SOURCE_RELATIVE = "GE360/Cantieri"
    private const val PREFS = "ge360_jobsite_replica"
    private const val DEBOUNCE_MS = 3_500L
    private const val CLOCK_TOLERANCE_MS = 2_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ge360-jobsite-replica").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val rerun = AtomicBoolean(false)
    private var installed = false
    private var appContext: Context? = null

    private val delayedSync = Runnable { appContext?.let(::submitSync) }

    data class ReplicaSummary(
        val localConfigured: Boolean,
        val driveConfigured: Boolean,
        val localCopied: Int,
        val driveCopied: Int,
        val localSkipped: Int,
        val driveSkipped: Int,
        val errors: List<String>
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    @Synchronized
    fun install(application: Application) {
        if (installed) return
        installed = true
        appContext = application.applicationContext
        // Un controllo iniziale recupera eventuali repliche interrotte. In seguito il
        // coordinatore di persistenza richiama la replica solo dopo modifiche reali.
        requestSync(application.applicationContext)
    }

    fun requestSync(context: Context) {
        appContext = context.applicationContext
        mainHandler.removeCallbacks(delayedSync)
        mainHandler.postDelayed(delayedSync, DEBOUNCE_MS)
    }

    fun lastStatus(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last_status", "").orEmpty()

    fun lastSuccessAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("last_success_at", 0L)

    private fun submitSync(context: Context) {
        if (!running.compareAndSet(false, true)) {
            rerun.set(true)
            return
        }
        executor.execute {
            try {
                val summary = syncNow(context.applicationContext)
                persistStatus(context, summary)
            } catch (error: Throwable) {
                Log.e(TAG, "Replica cartelle cantiere fallita", error)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("last_attempt_at", System.currentTimeMillis())
                    .putString("last_status", "Errore replica cantieri: ${error.message ?: error.javaClass.simpleName}")
                    .apply()
            } finally {
                running.set(false)
                if (rerun.getAndSet(false)) requestSync(context)
            }
        }
    }

    private fun syncNow(context: Context): ReplicaSummary {
        val sourceRoot = sourceRoot(context)
        val archive = BackupArchive(context)
        val localConfigured = archive.persistentLocalReady()
        val driveConfigured = archive.driveReady()
        val errors = mutableListOf<String>()

        if (!sourceRoot.exists() || !sourceRoot.isDirectory) {
            return ReplicaSummary(localConfigured, driveConfigured, 0, 0, 0, 0, emptyList())
        }

        writeSyncManifests(sourceRoot, localConfigured, driveConfigured)

        var localCopied = 0
        var localSkipped = 0
        var driveCopied = 0
        var driveSkipped = 0

        if (localConfigured) {
            runCatching {
                val result = mirrorToTree(context, sourceRoot, requireNotNull(archive.persistentLocalRoot()), "telefono")
                localCopied = result.first
                localSkipped = result.second
            }.onFailure { errors += "Telefono: ${it.message ?: it.javaClass.simpleName}" }
        }

        if (driveConfigured) {
            runCatching {
                val result = mirrorToTree(context, sourceRoot, requireNotNull(archive.driveRoot()), "Drive")
                driveCopied = result.first
                driveSkipped = result.second
            }.onFailure { errors += "Drive: ${it.message ?: it.javaClass.simpleName}" }
        }

        return ReplicaSummary(
            localConfigured = localConfigured,
            driveConfigured = driveConfigured,
            localCopied = localCopied,
            driveCopied = driveCopied,
            localSkipped = localSkipped,
            driveSkipped = driveSkipped,
            errors = errors.distinct()
        )
    }

    private fun sourceRoot(context: Context): File {
        val externalBase = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val external = externalBase?.let { File(it, SOURCE_RELATIVE) }
        if (external != null && external.exists()) return external
        return File(context.filesDir, SOURCE_RELATIVE)
    }

    private fun writeSyncManifests(root: File, localConfigured: Boolean, driveConfigured: Boolean) {
        val now = System.currentTimeMillis()
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("CANTIERE-") }
            .orEmpty()
            .forEach { jobFolder ->
                val manifest = JSONObject()
                    .put("schema", "ge360.jobsite-replica.v1")
                    .put("folder", jobFolder.name)
                    .put("relativePath", "GE360/Cantieri/${jobFolder.name}")
                    .put("generatedAt", now)
                    .put("androidOperationalCopy", true)
                    .put("persistentLocalReplicaConfigured", localConfigured)
                    .put("googleDriveReplicaConfigured", driveConfigured)
                    .put("replicationPolicy", "non-destructive")
                    .put("linux", JSONObject()
                        .put("ready", true)
                        .put("transport", "GE360 Data Hub / future file-vault sync")
                        .put("relativePath", "GE360/Cantieri/${jobFolder.name}")
                    )
                writeAtomic(File(jobFolder, "SYNC-MANIFEST.json"), manifest.toString(2))
            }
    }

    private fun mirrorToTree(context: Context, sourceRoot: File, rootUri: Uri, label: String): Pair<Int, Int> {
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Destinazione $label non accessibile")
        require(root.exists() && root.isDirectory && root.canWrite()) { "Destinazione $label non scrivibile" }

        val ge360 = directory(root, "GE360")
        val cantieri = directory(ge360, "Cantieri")
        return mirrorDirectory(context, sourceRoot, cantieri)
    }

    private fun mirrorDirectory(context: Context, source: File, target: DocumentFile): Pair<Int, Int> {
        var copied = 0
        var skipped = 0

        source.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty().forEach { child ->
            if (child.name.endsWith(".tmp", ignoreCase = true)) return@forEach
            if (child.isDirectory) {
                val childTarget = directory(target, child.name)
                val nested = mirrorDirectory(context, child, childTarget)
                copied += nested.first
                skipped += nested.second
            } else if (child.isFile) {
                val outcome = copyFileIfNeeded(context, child, target)
                if (outcome) copied++ else skipped++
            }
        }
        return copied to skipped
    }

    private fun copyFileIfNeeded(context: Context, source: File, parent: DocumentFile): Boolean {
        var target = parent.findFile(source.name)
        if (target != null && target.isDirectory) {
            // Non distruggiamo cartelle esistenti per un conflitto di nome.
            error("Conflitto destinazione: ${source.name} e' una cartella")
        }

        if (target != null && !needsCopy(source, target)) return false

        if (target == null) {
            target = parent.createFile(mimeFor(source), source.name)
                ?: error("Impossibile creare ${source.name}")
        }

        context.contentResolver.openOutputStream(target.uri, "w").use { output ->
            requireNotNull(output) { "Destinazione non scrivibile: ${source.name}" }
            FileInputStream(source).use { input -> input.copyTo(output, 64 * 1024) }
        }
        return true
    }

    private fun needsCopy(source: File, target: DocumentFile): Boolean {
        if (!target.exists() || !target.isFile) return true
        if (target.length() != source.length()) return true
        val remoteModified = target.lastModified()
        if (remoteModified <= 0L) return true
        return source.lastModified() > remoteModified + CLOCK_TOLERANCE_MS
    }

    private fun directory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            require(existing.isDirectory) { "$name esiste ma non e' una cartella" }
            return existing
        }
        return parent.createDirectory(name) ?: error("Impossibile creare cartella $name")
    }

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "json" -> "application/json"
        "txt", "log", "md" -> "text/plain"
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Impossibile aggiornare ${target.name}")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun persistStatus(context: Context, summary: ReplicaSummary) {
        val now = System.currentTimeMillis()
        val message = buildString {
            append("Cartelle cantiere: ")
            append(if (summary.localConfigured) "telefono ${summary.localCopied} aggiornati" else "telefono non configurato")
            append(" · ")
            append(if (summary.driveConfigured) "Drive ${summary.driveCopied} aggiornati" else "Drive non configurato")
            if (summary.errors.isNotEmpty()) append(" · errori: ${summary.errors.joinToString("; ")}")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_attempt_at", now)
            .putString("last_status", message)
            .apply {
                if (summary.ok && (summary.localConfigured || summary.driveConfigured)) {
                    putLong("last_success_at", now)
                }
            }
            .apply()
        if (summary.errors.isNotEmpty()) Log.w(TAG, message) else Log.i(TAG, message)
    }
}
