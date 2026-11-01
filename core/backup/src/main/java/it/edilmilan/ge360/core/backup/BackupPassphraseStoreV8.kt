package it.edilmilan.ge360.core.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores a recoverable passphrase ONLY in AES-GCM form, wrapped by Android
 * Keystore. This local copy lets scheduled jobs run while the original
 * passphrase must be saved separately by the operator for disaster recovery.
 *
 * Keystore loss/invalidation is a hard error: NEVER downgrade to plain ZIP.
 */
class BackupPassphraseStoreV8(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "ge360_encrypted_backup_v8", Context.MODE_PRIVATE
    )
    private val alias = "ge360_backup_v8_local_wrap"
    private val keystore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", false)

    fun enable(password: CharArray) {
        require(!isEnabled()) { "Cifratura già attiva: non sovrascrivere la chiave esistente" }
        require(password.size in 12..256 && password.any { !it.isWhitespace() }) {
            "Usa una password di recupero di almeno 12 caratteri"
        }
        val raw = String(password).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(raw)
            val iv = cipher.iv
            val wrapped = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, wrapped, 0, iv.size)
            System.arraycopy(encrypted, 0, wrapped, iv.size, encrypted.size)
            check(prefs.edit()
                .putString("wrapped_passphrase", Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putBoolean("enabled", true).commit()) {
                "Impossibile attivare la cifratura locale"
            }
            Arrays.fill(wrapped, 0.toByte())
            Arrays.fill(encrypted, 0.toByte())
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    fun readForBackup(): CharArray {
        require(isEnabled()) { "Cifratura non attivata" }
        val encoded = prefs.getString("wrapped_passphrase", null)
            ?: error("Chiave locale mancante. Nessun backup in chiaro verrà creato.")
        val wrapped = Base64.decode(encoded, Base64.NO_WRAP)
        try {
            require(wrapped.size > 12 + 16) { "Chiave locale corrotta" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = wrapped.copyOfRange(0, 12)
            val encrypted = wrapped.copyOfRange(12, wrapped.size)
            cipher.init(Cipher.DECRYPT_MODE, currentKey(), GCMParameterSpec(128, iv))
            val bytes = cipher.doFinal(encrypted)
            return try {
                String(bytes, Charsets.UTF_8).toCharArray()
            } finally {
                Arrays.fill(bytes, 0.toByte())
                Arrays.fill(encrypted, 0.toByte())
            }
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Chiave Android non disponibile. Recuperare con la password custodita fuori dal telefono; backup automatico bloccato.", error
            )
        } finally {
            Arrays.fill(wrapped, 0.toByte())
        }
    }

    private fun currentKey(): SecretKey = (keystore.getKey(alias, null) as? SecretKey)
        ?: error("Chiave Android Keystore non trovata")

    private fun getOrCreateKey(): SecretKey {
        if (keystore.containsAlias(alias)) return currentKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
