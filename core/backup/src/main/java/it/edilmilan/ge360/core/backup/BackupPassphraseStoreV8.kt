package it.edilmilan.ge360.core.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
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
    private val legacyAlias = "ge360_backup_v8_local_wrap"
    private fun activeAlias(): String = prefs.getString("keystore_alias", null) ?: legacyAlias
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

    /**
     * Called ONLY after authentication of a user-selected encrypted backup.
     * A new Keystore alias is prepared before switching preferences, so a
     * failure cannot destroy the old wrapped secret first.
     */
    fun rewrapAfterVerifiedArchive(password: CharArray) {
        require(isEnabled()) { "Attivare prima la cifratura V8" }
        require(password.size in 12..256 && password.any { !it.isWhitespace() }) {
            "Password di recupero non valida"
        }
        val previousAlias = activeAlias()
        val newAlias = "ge360_backup_v9_wrap_" + UUID.randomUUID().toString()
        val raw = String(password).toByteArray(Charsets.UTF_8)
        var committed = false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, generateKey(newAlias))
            val iv = cipher.iv
            check(iv.size == 12) { "Nonce Android Keystore non valido" }
            val encrypted = cipher.doFinal(raw)
            val wrapped = iv + encrypted
            try {
                check(prefs.edit()
                    .putString("keystore_alias", newAlias)
                    .putString("wrapped_passphrase", Base64.encodeToString(wrapped, Base64.NO_WRAP))
                    .putBoolean("enabled", true)
                    .commit()) { "Ripristino chiave locale non confermato" }
                committed = true
            } finally {
                wrapped.fill(0.toByte())
                encrypted.fill(0.toByte())
            }
        } finally {
            raw.fill(0.toByte())
            if (!committed) runCatching { keystore.deleteEntry(newAlias) }
        }
        if (previousAlias != newAlias) runCatching { keystore.deleteEntry(previousAlias) }
    }

    private fun currentKey(): SecretKey = (keystore.getKey(activeAlias(), null) as? SecretKey)
        ?: error("Chiave Android Keystore non trovata")

    private fun getOrCreateKey(): SecretKey {
        val alias = activeAlias()
        if (keystore.containsAlias(alias)) return currentKey()
        return generateKey(alias)
    }

    private fun generateKey(name: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
