package com.riseup.clone.domain.engine

import com.riseup.clone.domain.model.Cadence
import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.model.RecurringKind
import com.riseup.clone.domain.model.Transaction
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringDetectorTest {

    private val detector = RecurringDetector()
    private var idCounter = 0

    private fun tx(date: String, amount: Double, merchant: String) = Transaction(
        id = "t${idCounter++}",
        accountId = "acc",
        date = LocalDate.parse(date),
        amount = amount,
        merchant = merchant,
        category = Category.OTHER,
    )

    @Test
    fun `detects monthly salary as large recurring credit`() {
        val txs = listOf(
            tx("2026-04-01", 14_000.0, "Maskoret Hi-Tech BM"),
            tx("2026-05-01", 14_000.0, "Maskoret Hi-Tech BM"),
            tx("2026-06-01", 14_120.0, "Maskoret Hi-Tech BM"),
        )
        val items = detector.detect(txs)
        assertEquals(1, items.size)
        val salary = items.single()
        assertEquals(RecurringKind.SALARY, salary.kind)
        assertEquals(Cadence.MONTHLY, salary.cadence)
        assertEquals(1, salary.dayOfMonth)
        assertEquals(14_000.0, salary.amount, 1.0)
    }

    @Test
    fun `detects rent and subscription kinds`() {
        val txs = listOf(
            tx("2026-04-03", -5_500.0, "Sechar Dira Cohen"),
            tx("2026-05-03", -5_500.0, "Sechar Dira Cohen"),
            tx("2026-06-03", -5_500.0, "Sechar Dira Cohen"),
            tx("2026-04-05", -54.90, "Netflix.com 001"),
            tx("2026-05-05", -54.90, "Netflix.com 002"),
            tx("2026-06-05", -54.90, "Netflix.com 003"),
        )
        val items = detector.detect(txs)
        assertEquals(2, items.size)
        assertEquals(RecurringKind.RENT, items.first { it.merchantKey.contains("sechar") }.kind)
        assertEquals(
            RecurringKind.SUBSCRIPTION_OR_UTILITY,
            items.first { it.merchantKey.contains("netflix") }.kind,
        )
    }

    @Test
    fun `detects weekly cadence`() {
        val txs = (0L..5L).map { w ->
            tx(LocalDate.parse("2026-05-03").plusWeeks(w).toString(), -320.0, "Nikayon Dira")
        }
        val items = detector.detect(txs)
        assertEquals(1, items.size)
        assertEquals(Cadence.WEEKLY, items.single().cadence)
    }

    @Test
    fun `merchant normalization merges branch numbers and case`() {
        assertEquals(
            detector.normalizeMerchant("SHUFERSAL DEAL 123 T-A"),
            detector.normalizeMerchant("shufersal deal 77 t a"),
        )
    }

    @Test
    fun `irregular grocery spend is not recurring`() {
        // Same merchant, but wildly varying amounts and non-steady dates.
        val txs = listOf(
            tx("2026-05-02", -410.0, "Shufersal Deal"),
            tx("2026-05-06", -95.0, "Shufersal Deal"),
            tx("2026-05-13", -333.0, "Shufersal Deal"),
            tx("2026-05-17", -58.0, "Shufersal Deal"),
            tx("2026-05-30", -220.0, "Shufersal Deal"),
            tx("2026-06-11", -480.0, "Shufersal Deal"),
        )
        val items = detector.detect(txs)
        assertTrue("expected no recurring items, got $items", items.isEmpty())
    }

    @Test
    fun `two occurrences are not enough`() {
        val txs = listOf(
            tx("2026-05-01", -100.0, "SomeSub"),
            tx("2026-06-01", -100.0, "SomeSub"),
        )
        assertNull(detector.detect(txs).firstOrNull())
    }

    @Test
    fun `same merchant different amount bands split into clusters`() {
        // Gym charges a monthly fee AND an annual-ish big charge under one name;
        // only the steady monthly fee should be detected.
        val txs = listOf(
            tx("2026-04-02", -189.0, "Holmes Place"),
            tx("2026-05-02", -189.0, "Holmes Place"),
            tx("2026-06-02", -189.0, "Holmes Place"),
            tx("2026-05-20", -1_200.0, "Holmes Place"),
        )
        val items = detector.detect(txs)
        assertEquals(1, items.size)
        assertEquals(-189.0, items.single().amount, 0.01)
        assertEquals(3, items.single().occurrences)
    }
}
