package it.edilmilan.ge360.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale

/**
 * Archivio fisico degli scontrini legati agli attrezzi.
 *
 * I metadati canonici restano in [DocumentStore], mentre i PDF vengono conservati in una
 * cartella leggibile e riconoscibile separata dai normali documenti Scanner.
 */
object ToolWarrantyArchive {
    const val DOCUMENT_TYPE = "SCONTRINO_GARANZIA"
    const val ROOT_FOLDER = "GE360/Scontrini garanzia"

    fun isWarrantyReceipt(document: ScannerDocument): Boolean =
        document.type.equals(DOCUMENT_TYPE, ignoreCase = true)

    fun root(context: Context): File {
        val documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(documents, ROOT_FOLDER).apply { mkdirs() }
    }

    fun createPendingPdf(context: Context, source: Uri): File {
        val folder = File(context.cacheDir, "tool-warranty-scans").apply { mkdirs() }
        val target = File(folder, "pending-${System.currentTimeMillis()}.pdf")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Scontrino non leggibile" }
            FileOutputStream(target, false).use { output -> input.copyTo(output, 64 * 1024) }
        }
        require(target.length() > 0L) { "La scansione dello scontrino è vuota" }
        return target
    }

    /**
     * Sposta/copia il PDF nella cartella definitiva. Il file sorgente viene eliminato solo
     * dopo che la nuova copia è stata scritta e verificata.
     */
    fun commitPdf(
        context: Context,
        sourcePath: String,
        purchaseDate: String,
        toolName: String,
        ge360Id: String
    ): File {
        val source = File(sourcePath)
        require(source.exists() && source.isFile && source.length() > 0L) { "PDF dello scontrino non trovato" }
        val target = targetFile(context, purchaseDate, toolName, ge360Id)
        if (source.absolutePath == target.absolutePath) return target

        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        FileInputStream(source).use { input ->
            FileOutputStream(temporary, false).use { output -> input.copyTo(output, 64 * 1024) }
        }
        require(temporary.length() == source.length() && temporary.length() > 0L) {
            temporary.delete()
            "Copia dello scontrino incompleta"
        }
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Impossibile aggiornare ${target.name}")
        }
        if (!temporary.renameTo(target)) {
            FileInputStream(temporary).use { input ->
                FileOutputStream(target, false).use { output -> input.copyTo(output, 64 * 1024) }
            }
            temporary.delete()
        }
        require(target.exists() && target.length() == source.length()) { "Salvataggio dello scontrino non riuscito" }
        source.delete()
        return target
    }

    fun targetFile(
        context: Context,
        purchaseDate: String,
        toolName: String,
        ge360Id: String
    ): File {
        val normalizedDate = normalizedDate(purchaseDate)
        val year = normalizedDate.substringBefore('-')
            .takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: LocalDate.now().year.toString()
        return File(
            File(root(context), year).apply { mkdirs() },
            fileName(normalizedDate, toolName, ge360Id)
        )
    }

    fun fileName(purchaseDate: String, toolName: String, ge360Id: String): String {
        val normalizedDate = normalizedDate(purchaseDate)
        val shortId = safe(ge360Id).take(8).ifBlank { "NOID" }
        return "${normalizedDate}_${safe(toolName).ifBlank { "attrezzo" }}_${shortId}.pdf"
    }

    /**
     * Indice consultabile anche fuori dall'app: nome/tipo attrezzo e data d'acquisto non
     * rimangono quindi soltanto nel database SQLite.
     */
    fun writeIndex(context: Context, rows: List<ScannerDocument>) {
        val receipts = rows.filter(::isWarrantyReceipt)
            .sortedWith(compareByDescending<ScannerDocument> { it.documentDate }.thenByDescending { it.createdAt })
        val json = JSONObject()
            .put("schema", "ge360.tool-warranty-receipts.v1")
            .put("updatedAt", System.currentTimeMillis())
            .put("folder", ROOT_FOLDER)
            .put("receipts", JSONArray().apply {
                receipts.forEach { document ->
                    put(JSONObject().apply {
                        put("ge360Id", document.ge360Id)
                        put("toolName", document.toolName)
                        put("toolType", document.toolType)
                        put("purchaseDate", document.documentDate)
                        put("supplier", document.supplier)
                        if (document.totalAmount != null) put("totalAmount", document.totalAmount)
                        put("pdfFile", File(document.pdfPath).name)
                        put("createdAt", document.createdAt)
                        put("updatedAt", document.updatedAt)
                    })
                }
            })
        writeAtomic(File(root(context), "indice-scontrini-garanzia.json"), json.toString(2))

        val readable = buildString {
            appendLine("GE360 - SCONTRINI GARANZIA ATTREZZI")
            appendLine("Aggiornato: ${LocalDate.now()}")
            appendLine("Scontrini: ${receipts.size}")
            appendLine()
            receipts.forEach { document ->
                append(document.documentDate.ifBlank { "data non indicata" })
                append(" | ")
                append(document.toolName.ifBlank { "attrezzo non indicato" })
                append(" | ")
                append(document.toolType.ifBlank { "tipo non indicato" })
                if (document.supplier.isNotBlank()) append(" | ").append(document.supplier)
                append(" | ").append(File(document.pdfPath).name)
                appendLine()
            }
        }
        writeAtomic(File(root(context), "indice-scontrini-garanzia.txt"), readable)
    }

    fun normalizedDate(value: String): String =
        runCatching { LocalDate.parse(value.trim()).toString() }.getOrElse { LocalDate.now().toString() }

    fun safe(value: String): String = value
        .lowercase(Locale.ITALY)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(70)

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            error("Impossibile aggiornare ${target.name}")
        }
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }
}
