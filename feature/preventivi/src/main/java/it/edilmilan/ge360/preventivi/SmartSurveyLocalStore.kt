package it.edilmilan.ge360.preventivi

import android.content.Context

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

    fun delete(id: String) {
        check(prefs.edit().remove("s/$id").commit()) {
            "Impossibile eliminare la copia locale del sopralluogo"
        }
    }
}
