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
import java.util.UUID
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

    private fun isGe360Archive(file: File) = file.isFile &&
        (file.name.endsWith(".ge360.zip") || file.name.endsWith(".ge360.enc"))

    fun latestLocal(): File? = localBackups().firstOrNull()

    fun localBackups(): List<File> = backupDir().listFiles()?.filter(::isGe360Archive)
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun encryptionEnabled(): Boolean = BackupPassphraseStoreV8(context).isEnabled()

    fun enableEncryption(password: CharArray) =
        BackupPassphraseStoreV8(context).enable(password)

    /**
     * Restore scheduled-backup access after Android Keystore loss.
     * Do not trust a typed password by itself: first authenticate and inspect
     * an independently chosen encrypted GE360 archive from the document picker.
     * No local key/prefs mutation occurs until all checks have passed.
     */
    fun recoverLocalKeyFromUri(uri: Uri, password: CharArray) {
        require(encryptionEnabled()) { "La cifratura dei nuovi backup non è attiva" }
        val proof = File(context.cacheDir,
            "ge360-recovery-source-" + UUID.randomUUID().toString() + ".tmp")
        val clear = File(context.cacheDir,
            "ge360-recovery-check-" + UUID.randomUUID().toString() + ".ge360.zip")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Backup di prova non leggibile" }
                FileOutputStream(proof).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            require(EncryptedBackupV8.isEncrypted(proof)) {
                "Per riparare la chiave serve un backup GE360 cifrato"
            }
            EncryptedBackupV8.decrypt(proof, clear, password)
            inspect(clear, verifyHashes = true)
            val schema = readManifest(clear).optString("schema")
            require(schema == "ge360.backup.v1") { "Backup di prova GE360 non valido" }
            BackupPassphraseStoreV8(context).rewrapAfterVerifiedArchive(password)
        } finally {
            clear.delete()
            proof.delete()
        }
    }



    /**
     * With V8 enabled the ZIP is built in PRIVATE cache only, checked using
     * the existing V7 semantic preflight, and published as encrypted .ge360.enc.
     * A missing Keystore secret is fatal: do not silently create a plain backup.
     * Legacy unencrypted backup creation is preserved until the user opts in.
     */
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

        val credential = BackupPassphraseStoreV8(context)
        val secured = credential.isEnabled()
        val secret = if (secured) credential.readForBackup() else null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        val name = prefix + "_" + stamp + "_" + UUID.randomUUID().toString().take(8)
        val target = File(backupDir(), name + if (secured) ".ge360.enc" else ".ge360.zip")
        val stage = if (secured) File(context.cacheDir,
            "ge360-v8-build-" + UUID.randomUUID().toString() + ".zip")
            else File(backupDir(), target.name + ".partial")
        // Only published, complete archives have the .zip/.enc final suffix.
        // Crash remnants with .partial are ignored by latestLocal/rotation.
        val encryptedStage = if (secured) File(backupDir(), target.name + ".partial") else null
        try {
            check(stage.createNewFile()) { "Impossibile preparare il file di backup" }
            val hashes = JSONObject()
            var count = 0
            ZipOutputStream(BufferedOutputStream(FileOutputStream(stage))).use { zip ->
                entries.forEach { source ->
                    val normalized = source.path.trimStart('/').replace("..", "_")
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(normalized))
                    source.open().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
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
            val zipInfo = inspect(stage, verifyHashes = true)
            validateBeforePublish?.invoke(stage)
            val info = if (secured) {
                val encrypted = requireNotNull(encryptedStage)
                EncryptedBackupV8.encrypt(stage, encrypted, requireNotNull(secret))
                check(EncryptedBackupV8.isEncrypted(encrypted)) { "Archivio cifrato non valido" }
                check(encrypted.renameTo(target)) {
                    "Impossibile pubblicare il backup cifrato completo"
                }
                BackupFileInfo(target, sha256(FileInputStream(target)), target.length(), zipInfo.entries)
            } else {
                check(stage.renameTo(target)) { "Impossibile pubblicare il backup ZIP completo" }
                BackupFileInfo(target, zipInfo.sha256, zipInfo.sizeBytes, zipInfo.entries)
            }
            rotate(10)
            prefs.edit()
                .putLong("last_backup_at", System.currentTimeMillis())
                .putString("last_backup_sha256", info.sha256)
                .apply()
            return info
        } catch (error: Throwable) {
            // Also remove plain intermediate data on I/O or Keystore errors.
            target.delete()
            throw error
        } finally {
            // Cleanup is best-effort; cold-start V9 also removes recognized
            // abandoned scratch from process death/power loss.
            stage.delete()
            encryptedStage?.delete()
            secret?.fill('\u0000')
        }
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
        // Once protection is enabled never push an old cleartext ZIP to
        // persistent/Drive storage accidentally through a manual copy action.
        if (encryptionEnabled()) require(file.name.endsWith(".ge360.enc")) {
            "Questo ZIP storico non è cifrato. Crea un nuovo backup V8 prima di copiarlo."
        }
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: error("Cartella backup non accessibile")
        val folder = root.findFile("GE360 BACKUP")?.takeIf { it.isDirectory }
            ?: root.createDirectory("GE360 BACKUP") ?: error("Impossibile creare GE360 BACKUP")
        require(folder.findFile(file.name) == null) {
            "Backup già esistente: non sovrascrivere una copia precedente"
        }
        val mime = if (file.name.endsWith(".ge360.enc"))
            "application/octet-stream" else "application/zip"
        val target = folder.createFile(mime, file.name) ?: error("Impossibile creare backup")
        try {
            context.contentResolver.openOutputStream(target.uri, "w").use { output ->
                requireNotNull(output) { "Destinazione backup non scrivibile" }
                FileInputStream(file).use { input -> input.copyTo(output, 64 * 1024) }
            }
            // A successful SAF write does not prove the provider saved the
            // whole ciphertext: re-open and compare the complete SHA-256.
            val sourceSha = sha256(FileInputStream(file))
            val remoteSha = context.contentResolver.openInputStream(target.uri).use {
                requireNotNull(it) { "Copia remota non rileggibile" }
                sha256(it)
            }
            check(sourceSha == remoteSha) { "Copia di backup incompleta sulla destinazione" }
        } catch (error: Throwable) {
            target.delete() // Do not retain an incomplete remote ciphertext/ZIP.
            throw error
        }
        rotateSaf(folder, 10)
        return target.uri
    }

    private fun rotateSaf(folder: DocumentFile, keep: Int) {
        folder.listFiles()
            .filter { it.isFile && (
                it.name?.endsWith(".ge360.zip") == true ||
                    it.name?.endsWith(".ge360.enc") == true
            ) }
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