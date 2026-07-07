package com.riseup.clone.domain.seed

import com.riseup.clone.domain.engine.ForecastEngine
import com.riseup.clone.domain.model.Cadence
import com.riseup.clone.domain.model.RecurringKind
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end check on the shipped seed data: it must reproduce the product's
 * hero visual — a forecast that dips into the minus late in the month and
 * recovers after salary.
 */
class SeededForecastTest {

    private val engine = ForecastEngine()

    @Test
    fun `seed produces about four months of dense history`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = SeedDataGenerator().generate(today)
        assertTrue("expected dense ledger, got ${ledger.transactions.size}", ledger.transactions.size > 200)
        assertTrue(ledger.transactions.all { it.date < today })
        assertTrue(ledger.transactions.minOf { it.date } <= today.minusMonths(3))
    }

    @Test
    fun `seed recurring items are detected - salary rent subscriptions and weekly cleaner`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = SeedDataGenerator().generate(today)
        val result = engine.forecast(ledger.transactions, ledger.currentBalance, today)
        val items = result.recurringItems

        assertEquals(1, items.count { it.kind == RecurringKind.SALARY })
        assertEquals(1, items.count { it.kind == RecurringKind.RENT })
        val subs = items.filter { it.kind == RecurringKind.SUBSCRIPTION_OR_UTILITY }
        // Netflix, Spotify, gym, electric, water, arnona, cellcom, partner ~ 8
        assertTrue("expected >=6 subscriptions/utilities, got ${subs.size}: $subs", subs.size >= 6)
        assertTrue(items.any { it.cadence == Cadence.WEEKLY })
        assertEquals(14_000.0, items.first { it.kind == RecurringKind.SALARY }.amount, 1.0)
        assertEquals(-5_500.0, items.first { it.kind == RecurringKind.RENT }.amount, 1.0)
    }

    @Test
    fun `seeded forecast dips into the minus and recovers after salary`() {
        // Run the hero-scenario assertion from several vantage points in the
        // month, since the app computes "today" at runtime.
        for (todayStr in listOf("2026-07-05", "2026-07-14", "2026-07-23")) {
            val today = LocalDate.parse(todayStr)
            val ledger = SeedDataGenerator().generate(today)
            val result = engine.forecast(ledger.transactions, ledger.currentBalance, today)

            assertTrue(
                "[$today] expected overdraft dip, depth=${result.overdraftDepth}",
                result.overdraftDepth < 0,
            )
            assertNotNull("[$today] overdraftDate", result.overdraftDate)
            assertTrue(
                "[$today] dip should be believable (not a collapse), depth=${result.overdraftDepth}",
                result.overdraftDepth > -6_000,
            )

            // Recovery: after the balance first crosses below zero, the next
            // salary pulls it back above zero later in the horizon.
            val crossIdx = result.series.indexOfFirst { it.balance < 0 }
            val recovered = result.series.drop(crossIdx + 1).any { it.balance > 0 }
            assertTrue("[$today] expected recovery after the dip", recovered)
        }
    }

    @Test
    fun `seed is deterministic`() {
        val today = LocalDate.parse("2026-07-07")
        val a = SeedDataGenerator().generate(today)
        val b = SeedDataGenerator().generate(today)
        assertEquals(a.transactions, b.transactions)
        assertEquals(a.currentBalance, b.currentBalance, 0.001)
    }
}
