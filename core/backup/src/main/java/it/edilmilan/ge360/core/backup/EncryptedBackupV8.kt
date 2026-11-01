package it.edilmilan.ge360.core.backup

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

/**
 * Versioned, authenticated GE360 backup wrapper. The payload is the *existing*
 * GE360 ZIP; no database or JSON schema changes are needed.
 *
 * Format (40 byte authenticated header):
 * ASCII GE360ENC | PBKDF2 iterations (big-endian Int) | salt 16 | nonce 12
 * Followed by AES-256-GCM ciphertext and its 128-bit authentication tag.
 *
 * A passphrase kept outside the phone is REQUIRED to recover on a new device.
 * Neither SHA-256 nor AES-GCM proves who authored the underlying business data.
 */
object EncryptedBackupV8 {
    private val magic = "GE360ENC".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + NONCE_BYTES
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun isEncrypted(file: File): Boolean = runCatching {
        DataInputStream(FileInputStream(file)).use { input ->
            val prefix = ByteArray(magic.size)
            input.readFully(prefix)
            Arrays.equals(prefix, magic)
        }
    }.getOrDefault(false)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        require(password.isNotEmpty()) { "Password di recupero mancante" }
        require(iterations in 100_000..1_000_000) { "Parametri di cifratura non supportati" }
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            SecretKeySpec(bytes, "AES")
        } finally {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    fun encrypt(zip: File, target: File, password: CharArray) {
        require(zip.isFile && zip != target)
        require(!target.exists()) { "Backup cifrato già presente" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(magic).putInt(ITERATIONS).put(salt).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        try {
            FileOutputStream(target).use { raw ->
                raw.write(header)
                CipherOutputStream(raw, cipher).use { encrypted ->
                    FileInputStream(zip).use { source -> source.copyTo(encrypted, 64 * 1024) }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun decrypt(source: File, target: File, password: CharArray) {
        require(source.isFile && source != target)
        require(!target.exists()) { "File temporaneo di recupero già presente" }
        try {
            DataInputStream(FileInputStream(source)).use { raw ->
                val header = ByteArray(HEADER_BYTES)
                raw.readFully(header)
                val parsed = DataInputStream(ByteArrayInputStream(header))
                val actualMagic = ByteArray(magic.size)
                parsed.readFully(actualMagic)
                require(Arrays.equals(actualMagic, magic)) { "Non è un backup GE360 cifrato" }
                val iterations = parsed.readInt()
                val salt = ByteArray(SALT_BYTES)
                val nonce = ByteArray(NONCE_BYTES)
                parsed.readFully(salt)
                parsed.readFully(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations),
                    GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(header)
                CipherInputStream(raw, cipher).use { decrypted ->
                    FileOutputStream(target).use { output ->
                        decrypted.copyTo(output, 64 * 1024)
                        output.fd.sync()
                    }
                }
            }
        } catch (error: Throwable) {
            target.delete() // Never publish plaintext before GCM tag verification.
            throw IllegalArgumentException(
                "Password non corretta, backup alterato o formato cifrato non valido", error
            )
        }
    }
}
