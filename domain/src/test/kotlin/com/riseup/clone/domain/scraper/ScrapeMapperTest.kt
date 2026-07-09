package com.riseup.clone.domain.scraper

import com.riseup.clone.domain.model.AccountType
import com.riseup.clone.domain.model.Category
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dedicated suite for the raw -> domain mapper. Each concern the mapper owns
 * (merchant normalization, sign/amount handling, date parsing, category
 * defaulting, stable id derivation) is exercised in isolation.
 */
class ScrapeMapperTest {

    private fun rawTx(
        account: String = "acc-1",
        date: String = "05/06/2026",
        merchant: String = "SHUFERSAL DEAL 123 T-A",
        amount: String = "410.90",
        direction: String? = "DEBIT",
        category: String? = null,
        reference: String? = null,
    ) = ScrapedTransaction(
        accountExternalId = account,
        rawDate = date,
        rawMerchant = merchant,
        rawAmount = amount,
        rawDirection = direction,
        rawCategory = category,
        reference = reference,
    )

    // ---- Merchant normalization ------------------------------------------------

    @Test
    fun `merchant is normalized - branch numbers and case noise stripped`() {
        val a = ScrapeMapper.toTransaction(rawTx(merchant = "SHUFERSAL DEAL 123 T-A"))
        val b = ScrapeMapper.toTransaction(rawTx(merchant = "shufersal deal 77 t a"))
        // Same underlying merchant despite different case and branch numbers.
        assertEquals(b.merchant, a.merchant)
        assertEquals("Shufersal Deal T A", a.merchant)
    }

    @Test
    fun `merchant display is trimmed and collapsed`() {
        val tx = ScrapeMapper.toTransaction(rawTx(merchant = "  AROMA   ESPRESSO  BAR  "))
        assertEquals("Aroma Espresso Bar", tx.merchant)
    }

    // ---- Sign / amount handling ------------------------------------------------

    @Test
    fun `debit direction yields a negative amount`() {
        val tx = ScrapeMapper.toTransaction(rawTx(amount = "410.90", direction = "DEBIT"))
        assertEquals(-410.90, tx.amount, 1e-9)
        assertTrue(tx.isDebit)
    }

    @Test
    fun `credit direction yields a positive amount`() {
        val tx = ScrapeMapper.toTransaction(rawTx(amount = "14000.00", direction = "CREDIT"))
        assertEquals(14_000.0, tx.amount, 1e-9)
        assertTrue(tx.isCredit)
    }

    @Test
    fun `direction forces sign regardless of magnitude sign in the string`() {
        // A magnitude that already carries a sign must still obey the direction.
        assertEquals(-50.0, ScrapeMapper.parseAmount("50.00", "DEBIT"), 1e-9)
        assertEquals(50.0, ScrapeMapper.parseAmount("-50.00", "CREDIT"), 1e-9)
    }

    @Test
    fun `amount strips currency symbol and thousands separators`() {
        assertEquals(14_000.0, ScrapeMapper.parseAmount("₪ 14,000.00", "CREDIT"), 1e-9)
        assertEquals(1_234.56, ScrapeMapper.parseAmount("1,234.56", "CREDIT"), 1e-9)
    }

    @Test
    fun `embedded sign is honoured when no direction given`() {
        assertEquals(-99.9, ScrapeMapper.parseAmount("-99.90", null), 1e-9)
        assertEquals(99.9, ScrapeMapper.parseAmount("99.90", null), 1e-9)
    }

    @Test
    fun `unparseable amount throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrapeMapper.parseAmount("not-a-number", "DEBIT")
        }
    }

    // ---- Date parsing ----------------------------------------------------------

    @Test
    fun `parses day-first and iso date formats`() {
        assertEquals(LocalDate.of(2026, 6, 5), ScrapeMapper.parseDate("05/06/2026"))
        assertEquals(LocalDate.of(2026, 6, 5), ScrapeMapper.parseDate("5/6/2026"))
        assertEquals(LocalDate.of(2026, 6, 5), ScrapeMapper.parseDate("2026-06-05"))
        assertEquals(LocalDate.of(2026, 6, 5), ScrapeMapper.parseDate("05.06.2026"))
    }

    @Test
    fun `unparseable date throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrapeMapper.parseDate("June 5th 2026")
        }
    }

    // ---- Category defaulting ---------------------------------------------------

    @Test
    fun `category defaults to OTHER when absent or unknown`() {
        assertEquals(Category.OTHER, ScrapeMapper.toTransaction(rawTx(category = null)).category)
        assertEquals(Category.OTHER, ScrapeMapper.toCategory("SomethingWeDontKnow"))
    }

    @Test
    fun `recognized category label is mapped case-insensitively`() {
        assertEquals(Category.GROCERIES, ScrapeMapper.toCategory("groceries"))
        assertEquals(Category.SALARY, ScrapeMapper.toCategory(" SALARY "))
    }

    // ---- Stable id derivation --------------------------------------------------

    @Test
    fun `id is stable across identical rows`() {
        val a = ScrapeMapper.toTransaction(rawTx(reference = null))
        val b = ScrapeMapper.toTransaction(rawTx(reference = null))
        assertEquals(a.id, b.id)
    }

    @Test
    fun `id changes when the amount changes`() {
        val a = ScrapeMapper.toTransaction(rawTx(amount = "410.90", reference = null))
        val b = ScrapeMapper.toTransaction(rawTx(amount = "999.00", reference = null))
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `id prefers the bank reference when present`() {
        val tx = ScrapeMapper.toTransaction(rawTx(reference = "TXN-1002"))
        assertEquals("ref:acc-1:TXN-1002", tx.id)
    }

    @Test
    fun `id derived from content is insensitive to merchant branch-number noise`() {
        val a = ScrapeMapper.toTransaction(rawTx(merchant = "SHUFERSAL DEAL 123 T-A", reference = null))
        val b = ScrapeMapper.toTransaction(rawTx(merchant = "shufersal deal 77 t a", reference = null))
        // Same merchant identity + same date/amount/account => same de-dup id.
        assertEquals(a.id, b.id)
    }

    // ---- Account mapping -------------------------------------------------------

    @Test
    fun `account type is inferred from raw type label with checking default`() {
        assertEquals(AccountType.CREDIT_CARD, ScrapeMapper.toAccountType("Visa Credit Card"))
        assertEquals(AccountType.SAVINGS, ScrapeMapper.toAccountType("Savings Deposit"))
        assertEquals(AccountType.CHECKING, ScrapeMapper.toAccountType(null))
        assertEquals(AccountType.CHECKING, ScrapeMapper.toAccountType("Cheshbon Over Vashav"))
    }

    @Test
    fun `account maps external id and trims labels`() {
        val account = ScrapeMapper.toAccount(
            ScrapedAccount(externalId = "leumi-123", label = "  My Checking ", institution = "Bank Leumi "),
        )
        assertEquals("leumi-123", account.id)
        assertEquals("My Checking", account.name)
        assertEquals("Bank Leumi", account.institution)
    }
}
