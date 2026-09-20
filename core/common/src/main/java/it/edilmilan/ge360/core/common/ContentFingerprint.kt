package it.edilmilan.ge360.core.common

import java.security.MessageDigest

/** Impronta deterministica usata per evitare materializzazioni e repliche senza modifiche. */
object ContentFingerprint {
    fun sha256(parts: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
