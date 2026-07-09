package com.riseup.clone.data.security

import com.riseup.clone.domain.scraper.ScraperCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Turns a [ScraperCredentials] into a byte blob and back, so the store has a
 * single well-defined thing to encrypt (rather than encrypting three fields
 * separately). Deliberately dependency-free — a length-prefixed [DataOutputStream]
 * format rather than JSON — so it carries no serialization library and, crucially,
 * runs and is fully unit-testable on the plain JVM (see [CredentialCodecTest]),
 * independent of any Android/Keystore runtime.
 *
 * The output is *plaintext* and must only ever exist transiently in memory
 * between (de)serialization and (de)cryption — it is never written to disk in
 * this form.
 */
internal object CredentialCodec {

    /** Serialize [credentials] to a self-describing, length-prefixed blob. */
    fun encode(credentials: ScraperCredentials): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(credentials.username)
            out.writeUTF(credentials.password)
            out.writeInt(credentials.extra.size)
            for ((key, value) in credentials.extra) {
                out.writeUTF(key)
                out.writeUTF(value)
            }
        }
        return bytes.toByteArray()
    }

    /** Inverse of [encode]. Preserves [ScraperCredentials.extra] insertion order. */
    fun decode(blob: ByteArray): ScraperCredentials {
        DataInputStream(ByteArrayInputStream(blob)).use { input ->
            val username = input.readUTF()
            val password = input.readUTF()
            val extraSize = input.readInt()
            val extra = LinkedHashMap<String, String>(extraSize)
            repeat(extraSize) {
                val key = input.readUTF()
                extra[key] = input.readUTF()
            }
            return ScraperCredentials(username, password, extra)
        }
    }
}
