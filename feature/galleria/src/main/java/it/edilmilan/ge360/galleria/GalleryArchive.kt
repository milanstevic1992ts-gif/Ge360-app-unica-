package it.edilmilan.ge360.galleria

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import it.edilmilan.ge360.cantieri.JobsiteRecord
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryArchive(private val context: Context) {
    private val prefs = context.getSharedPreferences("ge360_gallery_archive", Context.MODE_PRIVATE)

    data class StoredFile(
        val uri: String,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String
    )

    fun localRoot(): Uri? = prefs.getString("local_root", null)?.let(Uri::parse)
    fun driveRoot(): Uri? = prefs.getString("drive_root", null)?.let(Uri::parse)

    fun setLocalRoot(uri: Uri) = remember("local_root", uri)
    fun setDriveRoot(uri: Uri) = remember("drive_root", uri)

    private fun remember(key: String, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs.edit().putString(key, uri.toString()).apply()
    }

    fun localReady() = writable(localRoot())
    fun driveReady() = writable(driveRoot())

    private fun writable(uri: Uri?): Boolean {
        if (uri == null) return false
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
            root != null && root.exists() && root.isDirectory && root.canWrite()
        }.getOrDefault(false)
    }

    fun readable(uriText: String): Boolean {
        if (uriText.isBlank()) return false
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriText)).use { it != null }
        }.getOrDefault(false)
    }

    fun copyToLocal(source: Uri, jobsite: JobsiteRecord, phase: String): StoredFile {
        val root = localRoot() ?: error("Archivio locale non configurato")
        val mime = context.contentResolver.getType(source) ?: "image/jpeg"
        val input = context.contentResolver.openInputStream(source) ?: error("Sorgente non leggibile")
        return input.use { copyStream(it, root, jobsite, phase, null, mime) }
    }

    fun copyStreamToLocal(
        input: InputStream,
        jobsite: JobsiteRecord,
        phase: String,
        requestedName: String,
        mimeType: String
    ): StoredFile {
        val root = localRoot() ?: error("Archivio locale non configurato")
        return copyStream(input, root, jobsite, phase, requestedName, mimeType)
    }

    fun copyToDrive(photo: GalleryPhoto, jobsite: JobsiteRecord): StoredFile {
        val root = driveRoot() ?: error("Drive non configurato")
        require(photo.localUri.isNotBlank()) { "Foto locale non disponibile" }
        val source = Uri.parse(photo.localUri)
        val mime = photo.mimeType.ifBlank { context.contentResolver.getType(source) ?: "image/jpeg" }
        val input = context.contentResolver.openInputStream(source) ?: error("Foto locale non leggibile")
        return input.use { copyStream(it, root, jobsite, photo.phase, photo.fileName, mime) }
    }

    private fun copyStream(
        input: InputStream,
        rootUri: Uri,
        jobsite: JobsiteRecord,
        phase: String,
        requestedName: String?,
        mimeType: String
    ): StoredFile {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cartella non accessibile")
        val gallery = directory(root, "GALLERIA CANTIERI")
        val jobFolder = directory(gallery, clean(jobsite.code) + "_" + clean(jobsite.displayTitle()))
        val phaseFolder = directory(jobFolder, normalizePhase(phase))
        val mime = mimeType.ifBlank { "image/jpeg" }
        val extension = requestedName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.let { ".${it.lowercase(Locale.ROOT)}" }
            ?: when {
                mime.contains("png", true) -> ".png"
                mime.contains("webp", true) -> ".webp"
                mime.contains("heic", true) || mime.contains("heif", true) -> ".heic"
                mime.contains("pdf", true) -> ".pdf"
                else -> ".jpg"
            }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ITALY).format(Date())
        val base = requestedName?.substringBeforeLast('.')?.let(::clean)?.takeIf { it.isNotBlank() } ?: "foto"
        val targetName = stamp + "_" + base + extension
        val target = phaseFolder.createFile(mime, targetName) ?: error("Impossibile creare il file")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openOutputStream(target.uri, "w").use { output ->
            requireNotNull(output) { "Destinazione non scrivibile" }
            val buffer = ByteArray(65536)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                size += n
            }
        }
        return StoredFile(
            target.uri.toString(),
            target.name ?: targetName,
            mime,
            size,
            digest.digest().joinToString("") { "%02x".format(it) }
        )
    }

    fun deleteLocal(uri: String): Boolean {
        if (uri.isBlank()) return true
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true
        }.getOrDefault(false)
    }

    private fun directory(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: error("Impossibile creare cartella")

    private fun normalizePhase(value: String): String = when (value.uppercase(Locale.ROOT)) {
        "PRIMA" -> GalleryPhases.BEFORE
        "DOPO" -> GalleryPhases.AFTER
        "PROBLEMI", "PROBLEMA" -> GalleryPhases.PROBLEMS
        "DOCUMENTI", "DOCUMENTO" -> GalleryPhases.DOCUMENTS
        else -> GalleryPhases.DURING
    }

    private fun clean(value: String): String = value
        .replace(Regex("[^a-zA-Z0-9 _.-]"), "")
        .trim()
        .replace(Regex(" +"), "-")
        .take(80)
        .ifBlank { "CANTIERE" }
}