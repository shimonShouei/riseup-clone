package com.riseup.clone.domain.scraper

import com.riseup.clone.domain.engine.ForecastEngine
import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.seed.SeedDataGenerator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connect-bank flow imports [SampleStatementCsv] through the real
 * scrape → map pipeline. These tests prove that path produces a *rich* ledger —
 * the whole point of the fix (a thin fixture left the dashboard empty).
 */
class SampleStatementCsvTest {

    private val today = LocalDate.parse("2026-07-07")
    private val institution = "Bank Leumi"
    private val account = ScrapedAccount(
        externalId = "$institution-checking",
        label = "Checking",
        institution = institution,
        rawType = "checking",
    )

    private fun scrapeSample(): ScrapeResult.Success {
        val scraper = CsvBankScraper(institution, account) { SampleStatementCsv.forToday(today) }
        val result = scraper.scrape(
            ScraperCredentials("user", "pass"),
            DateRange(LocalDate.of(2000, 1, 1), today),
        )
        return result as ScrapeResult.Success
    }

    @Test
    fun `imported statement carries the full seeded history, not a thin sample`() {
        val mapped = ScrapeMapper.map(scrapeSample())
        assertTrue(
            "expected a dense import, got ${mapped.transactions.size}",
            mapped.transactions.size > 200,
        )
        assertEquals(1, mapped.accounts.size)
    }

    @Test
    fun `every transaction maps back to the exact seed row (round-trip)`() {
        val seeded = SeedDataGenerator().generate(today)
        val mapped = ScrapeMapper.map(scrapeSample())
        // Same count, and each seed row is reproduced by date + amount (cents).
        assertEquals(seeded.transactions.size, mapped.transactions.size)
        val order = compareBy<Pair<LocalDate, Long>>({ it.first }, { it.second })
        val seedKeys = seeded.transactions.map { it.date to Math.round(it.amount * 100) }.sortedWith(order)
        val mappedKeys = mapped.transactions.map { it.date to Math.round(it.amount * 100) }.sortedWith(order)
        assertEquals(seedKeys, mappedKeys)
    }

    @Test
    fun `categories survive the CSV round-trip so the breakdown is not all Other`() {
        val mapped = ScrapeMapper.map(scrapeSample())
        val categories = mapped.transactions.map { it.category }.toSet()
        assertTrue("salary category lost", Category.SALARY in categories)
        assertTrue("rent category lost", Category.RENT in categories)
        assertTrue("groceries category lost", Category.GROCERIES in categories)
        // Sanity: the ledger isn't dominated by the default bucket.
        val other = mapped.transactions.count { it.category == Category.OTHER }
        assertTrue("too many uncategorized rows: $other", other < mapped.transactions.size / 2)
    }

    @Test
    fun `imported ledger still reproduces the overdraft-dip hero forecast`() {
        val mapped = ScrapeMapper.map(scrapeSample())
        val balance = mapped.transactions.sumOf { it.amount } // opening ~0 for the check
        val result = ForecastEngine().forecast(mapped.transactions, balance, today)
        assertNotNull("expected recurring detection on imported data", result.recurringItems)
        assertTrue("expected recurring items detected", result.recurringItems.isNotEmpty())
    }
}
