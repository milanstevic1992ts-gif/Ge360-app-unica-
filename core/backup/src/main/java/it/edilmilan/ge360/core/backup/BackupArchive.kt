package it.edilmilan.ge360.core.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class BackupEntrySource(
    val path: String,
    val open: () -> InputStream
)

data class BackupFileInfo(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
    val entries: Int
)

class BackupArchive(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun backupDir(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, "GE360/Backups").apply { mkdirs() }
    }

    fun latestLocal(): File? = backupDir().listFiles { f -> f.isFile && f.name.endsWith(".ge360.zip") }
        ?.maxByOrNull { it.lastModified() }

    fun localBackups(): List<File> = backupDir().listFiles { f -> f.isFile && f.name.endsWith(".ge360.zip") }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun create(
        entries: List<BackupEntrySource>,
        manifest: JSONObject,
        prefix: String = "GE360",
        validateBeforePublish: ((File) -> Unit)? = null
    ): BackupFileInfo {
        val runtimeVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        if (!runtimeVersion.isNullOrBlank()) manifest.put("appVersion", runtimeVersion)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        val target = File(backupDir(), "${prefix}_$stamp.ge360.zip")
        val hashes = JSONObject()
        var count = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            entries.forEach { source ->
                val normalized = source.path.trimStart('/').replace("..", "_")
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(normalized))
                source.open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        zip.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                }
                zip.closeEntry()
                hashes.put(normalized, digest.digest().hex())
                count++
            }
            manifest.put("entrySha256", hashes)
            val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifestBytes)
            zip.closeEntry()
            count++
        }
        // Run feature-level semantic checks before rotation/last_backup_at.
        // An archive may have internally correct ZIP hashes yet contain
        // documents that disagree with the app's authoritative metadata.
        val info = try {
            inspect(target, verifyHashes = true).also {
                validateBeforePublish?.invoke(target)
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        rotate(10)
        prefs.edit().putLong("last_backup_at", System.currentTimeMillis()).putString("last_backup_sha256", info.sha256).apply()
        return info
    }

    fun inspect(file: File, verifyHashes: Boolean): BackupFileInfo {
        require(file.exists() && file.isFile) { "Backup non trovato" }
        val whole = sha256(FileInputStream(file))
        var entries = 0
        ZipFile(file).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST) ?: error("manifest.json mancante")
            val manifest = zip.getInputStream(manifestEntry).bufferedReader().use { JSONObject(it.readText()) }
            require(manifest.optString("schema").startsWith("ge360.backup.")) { "Formato backup GE360 non riconosciuto" }
            val expected = manifest.optJSONObject("entrySha256") ?: JSONObject()
            val expectedNames = mutableSetOf<String>()
            val expectedKeys = expected.keys()
            while (expectedKeys.hasNext()) expectedNames += expectedKeys.next()
            val actualNames = mutableSetOf<String>()

            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                entries++
                if (entry.name == MANIFEST) continue

                require(actualNames.add(entry.name)) { "Voce duplicata nel backup: ${entry.name}" }
                if (verifyHashes) {
                    require(expected.has(entry.name)) { "Checksum mancante nel manifest: ${entry.name}" }
                    val actual = sha256(zip.getInputStream(entry))
                    require(actual.equals(expected.getString(entry.name), true)) { "Checksum non valido: ${entry.name}" }
                }
            }

            if (verifyHashes) {
                val missing = expectedNames - actualNames
                require(missing.isEmpty()) { "Voci dichiarate nel manifest ma mancanti: ${missing.joinToString(", ")}" }
                val undeclared = actualNames - expectedNames
                require(undeclared.isEmpty()) { "Voci non dichiarate nel manifest: ${undeclared.joinToString(", ")}" }
            }
        }
        return BackupFileInfo(file, whole, file.length(), entries)
    }

    fun readManifest(file: File): JSONObject = ZipFile(file).use { zip ->
        val e = zip.getEntry(MANIFEST) ?: error("manifest.json mancante")
        zip.getInputStream(e).bufferedReader().use { JSONObject(it.readText()) }
    }

    fun extractEntry(file: File, entryName: String, target: File): File {
        target.parentFile?.mkdirs()
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName) ?: error("Voce backup mancante: $entryName")
            zip.getInputStream(entry).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        }
        return target
    }

    fun readText(file: File, entryName: String): String = ZipFile(file).use { zip ->
        val e = zip.getEntry(entryName) ?: error("Voce backup mancante: $entryName")
        zip.getInputStream(e).bufferedReader().use { it.readText() }
    }

    fun openEntry(file: File, entryName: String): InputStream {
        val zip = ZipFile(file)
        val e = zip.getEntry(entryName) ?: run { zip.close(); error("Voce backup mancante: $entryName") }
        val raw = zip.getInputStream(e)
        return object : InputStream() {
            override fun read(): Int = raw.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = raw.read(b, off, len)
            override fun close() { raw.close(); zip.close() }
        }
    }

    fun rotate(keep: Int = 10) {
        localBackups().drop(keep.coerceAtLeast(1)).forEach { runCatching { it.delete() } }
    }

    fun persistentLocalRoot(): Uri? = prefs.getString("persistent_local_root", null)?.let(Uri::parse)
    fun driveRoot(): Uri? = prefs.getString("drive_root", null)?.let(Uri::parse)

    fun setPersistentLocalRoot(uri: Uri) = rememberRoot("persistent_local_root", uri)
    fun setDriveRoot(uri: Uri) = rememberRoot("drive_root", uri)

    private fun rememberRoot(key: String, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        prefs.edit().putString(key, uri.toString()).apply()
    }

    fun persistentLocalReady(): Boolean = treeReady(persistentLocalRoot())
    fun driveReady(): Boolean = treeReady(driveRoot())

    private fun treeReady(uri: Uri?): Boolean {
        if (uri == null) return false
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, uri)
            root != null && root.exists() && root.isDirectory && root.canWrite()
        }.getOrDefault(false)
    }

    fun copyToPersistentLocal(file: File): Uri {
        val uri = copyToSafRoot(persistentLocalRoot() ?: error("Cartella locale persistente non configurata"), file)
        prefs.edit().putLong("last_persistent_local_at", System.currentTimeMillis()).apply()
        return uri
    }

    fun copyToDrive(file: File): Uri {
        val uri = copyToSafRoot(driveRoot() ?: error("Cartella Drive backup non configurata"), file)
        prefs.edit().putLong("last_drive_backup_at", System.currentTimeMillis()).apply()
        return uri
    }

    private fun copyToSafRoot(rootUri: Uri, file: File): Uri {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cartella backup non accessibile")
        val folder = root.findFile("GE360 BACKUP")?.takeIf { it.isDirectory }
            ?: root.createDirectory("GE360 BACKUP") ?: error("Impossibile creare GE360 BACKUP")
        folder.findFile(file.name)?.delete()
        val target = folder.createFile("application/zip", file.name) ?: error("Impossibile creare backup")
        context.contentResolver.openOutputStream(target.uri, "w").use { output ->
            requireNotNull(output) { "Destinazione backup non scrivibile" }
            FileInputStream(file).use { input -> input.copyTo(output, 64 * 1024) }
        }
        rotateSaf(folder, 10)
        return target.uri
    }

    private fun rotateSaf(folder: DocumentFile, keep: Int) {
        folder.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".ge360.zip") == true || it.name?.contains("GE360_") == true) }
            .sortedByDescending { it.lastModified() }
            .drop(keep.coerceAtLeast(1))
            .forEach { runCatching { it.delete() } }
    }

    fun lastBackupAt(): Long = prefs.getLong("last_backup_at", 0L)
    fun lastPersistentLocalAt(): Long = prefs.getLong("last_persistent_local_at", 0L)
    fun lastDriveBackupAt(): Long = prefs.getLong("last_drive_backup_at", 0L)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(input).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val MANIFEST = "manifest.json"
        private const val PREFS = "ge360_shared_backup"
    }
}