package com.riseup.clone.data.security

import com.riseup.clone.domain.scraper.ScraperCredentials
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Pure-JVM checks on the credential (de)serialization. No Android runtime is
 * involved, so these always run in CI without a device.
 */
class CredentialCodecTest {

    @Test
    fun `credentials survive an encode-decode round trip`() {
        val original = ScraperCredentials(
            username = "user@example.com",
            password = "s3cr3t-p@ss word",
            extra = linkedMapOf("nationalId" to "123456789", "card" to "4242"),
        )

        val restored = CredentialCodec.decode(CredentialCodec.encode(original))

        assertEquals(original, restored)
    }

    @Test
    fun `empty extra map round-trips`() {
        val original = ScraperCredentials(username = "u", password = "p")

        val restored = CredentialCodec.decode(CredentialCodec.encode(original))

        assertEquals(original, restored)
        assertEquals(emptyMap(), restored.extra)
    }

    @Test
    fun `unicode and empty strings are preserved`() {
        val original = ScraperCredentials(
            username = "משתמש",
            password = "",
            extra = mapOf("emoji" to "🔐", "blank" to ""),
        )

        val restored = CredentialCodec.decode(CredentialCodec.encode(original))

        assertEquals(original, restored)
    }
}
