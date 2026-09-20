package it.edilmilan.ge360.core.bridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TrainerDeviceConfig(
    val deviceId: String,
    val trainerToken: String = ""
) {
    val paired: Boolean get() = deviceId.isNotBlank() && trainerToken.isNotBlank()
}

/**
 * Identita' privacy-preserving del telefono usata dal Training Bridge.
 *
 * L'ANDROID_ID non viene mai inviato al backend: viene trasformato in SHA-256 insieme al
 * package name e a un namespace GE360. Il risultato resta compatibile con il formato
 * device_id accettato dal backend.
 */
object TrainerDeviceIdentity {
    private const val LEGACY_BROKEN_ANDROID_ID = "9774d56d682e549c"
    private val TOKEN_PATTERN = Regex("^trn_[A-Za-z0-9_-]{28,}$")

    fun derive(packageName: String, androidId: String): String? {
        val clean = androidId.trim()
        if (clean.isBlank() || clean.equals(LEGACY_BROKEN_ANDROID_ID, ignoreCase = true)) return null
        val source = "$packageName|$clean|ge360-trainer-v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "android-${hex.take(40)}"
    }

    fun fallback(): String = "android-fallback-${UUID.randomUUID()}"

    fun validTrainerToken(value: String): Boolean = TOKEN_PATTERN.matches(value.trim())
}

/**
 * Archivio separato per la credenziale Trainer.
 *
 * - device_id persistente ma non segreto;
 * - token cifrato AES/GCM con una chiave dedicata dell'Android Keystore;
 * - nessuna esportazione della credenziale;
 * - clearTrainerToken() non cambia l'identita' del dispositivo.
 *
 * L'app principale disabilita Android Backup, quindi anche il ciphertext resta fuori dai backup.
 */
class SecureTrainerDeviceSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): TrainerDeviceConfig = TrainerDeviceConfig(
        deviceId = deviceId(),
        trainerToken = decrypt(prefs.getString(KEY_TOKEN, "").orEmpty())
    )

    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, "").orEmpty().trim().takeIf { it.isNotBlank() }?.let { return it }

        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        }.getOrDefault("")
        val generated = TrainerDeviceIdentity.derive(appContext.packageName, androidId)
            ?: TrainerDeviceIdentity.fallback()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android GE360" }
        .take(128)

    fun hasTrainerToken(): Boolean = get().trainerToken.isNotBlank()

    fun saveTrainerToken(value: String) {
        val clean = value.trim()
        require(TrainerDeviceIdentity.validTrainerToken(clean)) {
            "Credenziale Trainer non valida"
        }
        prefs.edit().putString(KEY_TOKEN, encrypt(clean)).apply()
    }

    fun clearTrainerToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun encrypt(value: String): String {
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
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "ge360_trainer_device_v1"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "trainer_token"
        private const val KEY_ALIAS = "ge360_trainer_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
