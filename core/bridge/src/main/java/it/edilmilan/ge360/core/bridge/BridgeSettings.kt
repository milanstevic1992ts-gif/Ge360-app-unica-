package it.edilmilan.ge360.core.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BridgeService(val key: String) { MARKETING("marketing"), JARVIS("jarvis") }

data class BridgeServiceConfig(
    val baseUrl: String = "",
    val token: String = "",
    val allowInsecureHttp: Boolean = false
) {
    val configured: Boolean get() = baseUrl.isNotBlank()
}

class SecureBridgeSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(service: BridgeService): BridgeServiceConfig {
        val storedUrl = prefs.getString("${service.key}_url", "").orEmpty()
        val baseUrl = if (service == BridgeService.JARVIS) {
            if (storedUrl.isBlank()) DEFAULT_GE360_SERVER_URL
            else runCatching { normalizeServerUrl(storedUrl) }.getOrDefault(DEFAULT_GE360_SERVER_URL)
        } else storedUrl
        return BridgeServiceConfig(
            baseUrl = baseUrl,
            token = decrypt(prefs.getString("${service.key}_token", "").orEmpty()),
            allowInsecureHttp = prefs.getBoolean("${service.key}_allow_http", false) &&
                baseUrl.startsWith("http://", ignoreCase = true)
        )
    }

    fun save(service: BridgeService, baseUrl: String, token: String, allowInsecureHttp: Boolean) {
        val normalized = if (service == BridgeService.JARVIS) normalizeServerUrl(baseUrl) else normalizeBaseUrl(baseUrl)
        prefs.edit()
            .putString("${service.key}_url", normalized)
            .putString("${service.key}_token", encrypt(token.trim()))
            .putBoolean("${service.key}_allow_http", allowInsecureHttp && normalized.startsWith("http://", true))
            .apply()
    }

    fun clear(service: BridgeService) {
        prefs.edit()
            .remove("${service.key}_url")
            .remove("${service.key}_token")
            .remove("${service.key}_allow_http")
            .apply()
    }

    fun exportPublicJson(): String = JSONObject().apply {
        put("schema", "ge360.bridge-settings.v1")
        BridgeService.entries.forEach { service ->
            val c = get(service)
            put(service.key, JSONObject()
                .put("baseUrl", c.baseUrl)
                .put("allowInsecureHttp", c.allowInsecureHttp)
                .put("tokenIncluded", false))
        }
    }.toString(2)

    fun importPublicJson(json: String) {
        val root = JSONObject(json)
        if (root.optString("schema") != "ge360.bridge-settings.v1") return
        BridgeService.entries.forEach { service ->
            val source = root.optJSONObject(service.key) ?: return@forEach
            val current = get(service)
            save(
                service,
                source.optString("baseUrl", current.baseUrl),
                current.token,
                source.optBoolean("allowInsecureHttp", current.allowInsecureHttp)
            )
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        val uri = URI(trimmed)
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Usa un URL http:// o https://" }
        require(!uri.host.isNullOrBlank()) { "URL del servizio non valido" }
        return trimmed
    }

    private fun normalizeServerUrl(value: String): String {
        var candidate = value.trim()
        if (candidate.isBlank()) return DEFAULT_GE360_SERVER_URL
        candidate = EMBEDDED_HTTP_URL.find(candidate)?.value ?: candidate
        candidate = candidate.trim().trim('"', '\'').trimEnd('/', '.', ',', ';', ')', ']')
        if (!HAS_SCHEME.containsMatchIn(candidate)) candidate = "https://$candidate"
        return normalizeBaseUrl(candidate)
    }

    private fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        return runCatching {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            require(payload.size > IV_BYTES)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val encrypted = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_GE360_SERVER_URL = "https://ge360-server.taile742de.ts.net:8443"

        private val EMBEDDED_HTTP_URL = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        private val HAS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private const val PREFS = "ge360_bridge_settings"
        private const val KEY_ALIAS = "ge360_bridge_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
