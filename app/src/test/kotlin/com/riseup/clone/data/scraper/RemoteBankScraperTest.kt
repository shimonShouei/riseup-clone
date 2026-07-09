package com.riseup.clone.data.scraper

import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.FailureReason
import com.riseup.clone.domain.scraper.ScrapeResult
import com.riseup.clone.domain.scraper.ScraperCredentials
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

/**
 * JVM unit tests for [RemoteBankScraper] driven against OkHttp's [MockWebServer]
 * over plain HTTP — no device, no Robolectric, no real TLS. These exercise the
 * whole request-build / status-mapping / response-parse path (everything except
 * the TLS pinning handshake itself, which needs real TLS — see
 * [RemoteBankScraperPinningTest]).
 */
class RemoteBankScraperTest {

    private lateinit var server: MockWebServer

    private val credentials = ScraperCredentials(
        username = "national-id-123",
        password = "s3cr3t-pw",
        extra = mapOf("num" to "user-code-9"),
    )
    private val range = DateRange(LocalDate.of(2025, 7, 1), LocalDate.of(2026, 7, 1))

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun scraper(
        token: () -> String = { TOKEN },
        readTimeout: Duration = Duration.ofSeconds(3),
    ): RemoteBankScraper {
        val client = OkHttpClient.Builder().readTimeout(readTimeout).build()
        return RemoteBankScraper(
            institution = RemoteBankScraper.DISCOUNT_INSTITUTION,
            endpoint = server.url("/scrape"),
            client = client,
            tokenProvider = token,
            companyId = RemoteBankScraper.DISCOUNT_COMPANY_ID,
        )
    }

    @Test
    fun `200 maps into raw account and transaction DTOs`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SUCCESS_BODY))

        val result = scraper().scrape(credentials, range)

        val success = result as ScrapeResult.Success
        assertEquals(1, success.accounts.size)
        val account = success.accounts.single()
        assertEquals("12-345-6789", account.externalId)
        assertEquals("12-345-6789", account.label)
        assertEquals("Discount Bank", account.institution)
        assertEquals("bankIssued", account.rawType)

        assertEquals(1, success.transactions.size)
        val txn = success.transactions.single()
        assertEquals("12-345-6789", txn.accountExternalId)
        assertEquals("2026-01-15T00:00:00.000Z", txn.rawDate)
        assertEquals("SUPER PHARM  TEL AVIV", txn.rawMerchant)
        assertEquals("-50.25", txn.rawAmount)
        assertNull(txn.rawDirection)
        assertEquals("Health", txn.rawCategory)
        assertEquals("987654", txn.reference)
    }

    @Test
    fun `request carries bearer token and maps credentials to backend fields`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_BODY))

        scraper().scrape(credentials, range)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/scrape", recorded.path)
        assertEquals("Bearer $TOKEN", recorded.getHeader("Authorization"))

        val body = recorded.body.readUtf8()
        // username -> id, password -> password, extra["num"] -> num, startDate = range.from.
        assertTrue(body.contains("\"companyId\":\"discount\""), body)
        assertTrue(body.contains("\"id\":\"national-id-123\""), body)
        assertTrue(body.contains("\"password\":\"s3cr3t-pw\""), body)
        assertTrue(body.contains("\"num\":\"user-code-9\""), body)
        assertTrue(body.contains("\"startDate\":\"2025-07-01\""), body)
    }

    @Test
    fun `401 maps to INVALID_CREDENTIALS`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_credentials"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.INVALID_CREDENTIALS, failure.reason)
    }

    @Test
    fun `403 account_blocked maps to INVALID_CREDENTIALS`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"account_blocked"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.INVALID_CREDENTIALS, failure.reason)
    }

    @Test
    fun `409 otp_required maps to OTP_REQUIRED with the stable otp_required message`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"otp_required"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        // Dedicated, permanent reason so the background worker never retry-loops (R16).
        assertEquals(FailureReason.OTP_REQUIRED, failure.reason)
        assertEquals("otp_required", failure.message)
        assertEquals(RemoteBankScraper.OTP_REQUIRED_MESSAGE, failure.message)
    }

    @Test
    fun `502 network maps to NETWORK`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"network"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.NETWORK, failure.reason)
    }

    @Test
    fun `504 timeout maps to NETWORK`() {
        server.enqueue(MockResponse().setResponseCode(504).setBody("""{"error":"timeout"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.NETWORK, failure.reason)
    }

    @Test
    fun `500 internal_error maps to UNKNOWN`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"internal_error"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.UNKNOWN, failure.reason)
    }

    @Test
    fun `400 maps to PARSE_ERROR`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad_request"}"""))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.PARSE_ERROR, failure.reason)
    }

    @Test
    fun `malformed 200 body maps to PARSE_ERROR`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this is not json{"))

        val failure = scraper().scrape(credentials, range) as ScrapeResult.Failure

        assertEquals(FailureReason.PARSE_ERROR, failure.reason)
    }

    @Test
    fun `a hung connection times out and maps to NETWORK`() {
        // Server accepts the socket but never replies; client read timeout fires.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val failure = scraper(readTimeout = Duration.ofMillis(300)).scrape(credentials, range)
            as ScrapeResult.Failure

        assertEquals(FailureReason.NETWORK, failure.reason)
    }

    @Test
    fun `a token read failure maps to UNKNOWN without hitting the network`() {
        val failure = scraper(token = { error("keystore unavailable") }).scrape(credentials, range)
            as ScrapeResult.Failure

        assertEquals(FailureReason.UNKNOWN, failure.reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `credentials and token are never written to stdout or stderr`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SUCCESS_BODY))

        val originalOut = System.out
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        val stream = PrintStream(captured, true, "UTF-8")
        System.setOut(stream)
        System.setErr(stream)
        try {
            scraper().scrape(credentials, range)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }

        val logged = captured.toString("UTF-8")
        assertFalse(logged.contains(credentials.password), "password leaked to logs")
        assertFalse(logged.contains(credentials.extra.getValue("num")), "num leaked to logs")
        assertFalse(logged.contains(TOKEN), "bearer token leaked to logs")
    }

    private companion object {
        const val TOKEN = "test-bearer-token"

        const val SUCCESS_BODY = """
            {
              "accounts": [
                { "externalId": "12-345-6789", "label": "12-345-6789",
                  "institution": "Discount Bank", "rawType": "bankIssued" }
              ],
              "transactions": [
                { "accountExternalId": "12-345-6789", "rawDate": "2026-01-15T00:00:00.000Z",
                  "rawMerchant": "SUPER PHARM  TEL AVIV", "rawAmount": "-50.25",
                  "rawDirection": null, "rawCategory": "Health", "reference": "987654" }
              ]
            }
        """

        const val EMPTY_BODY = """{"accounts":[],"transactions":[]}"""
    }
}
