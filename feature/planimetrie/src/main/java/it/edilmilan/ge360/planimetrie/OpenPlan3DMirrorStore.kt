package it.edilmilan.ge360.planimetrie

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Copia nativa verificabile dei progetti OpenPlan3D e delle foto WebView.
 * La fonte operativa rimane localStorage / IndexedDB; qui arrivano i dati
 * tramite il solo WebMessage bridge limitato all'origin appassets HTTPS.
 *
 * NESSUNA credenziale di backend (apiKey, URL di pairing) viene duplicata.
 * Un upload interrotto rende il backup inammissibile, non "completo".
 */
class OpenPlan3DMirrorStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "ge360-openplan3d-mirror")
    private val libraryFile = File(root, "library.json")
    private val dirtyFile = File(root, "library.dirty")
    private val readyFile = File(root, "library.ready")
    private val pendingRestoreFile = File(root, "restore.pending.json")
    private val photosDir = File(root, "photos")
    private val libraryStage = File(root, "library.part")
    private var incomingPhoto: PhotoUpload? = null
    private var incomingLibraryIndex = 0

    data class Photo(val id: String, val file: File)
    data class RestoreStatus(val pending: Boolean, val mode: String, val photos: List<String>)

    private data class PhotoUpload(
        val id: String, val size: Long, val sha256: String,
        val stage: File, var written: Long = 0L, var next: Int = 0
    )

    init {
        check(root.isDirectory || root.mkdirs()) { "Impossibile creare archivio OpenPlan3D" }
        check(photosDir.isDirectory || photosDir.mkdirs()) { "Impossibile creare cartella foto OpenPlan3D" }
    }

    @Synchronized fun markDirty() {
        check(dirtyFile.exists() || dirtyFile.createNewFile()) {
            "Impossibile segnalare un aggiornamento OpenPlan3D pendente"
        }
    }

    /** Mark a partial library transaction before writing ANY payload. */
    @Synchronized fun beginLibrary() {
        check(dirtyFile.exists() || dirtyFile.createNewFile()) { "Impossibile segnalare salvataggio pendente" }
        if (libraryStage.exists()) libraryStage.delete()
        check(libraryStage.createNewFile()) { "Impossibile preparare lo snapshot" }
        incomingLibraryIndex = 0
    }

    @Synchronized fun libraryChunk(index: Int, base64: String) {
        require(dirtyFile.isFile && libraryStage.isFile && index == incomingLibraryIndex) {
            "Chunk libreria fuori ordine"
        }
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES && libraryStage.length() + bytes.size <= MAX_LIBRARY_BYTES)
        FileOutputStream(libraryStage, true).use {
            it.write(bytes)
            it.fd.sync()
        }
        incomingLibraryIndex++
    }

    @Synchronized fun endLibrary(sha256: String) {
        require(dirtyFile.isFile && libraryStage.isFile && incomingLibraryIndex > 0)
        require(sha256.matches(Regex("[a-f0-9]{64}")) && digest(libraryStage) == sha256) {
            "SHA-256 OpenPlan3D libreria non corrispondente"
        }
        val raw = libraryStage.readText(Charsets.UTF_8)
        validateLibrary(raw)
        atomicReplace(libraryStage, libraryFile)
        check(readyFile.exists() || readyFile.createNewFile())
        check(dirtyFile.delete()) { "Impossibile chiudere aggiornamento libreria" }
    }

    @Synchronized fun startPhoto(id: String, size: Long, sha256: String) {
        require(validId(id))
        require(size > 0L && size <= MAX_PHOTO_BYTES && sha256.matches(Regex("[a-f0-9]{64}")))
        check(incomingPhoto == null) { "Upload foto già in corso" }
        val stage = File(photosDir, ".$id.partial")
        if (stage.exists()) stage.delete()
        check(stage.createNewFile())
        incomingPhoto = PhotoUpload(id, size, sha256, stage)
    }

    @Synchronized fun photoChunk(id: String, index: Int, base64: String) {
        val upload = requireNotNull(incomingPhoto) { "Foto senza sessione di upload" }
        require(upload.id == id && upload.next == index) { "Chunk foto fuori ordine" }
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES && upload.written + bytes.size <= upload.size)
        FileOutputStream(upload.stage, true).use {
            it.write(bytes)
            it.fd.sync()
        }
        upload.written += bytes.size
        upload.next++
    }

    @Synchronized fun endPhoto(id: String) {
        val upload = requireNotNull(incomingPhoto)
        require(upload.id == id && upload.written == upload.size && digest(upload.stage) == upload.sha256) {
            "Foto incompleta o checksum errato"
        }
        val target = photoFile(id)
        atomicReplace(upload.stage, target)
        incomingPhoto = null
    }

    @Synchronized fun photoIds(): List<String> =
        photosDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }
            .map { it.name.removeSuffix(".bin") }.filter(::validId).sorted()

    /**
     * If Android WebView storage was cleared but the native verified copy
     * remains, restore instead of silently treating the empty JS library as
     * authoritative. A dirty snapshot is never auto-restored.
     */
    fun recoverIfWebViewEmpty(planCount: Int) {
        if (planCount != 0 || pendingRestoreFile.exists() || dirtyFile.exists() || !hasSnapshot()) return
        val existing = runCatching {
            JSONObject(libraryFile.readText()).getJSONArray("plans").length()
        }.getOrDefault(0)
        if (existing <= 0) return
        val tmp = File(root, "restore.pending.part")
        tmp.writeText(JSONObject().put("mode", "merge").toString())
        atomicReplace(tmp, pendingRestoreFile)
    }

    fun hasSnapshot(): Boolean = readyFile.isFile && libraryFile.isFile
    fun pendingStatus(): RestoreStatus {
        if (!pendingRestoreFile.isFile) return RestoreStatus(false, "", emptyList())
        val j = JSONObject(pendingRestoreFile.readText())
        val meta = validateLibrary(libraryFile.readText())
        return RestoreStatus(true, j.getString("mode"), photoIdsFor(meta))
    }

    fun readLibrary(): String {
        check(hasSnapshot()) { "OpenPlan3D non sincronizzato: apri Rilievi avanzati almeno una volta" }
        check(!dirtyFile.exists()) { "Salvataggio OpenPlan3D non completato: riapri Rilievi avanzati" }
        check(!pendingRestoreFile.exists()) { "Apri OpenPlan3D per completare il ripristino pendente" }
        val raw = libraryFile.readText()
        validateLibrary(raw)
        val missing = photoIdsFor(JSONObject(raw)).filterNot { id -> photoFile(id).isFile && photoFile(id).length() > 0 }
        check(missing.isEmpty()) {
            "Foto OpenPlan3D non ancora trasferite al backup: " + missing.take(5).joinToString(", ")
        }
        return raw
    }

    fun photosForBackup(): List<Photo> {
        val ids = photoIdsFor(JSONObject(readLibrary()))
        return ids.map { id ->
            val file = photoFile(id)
            require(file.isFile && file.length() > 0) { "Foto OpenPlan3D mancante: $id" }
            Photo(id, file)
        }
    }

    /** Used only for a verified incoming archive, before publishing new data. */
    fun importVerified(json: String, photos: Map<String, File>, replaceAll: Boolean) {
        val parsed = validateLibrary(json)
        val wanted = photoIdsFor(parsed).toSet()
        require(photos.keys == wanted) { "Backup OpenPlan3D: elenco foto differente dal manifest" }
        photos.forEach { (id, src) ->
            require(validId(id) && src.isFile && src.length() > 0)
        }
        photos.forEach { (id, src) ->
            val dest = photoFile(id)
            if (!replaceAll && dest.isFile && dest.length() > 0) return@forEach
            val stage = File(photosDir, ".$id.importing")
            try {
                src.inputStream().use { inp ->
                    FileOutputStream(stage).use { out -> inp.copyTo(out); out.fd.sync() }
                }
                require(stage.length() == src.length() && digest(stage) == digest(src)) {
                    "Copia foto OpenPlan3D non verificata"
                }
                atomicReplace(stage, dest)
            } finally { stage.delete() }
        }
        // La libreria importata non tocca ancora localStorage. Il prossimo
        // avvio del WebView applica Unisci/Sostituisci in IndexedDB e solo
        // dopo elimina il flag di restore. Non si dichiarano dati ripristinati
        // prima di tale conferma.
        val stage = File(root, "library.importing")
        stage.writeText(json)
        atomicReplace(stage, libraryFile)
        check(readyFile.exists() || readyFile.createNewFile())
        dirtyFile.delete()
        val flagStage = File(root, "restore.pending.part")
        flagStage.writeText(JSONObject().put("mode", if (replaceAll) "replace" else "merge").toString())
        atomicReplace(flagStage, pendingRestoreFile)
    }

    fun libraryForRestore(): String {
        require(pendingRestoreFile.isFile) { "Nessun ripristino OpenPlan3D pendente" }
        return libraryFile.readText()
    }

    fun photoChunkForRestore(id: String, offset: Long, count: Int): String {
        require(pendingRestoreFile.isFile && validId(id) && id in photoIdsFor(JSONObject(libraryForRestore())))
        val file = photoFile(id)
        require(offset >= 0L && count in 1..CHUNK_BYTES && offset + count <= file.length())
        val bytes = ByteArray(count)
        RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(bytes) }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun photoSize(id: String): Long {
        require(validId(id) && id in photoIdsFor(JSONObject(libraryForRestore())))
        return photoFile(id).length()
    }

    fun completeRestore() {
        require(pendingRestoreFile.isFile)
        check(pendingRestoreFile.delete()) { "Flag ripristino OpenPlan3D non cancellabile" }
        // JS salva successivamente la libreria riconciliata in modo normale.
    }

    private fun photoFile(id: String): File {
        require(validId(id)) { "Identificativo foto non valido" }
        return File(photosDir, "$id.bin")
    }

    private fun photoIdsFor(root: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        val plans = root.getJSONArray("plans")
        for (i in 0 until plans.length()) {
            val photos = plans.getJSONObject(i).optJSONArray("photos") ?: JSONArray()
            for (j in 0 until photos.length()) {
                val id = photos.getJSONObject(j).getString("id")
                require(validId(id) && result.add(id)) { "Foto duplicata o ID non valido" }
            }
        }
        return result.sorted()
    }

    private fun validateLibrary(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size in 2..MAX_LIBRARY_BYTES) {
            "Dimensione libreria OpenPlan3D non valida"
        }
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Schema OpenPlan3D non compatibile" }
        val plans = root.getJSONArray("plans")
        require(plans.length() <= 10000) { "Troppi rilievi OpenPlan3D" }
        val ids = mutableSetOf<String>()
        for (i in 0 until plans.length()) {
            val plan = plans.getJSONObject(i)
            require(validId(plan.getString("id")) && ids.add(plan.getString("id"))) {
                "Rilievo duplicato o ID non valido"
            }
        }
        photoIdsFor(root)
        return root
    }

    private fun atomicReplace(stage: File, dest: File) {
        Files.move(stage.toPath(), dest.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                md.update(bytes, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SCHEMA = "ge360.openplan3d.mirror.v1"
        const val BACKUP_PATH = "modules/openplan3d.json"
        const val PHOTO_PREFIX = "files/openplan3d/"
        const val CHUNK_BYTES = 128 * 1024
        const val MAX_LIBRARY_BYTES = 50 * 1024 * 1024
        const val MAX_PHOTO_BYTES = 25L * 1024 * 1024
        fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,100}"))
    }
}
