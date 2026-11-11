package it.edilmilan.ge360.preventivi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Archivia più sopralluoghi offline, senza toccare il DB dei preventivi. */
class SmartSurveyLocalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ge360_smart_surveys_v1", Context.MODE_PRIVATE)

    fun get(id: String): SmartSurveySessionV1? {
        if (id.isBlank()) return null
        return prefs.getString("s/$id", null)?.let { raw ->
            runCatching { SmartSurveySessionV1.fromJson(raw) }.getOrNull()
        }
    }

    fun list(): List<SmartSurveySessionV1> = prefs.all.keys.asSequence()
        .filter { it.startsWith("s/") }
        .mapNotNull { get(it.removePrefix("s/")) }
        .sortedByDescending { it.updatedAt }.toList()

    fun save(draft: SmartSurveySessionV1) {
        draft.updatedAt = System.currentTimeMillis()
        check(prefs.edit().putString("s/${draft.id}", draft.toJson().toString()).commit()) {
            "Impossibile salvare il sopralluogo sul dispositivo"
        }
    }

    /** Versione portabile completa della parte testuale dei sopralluoghi. */
    fun exportJson(): String = JSONObject().apply {
        put("schema", BACKUP_SCHEMA)
        // Non perdere silenziosamente una sessione malformata: un backup
        // dichiarato completo deve fallire anziché omettere dati dell'utente.
        val rawSessions = prefs.all.filterKeys { it.startsWith("s/") }
        val parsed = rawSessions.map { (key, value) ->
            val session = SmartSurveySessionV1.fromJson(value as? String
                ?: error("Sopralluogo non leggibile: " + key))
            require("s/" + session.id == key) { "ID sopralluogo non coerente: " + key }
            session
        }
        put("sessions", JSONArray().apply { parsed.forEach { put(it.toJson()) } })
    }.toString()

    /**
     * Merge per UUID senza duplicati. In modalità Unisci si preserva sempre
     * la sessione locale già presente; Sostituisci cambia solo questo archivio.
     * Il chiamante deve prima verificare i byte delle foto col manifest ZIP.
     */
    fun importJson(json: String, replaceAll: Boolean): Int {
        val root = JSONObject(json)
        require(root.optString("schema") == BACKUP_SCHEMA) {
            "Formato sopralluoghi Smart non supportato"
        }
        val incoming = root.getJSONArray("sessions")
        require(incoming.length() <= 5000) { "Troppi sopralluoghi nel backup" }
        val unique = linkedMapOf<String, SmartSurveySessionV1>()
        for (i in 0 until incoming.length()) {
            val data = incoming.getJSONObject(i)
            val session = SmartSurveySessionV1.fromJson(data.toString())
            require(UUID.fromString(session.id).toString() == session.id) { "UUID sopralluogo non valido" }
            require(session.photoFiles.distinct().size == session.photoFiles.size) {
                "Fotografie duplicate nel sopralluogo"
            }
            require(unique.putIfAbsent(session.id, session) == null) {
                "Sessione duplicata nel backup: " + session.id
            }
        }
        val previous = if (replaceAll) emptyMap() else list().associateBy { it.id }
        val edit = prefs.edit()
        if (replaceAll) prefs.all.keys.filter { it.startsWith("s/") }.forEach(edit::remove)
        var inserted = 0
        unique.values.forEach { session ->
            if (session.id !in previous) {
                edit.putString("s/" + session.id, session.toJson().toString())
                inserted++
            }
        }
        check(edit.commit()) { "Impossibile ripristinare l'archivio sopralluoghi Smart" }
        return inserted
    }

    companion object {
        const val BACKUP_SCHEMA = "ge360.preventivi.smart-sopralluoghi.v1"
        const val PHOTO_FOLDER = "smart_sopralluoghi"
        fun validPhotoName(value: String): Boolean {
            if (!value.endsWith(".img")) return false
            val id = value.removeSuffix(".img")
            return runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        }
    }

    fun delete(id: String) {
        check(prefs.edit().remove("s/$id").commit()) {
            "Impossibile eliminare la copia locale del sopralluogo"
        }
    }
}
