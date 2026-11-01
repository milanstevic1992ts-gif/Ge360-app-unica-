package it.edilmilan.ge360.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * V8 contracts only. Intentionally not executed until explicit QA authorization.
 * No Android device, network, GitHub workflow or private data is involved.
 */
class EncryptedBackupV8Test {
    @Test fun authenticatedRoundTripAndWrongPasswordFailsClosed() {
        val dir = Files.createTempDirectory("ge360-crypto-contract").toFile()
        try {
            val clear = dir.resolve("input.ge360.zip")
            val encrypted = dir.resolve("protected.ge360.enc")
            val restored = dir.resolve("restored.ge360.zip")
            val invalid = dir.resolve("should-not-exist.zip")
            val bytes = "synthetic GE360 ZIP payload for cryptographic contract"
                .toByteArray(Charsets.UTF_8)
            clear.writeBytes(bytes)
            val password = "test-only-recovery-passphrase-123".toCharArray()
            try {
                EncryptedBackupV8.encrypt(clear, encrypted, password)
                assertTrue(EncryptedBackupV8.isEncrypted(encrypted))
                assertFalse(EncryptedBackupV8.isEncrypted(clear))
                EncryptedBackupV8.decrypt(encrypted, restored, password)
                assertArrayEquals(bytes, restored.readBytes())
                assertTrue(runCatching {
                    EncryptedBackupV8.decrypt(encrypted, invalid, "wrong-passphrase".toCharArray())
                }.isFailure)
                assertFalse(invalid.exists())
            } finally {
                password.fill('\u0000')
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun tamperOrHeaderChangeRejectsAndDoesNotPublishPlaintext() {
        val dir = Files.createTempDirectory("ge360-crypto-integrity").toFile()
        try {
            val source = dir.resolve("source.zip").apply { writeText("synthetic") }
            val encrypted = dir.resolve("original.ge360.enc")
            val password = "test-only-another-recovery-password".toCharArray()
            try {
                EncryptedBackupV8.encrypt(source, encrypted, password)
                val payload = encrypted.readBytes()
                payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
                val changed = dir.resolve("tampered.ge360.enc").apply { writeBytes(payload) }
                val output = dir.resolve("output.zip")
                assertTrue(runCatching {
                    EncryptedBackupV8.decrypt(changed, output, password)
                }.isFailure)
                assertFalse(output.exists())
                val changedHeader = encrypted.readBytes()
                changedHeader[8] = (changedHeader[8].toInt() xor 1).toByte()
                val badHeader = dir.resolve("header.ge360.enc").apply { writeBytes(changedHeader) }
                assertTrue(runCatching {
                    EncryptedBackupV8.decrypt(badHeader, output, password)
                }.isFailure)
                assertFalse(output.exists())
            } finally { password.fill('\u0000') }
        } finally { dir.deleteRecursively() }
    }

    @Test fun encryptionUsesFreshSaltAndNonceForEveryArchive() {
        val dir = Files.createTempDirectory("ge360-crypto-randomness").toFile()
        try {
            val source = dir.resolve("source.zip").apply { writeText("same synthetic content") }
            val a = dir.resolve("one.ge360.enc")
            val b = dir.resolve("two.ge360.enc")
            val password = "test-only-random-nonce-password".toCharArray()
            try {
                EncryptedBackupV8.encrypt(source, a, password)
                EncryptedBackupV8.encrypt(source, b, password)
                assertFalse(a.readBytes().contentEquals(b.readBytes()))
            } finally { password.fill('\u0000') }
        } finally { dir.deleteRecursively() }
    }
}
