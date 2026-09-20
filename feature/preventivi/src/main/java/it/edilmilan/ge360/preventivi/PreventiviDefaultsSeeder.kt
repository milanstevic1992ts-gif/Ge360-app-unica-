package it.edilmilan.ge360.preventivi

import android.content.Context
import org.json.JSONObject

/**
 * Ponte tra l'uso reale dell'app e lo standard versionato nella repository.
 *
 * Il file assets/preventivi_customizations.default.json può essere sostituito
 * in futuro con un backup esportato dall'app. Quando exportedAt/bundleVersion
 * aumenta, il contenuto viene unito una sola volta alle personalizzazioni del
 * dispositivo senza cancellare ciò che l'utente ha aggiunto nel frattempo.
 */
object PreventiviDefaultsSeeder {
    private const val ASSET = "preventivi_customizations.default.json"
    private const val PREFS = "ge360_preventivi_defaults_seed"
    private const val KEY_VERSION = "last_bundle_version"

    fun ensure(context: Context) {
        runCatching {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            if (root.optString("schema") != PreventiviCustomizationStore.BACKUP_SCHEMA) return
            val version = root.optLong("bundleVersion", root.optLong("exportedAt", 1L)).coerceAtLeast(1L)
            val seedPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (seedPrefs.getLong(KEY_VERSION, 0L) >= version) return
            PreventiviCustomizationStore(context).importReusableJson(json, replace = false)
            seedPrefs.edit().putLong(KEY_VERSION, version).apply()
        }
    }
}
