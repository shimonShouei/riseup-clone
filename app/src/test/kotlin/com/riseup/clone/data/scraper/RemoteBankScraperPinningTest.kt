package com.riseup.clone.data.scraper

import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.FailureReason
import com.riseup.clone.domain.scraper.ScrapeResult
import com.riseup.clone.domain.scraper.ScraperCredentials
import java.time.LocalDate
import kotlin.test.assertEquals
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Ignore
import org.junit.Test

/**
 * Verifies the **fail-closed on cert-pin mismatch** behaviour (SECURITY.md R13):
 * a client whose `CertificatePinner` pin does not match the server's SPKI must
 * reject the TLS handshake (`SSLPeerUnverifiedException`) with no fallback to
 * system trust, and [RemoteBankScraper] must map that to
 * [FailureReason.NETWORK].
 *
 * Ignored on this build machine only: exercising a real TLS handshake headlessly
 * relies on a JSSE/conscrypt TLS stack that is not reliably available on this
 * Windows/ARM64 box (the same limitation that blocks Robolectric here). The test
 * itself is correct and runs on x86_64 CI or a device — remove [Ignore] there.
 * The rest of [RemoteBankScraper]'s logic (request build, status mapping, parse,
 * header, no-logging) is fully covered headlessly by [RemoteBankScraperTest].
 */
@Ignore("Real TLS handshake not reliably available on Windows/ARM64 (as with Robolectric/conscrypt); run on x86_64 CI or a device")
class RemoteBankScraperPinningTest {

    private val credentials = ScraperCredentials("id", "pw", mapOf("num" to "n"))
    private val range = DateRange(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 7, 1))

    @Test
    fun `pin mismatch fails closed and maps to NETWORK`() {
        val serverCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverHandshake = HandshakeCertificates.Builder().heldCertificate(serverCert).build()

        val server = MockWebServer()
        server.useHttps(serverHandshake.sslSocketFactory(), false)
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accounts":[],"transactions":[]}"""))
        server.start()

        // Client trusts the server cert (so trust is NOT the failure) but pins a
        // DELIBERATELY WRONG SPKI — the pin check must reject the connection.
        val clientHandshake =
            HandshakeCertificates.Builder().addTrustedCertificate(serverCert.certificate).build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientHandshake.sslSocketFactory(), clientHandshake.trustManager)
            .certificatePinner(
                CertificatePinner.Builder().add(server.hostName, WRONG_PIN).build(),
            )
            .build()

        val scraper = RemoteBankScraper(
            institution = RemoteBankScraper.DISCOUNT_INSTITUTION,
            endpoint = server.url("/scrape"),
            client = client,
            tokenProvider = { "token" },
            companyId = RemoteBankScraper.DISCOUNT_COMPANY_ID,
        )

        val failure = scraper.scrape(credentials, range) as ScrapeResult.Failure
        assertEquals(FailureReason.NETWORK, failure.reason)

        server.shutdown()
    }

    private companion object {
        /** Valid-format SPKI pin that will not match any real key. */
        const val WRONG_PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
