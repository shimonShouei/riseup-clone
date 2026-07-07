package com.riseup.clone.domain.engine

import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.model.RecurringKind
import com.riseup.clone.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastEngineTest {

    private val engine = ForecastEngine()
    private var idCounter = 0

    private fun tx(date: LocalDate, amount: Double, merchant: String) = Transaction(
        id = "t${idCounter++}",
        accountId = "acc",
        date = date,
        amount = amount,
        merchant = merchant,
        category = Category.OTHER,
    )

    /**
     * Three months of: salary +10,000 on the 1st, rent -6,000 on the 3rd,
     * plus a steady ₪150/day discretionary burn (as varied one-off merchants
     * so it is NOT detected as recurring).
     */
    private fun buildLedger(from: LocalDate, until: LocalDate): List<Transaction> {
        val txs = mutableListOf<Transaction>()
        var ym = YearMonth.from(from)
        while (ym <= YearMonth.from(until)) {
            if (ym.atDay(1) in from..until) txs += tx(ym.atDay(1), 10_000.0, "Maskoret BM")
            if (ym.atDay(3) in from..until) txs += tx(ym.atDay(3), -6_000.0, "Sechar Dira")
            ym = ym.plusMonths(1)
        }
        var d = from
        var i = 0
        while (d <= until) {
            // Unique merchant per day -> never clusters as recurring.
            txs += tx(d, -150.0, "OneOff Shop ${i++}")
            d = d.plusDays(1)
        }
        return txs
    }

    @Test
    fun `horizon runs from today to end of next month`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = buildLedger(LocalDate.parse("2026-04-01"), today.minusDays(1))
        val result = engine.forecast(ledger, 5_000.0, today)

        assertEquals(today, result.series.first().date)
        assertEquals(LocalDate.parse("2026-08-31"), result.series.last().date)
        assertEquals(5_000.0, result.series.first().balance, 0.001)
        // One point per day, no gaps.
        assertEquals(31 - 7 + 31 + 1, result.series.size)
    }

    @Test
    fun `dips into overdraft then recovers after salary`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = buildLedger(LocalDate.parse("2026-04-01"), today.minusDays(1))
        // Burn is ~150/day, 24 days until Aug-1 salary => ~3,600 spend.
        // Start with 2,000: crosses below zero ~Jul-20, recovers on Aug-1.
        val result = engine.forecast(ledger, 2_000.0, today)

        assertTrue("expected overdraft, depth=${result.overdraftDepth}", result.overdraftDepth < 0)
        assertNotNull(result.overdraftDate)
        assertTrue(
            "overdraft should start before Aug salary",
            result.overdraftDate!! < LocalDate.parse("2026-08-01"),
        )
        // Month-end (Jul 31) is in the minus...
        assertTrue(result.monthEndBalance < 0)
        // ...salary on Aug 1 pulls it back up:
        val aug1 = result.series.first { it.date == LocalDate.parse("2026-08-01") }
        val jul31 = result.series.first { it.date == LocalDate.parse("2026-07-31") }
        assertTrue(aug1.balance > jul31.balance + 9_000)
        assertTrue("recovers above zero after salary", aug1.balance > 0)
        // The most negative point is exactly the reported depth.
        assertEquals(result.series.minOf { it.balance }, result.overdraftDepth, 0.001)
    }

    @Test
    fun `stays positive with a healthy balance`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = buildLedger(LocalDate.parse("2026-04-01"), today.minusDays(1))
        val result = engine.forecast(ledger, 40_000.0, today)

        assertEquals(0.0, result.overdraftDepth, 0.001)
        assertNull(result.overdraftDate)
        assertTrue(result.series.all { it.balance > 0 })
        assertTrue(result.monthEndBalance > 0)
        assertTrue(result.nextMonthBalance > 0)
    }

    @Test
    fun `future salary and rent are scheduled on their expected days`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = buildLedger(LocalDate.parse("2026-04-01"), today.minusDays(1))
        val result = engine.forecast(ledger, 5_000.0, today)

        val salaryEvents = result.projectedEvents.filter { it.kind == RecurringKind.SALARY }
        assertEquals(listOf(LocalDate.parse("2026-08-01")), salaryEvents.map { it.date })

        val rentEvents = result.projectedEvents.filter { it.kind == RecurringKind.RENT }
        // Jul rent (Jul 3) already happened before today=Jul 7; only Aug 3 remains.
        assertEquals(listOf(LocalDate.parse("2026-08-03")), rentEvents.map { it.date })
    }

    @Test
    fun `discretionary rate approximates historical burn and excludes recurring`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = buildLedger(LocalDate.parse("2026-04-01"), today.minusDays(1))
        val result = engine.forecast(ledger, 5_000.0, today)

        // Ledger burns exactly 150/day of non-recurring spend; rent/salary must
        // not pollute the rate.
        assertEquals(150.0, result.dailyDiscretionaryRate, 15.0)
    }

    @Test
    fun `weekly recurring item is projected weekly`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = buildLedger(LocalDate.parse("2026-04-01"), today.minusDays(1)).toMutableList()
        var d = LocalDate.parse("2026-04-05") // a Sunday
        while (d < today) {
            ledger += tx(d, -320.0, "Nikayon Dira")
            d = d.plusWeeks(1)
        }
        val result = engine.forecast(ledger, 5_000.0, today)
        val cleaner = result.projectedEvents.filter { it.merchant == "Nikayon Dira" }
        assertTrue("expected ~8 weekly events, got ${cleaner.size}", cleaner.size in 7..9)
        // All on Sundays, 7 days apart.
        assertTrue(cleaner.zipWithNext().all { (a, b) -> b.date == a.date.plusWeeks(1) })
    }
}
