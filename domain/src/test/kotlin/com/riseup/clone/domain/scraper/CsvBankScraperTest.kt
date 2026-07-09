package com.riseup.clone.domain.scraper

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the CSV provider against captured sample statements in
 * `src/test/resources`, then runs its raw output through the mapper to prove the
 * whole seam produces correct domain data.
 */
class CsvBankScraperTest {

    private val account = ScrapedAccount(
        externalId = "leumi-checking-001",
        label = "Cheshbon Over Vashav",
        institution = "Bank Leumi",
        rawType = "Checking",
    )

    private val credentials = ScraperCredentials(username = "user", password = "secret")

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource: $name" }
            .bufferedReader().use { it.readText() }

    private fun scraper(resourceName: String) =
        CsvBankScraper("Bank Leumi", account) { resource(resourceName) }

    private fun success(result: ScrapeResult): ScrapeResult.Success {
        assertTrue("expected Success, got $result", result is ScrapeResult.Success)
        return result as ScrapeResult.Success
    }

    private val wholeRange = DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    @Test
    fun `parses all rows and returns the configured account`() {
        val result = success(scraper("leumi_statement_sample.csv").scrape(credentials, wholeRange))
        assertEquals(listOf(account), result.accounts)
        assertEquals(7, result.transactions.size)
    }

    @Test
    fun `debit and credit columns become the raw direction`() {
        val txs = success(scraper("leumi_statement_sample.csv").scrape(credentials, wholeRange)).transactions
        val salary = txs.first { it.rawMerchant.contains("MASKORET") }
        val rent = txs.first { it.rawMerchant.contains("SECHAR DIRA") }
        assertEquals("CREDIT", salary.rawDirection)
        assertEquals("14,000.00", salary.rawAmount)
        assertEquals("DEBIT", rent.rawDirection)
        assertEquals("5500.00", rent.rawAmount)
    }

    @Test
    fun `quoted field with an inner comma stays intact and reference is optional`() {
        val txs = success(scraper("leumi_statement_sample.csv").scrape(credentials, wholeRange)).transactions
        val aroma = txs.first { it.rawMerchant.contains("AROMA") }
        // The quoted "AROMA ESPRESSO BAR, DIZENGOFF" keeps its inner comma.
        assertEquals("AROMA ESPRESSO BAR, DIZENGOFF", aroma.rawMerchant)
        // That row's Reference column is empty -> null.
        assertNull(aroma.reference)
    }

    @Test
    fun `transactions are filtered to the requested date range`() {
        // June only: the two July salary rows and nothing after must be excluded.
        val june = DateRange(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
        val txs = success(scraper("leumi_statement_sample.csv").scrape(credentials, june)).transactions
        assertEquals(6, txs.size)
        assertTrue(txs.none { it.reference == "TXN-1006" }) // the 02/07 row
    }

    @Test
    fun `end to end - provider output through the mapper yields correct domain data`() {
        val result = success(scraper("leumi_statement_sample.csv").scrape(credentials, wholeRange))
        val mapped = ScrapeMapper.map(result)

        assertEquals(1, mapped.accounts.size)
        assertEquals(7, mapped.transactions.size)

        val salary = mapped.transactions.first { it.merchant.contains("Maskoret") }
        assertEquals(14_000.0, salary.amount, 1e-9)
        assertEquals(LocalDate.of(2026, 6, 1), salary.date)
        assertEquals("ref:leumi-checking-001:TXN-1000", salary.id)

        val rent = mapped.transactions.first { it.merchant.contains("Sechar Dira") }
        assertEquals(-5_500.0, rent.amount, 1e-9)

        // Every debit is negative, every credit positive (domain convention).
        val debits = mapped.transactions.filter { it.amount < 0 }
        val credits = mapped.transactions.filter { it.amount > 0 }
        assertEquals(5, debits.size)
        assertEquals(2, credits.size)
    }

    @Test
    fun `structurally malformed CSV fails with PARSE_ERROR`() {
        val result = scraper("malformed_short_row.csv").scrape(credentials, wholeRange)
        assertTrue("expected Failure, got $result", result is ScrapeResult.Failure)
        assertEquals(FailureReason.PARSE_ERROR, (result as ScrapeResult.Failure).reason)
    }

    @Test
    fun `header-only CSV succeeds with no transactions`() {
        val result = success(
            CsvBankScraper("Bank Leumi", account) { "Date,Description,Debit,Credit,Balance,Reference\n" }
                .scrape(credentials, wholeRange),
        )
        assertTrue(result.transactions.isEmpty())
    }

    @Test
    fun `unreadable source fails with NETWORK`() {
        val result = CsvBankScraper("Bank Leumi", account) { error("connection dropped") }
            .scrape(credentials, wholeRange)
        assertTrue(result is ScrapeResult.Failure)
        assertEquals(FailureReason.NETWORK, (result as ScrapeResult.Failure).reason)
    }
}
