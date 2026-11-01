package it.edilmilan.ge360.preventivi

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Private immutable evidence copies. The user selects a real PDF/JPEG/PNG via SAF;
 * we do NOT retain a fragile external content URI or trust a supplied file path.
 */
object MetricAgreementFilesV5 {
    const val MAX_BYTES: Long = 12L * 1024 * 1024
    private fun extension(mime: String): String = when (mime) {
        "application/pdf" -> "pdf"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> error("Formato non consentito: PDF, JPG, PNG")
    }

    fun fileFor(context: Context, record: MetricAgreementAttachmentV5): File {
        require(record.valid())
        val id = UUID.fromString(record.ge360Id).toString()
        return File(context.filesDir, "metric-agreements/$id.${extension(record.mimeType)}")
    }

    fun existsAndMatches(context: Context, record: MetricAgreementAttachmentV5): Boolean =
        runCatching {
            val f = fileFor(context, record)
            f.isFile && f.length() == record.sizeBytes &&
                sha256(f) == record.sha256 && correctHeader(f, record.mimeType)
        }.getOrDefault(false)

    fun contentUri(context: Context, record: MetricAgreementAttachmentV5): Uri {
        require(existsAndMatches(context, record)) { "Allegato mancante o alterato" }
        return FileProvider.getUriForFile(
            context, context.packageName + ".preventivi.evidence", fileFor(context, record)
        )
    }

    fun copyFromPicker(
        context: Context, source: Uri, estimateId: String,
        economicsId: String, correctionId: String = ""
    ): MetricAgreementAttachmentV5 {
        require(source.scheme == "content") { "Selezionare un documento dall'archivio Android" }
        val resolver = context.contentResolver
        val declared = resolver.getType(source)?.lowercase() ?: ""
        val mime = when (declared) {
            "application/pdf", "image/jpeg", "image/png" -> declared
            else -> error("Allegato non consentito: selezionare PDF, JPG o PNG")
        }
        val original = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null).use { c ->
            if (c != null && c.moveToFirst()) c.getString(0) else null
        }?.replace(Regex("[\\\\/\u0000-\u001f]"), "_")?.take(120)
            ?.ifBlank { "documento.${extension(mime)}" }
            ?: "documento.${extension(mime)}"
        val id = UUID.randomUUID().toString()
        val provisional = MetricAgreementAttachmentV5(
            ge360Id = id, estimateGe360Id = estimateId,
            economicsGe360Id = economicsId, correctionGe360Id = correctionId,
            fileName = original, mimeType = mime, sizeBytes = 1,
            sha256 = "0".repeat(64)
        )
        val target = fileFor(context, provisional)
        require(target.parentFile?.mkdirs() == true ||
            target.parentFile?.isDirectory == true) { "Cartella documenti non disponibile" }
        val temp = File(target.parentFile, "$id.partial")
        var count = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Documento non leggibile" }
                FileOutputStream(temp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val bytes = input.read(buf)
                        if (bytes < 0) break
                        if (bytes == 0) continue
                        count += bytes
                        require(count <= MAX_BYTES) { "Documento troppo grande (max 12 MB)" }
                        digest.update(buf, 0, bytes)
                        output.write(buf, 0, bytes)
                    }
                    output.fd.sync()
                }
            }
            require(count > 0 && correctHeader(temp, mime)) {
                "Il formato reale del file non corrisponde a PDF/JPG/PNG"
            }
            check(!target.exists() && temp.renameTo(target)) {
                "Impossibile salvare la copia privata del documento"
            }
            val record = provisional.copy(
                sizeBytes = count,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            )
            require(record.valid() && existsAndMatches(context, record)) {
                "Copia allegato non verificabile"
            }
            return record
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally { temp.delete() }
    }

    private fun correctHeader(file: File, mime: String): Boolean {
        val prefix = ByteArray(8)
        val count = FileInputStream(file).use { it.read(prefix) }
        if (count < 4) return false
        return when (mime) {
            "application/pdf" -> prefix.take(5).toByteArray().contentEquals(
                "%PDF-".toByteArray())
            "image/jpeg" -> (prefix[0].toInt() and 255) == 255 &&
                (prefix[1].toInt() and 255) == 216 &&
                (prefix[2].toInt() and 255) == 255
            "image/png" -> prefix.contentEquals(
                byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            else -> false
        }
    }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) hash.update(buf, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}

class MetricAgreementFileProvider : FileProvider()
