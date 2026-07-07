package com.riseup.clone.domain.engine

import com.riseup.clone.domain.model.Cadence
import com.riseup.clone.domain.model.DailyBalance
import com.riseup.clone.domain.model.ForecastResult
import com.riseup.clone.domain.model.ProjectedEvent
import com.riseup.clone.domain.model.RecurringItem
import com.riseup.clone.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The core RiseUp mechanic: given a ledger and today's balance, project the
 * daily balance from today to the end of next month.
 *
 * Projection = current balance
 *            + future recurring items on their expected dates
 *            - a daily discretionary spend rate derived from historical
 *              non-recurring debits.
 */
class ForecastEngine(
    private val detector: RecurringDetector = RecurringDetector(),
    /** How far back to look when estimating the discretionary rate. */
    private val discretionaryLookbackDays: Long = 90,
) {

    fun forecast(
        transactions: List<Transaction>,
        currentBalance: Double,
        today: LocalDate,
    ): ForecastResult {
        val history = transactions.filter { !it.date.isAfter(today) }
        val recurring = detector.detect(history)
        val dailyRate = discretionaryDailyRate(history, recurring, today)

        val horizonEnd = YearMonth.from(today).plusMonths(1).atEndOfMonth()
        val events = projectEvents(recurring, today, horizonEnd)
        val eventsByDate = events.groupBy { it.date }

        val series = ArrayList<DailyBalance>(ChronoUnit.DAYS.between(today, horizonEnd).toInt() + 1)
        var balance = currentBalance
        series += DailyBalance(today, balance)
        var date = today.plusDays(1)
        while (!date.isAfter(horizonEnd)) {
            balance += eventsByDate[date]?.sumOf { it.amount } ?: 0.0
            balance -= dailyRate
            series += DailyBalance(date, balance)
            date = date.plusDays(1)
        }

        val monthEnd = YearMonth.from(today).atEndOfMonth()
        val monthEndBalance = series.last { it.date <= monthEnd }.balance
        val nextMonthBalance = series.last().balance
        val minPoint = series.minBy { it.balance }
        val overdraftDepth = minOf(0.0, minPoint.balance)
        val overdraftDate = series.firstOrNull { it.balance < 0.0 }?.date

        return ForecastResult(
            today = today,
            currentBalance = currentBalance,
            monthEndBalance = monthEndBalance,
            nextMonthBalance = nextMonthBalance,
            overdraftDepth = overdraftDepth,
            overdraftDate = overdraftDate,
            series = series,
            recurringItems = recurring,
            projectedEvents = events,
            dailyDiscretionaryRate = dailyRate,
        )
    }

    /**
     * Average daily spend of everything that is NOT part of a recurring
     * cluster, over the lookback window. Returned as a positive ₪/day rate.
     */
    fun discretionaryDailyRate(
        history: List<Transaction>,
        recurring: List<RecurringItem>,
        today: LocalDate,
    ): Double {
        val recurringIds = recurring.flatMapTo(HashSet()) { it.transactionIds }
        val windowStart = today.minusDays(discretionaryLookbackDays)
        val window = history.filter {
            it.isDebit && it.id !in recurringIds && !it.date.isBefore(windowStart)
        }
        if (window.isEmpty()) return 0.0
        val earliest = window.minOf { it.date }
        val days = maxOf(1, ChronoUnit.DAYS.between(earliest, today))
        return abs(window.sumOf { it.amount }) / days
    }

    /** Expands recurring items into concrete dated events inside (today, horizonEnd]. */
    fun projectEvents(
        recurring: List<RecurringItem>,
        today: LocalDate,
        horizonEnd: LocalDate,
    ): List<ProjectedEvent> {
        val events = mutableListOf<ProjectedEvent>()
        for (item in recurring) {
            var next = firstOccurrenceAfter(item, today)
            while (!next.isAfter(horizonEnd)) {
                events += ProjectedEvent(next, item.merchant, item.amount, item.kind)
                next = when (item.cadence) {
                    Cadence.WEEKLY -> next.plusWeeks(1)
                    Cadence.MONTHLY -> onDayOfMonth(YearMonth.from(next).plusMonths(1), item.dayOfMonth)
                }
            }
        }
        return events.sortedBy { it.date }
    }

    private fun firstOccurrenceAfter(item: RecurringItem, today: LocalDate): LocalDate =
        when (item.cadence) {
            Cadence.WEEKLY -> {
                var d = item.lastDate.plusWeeks(1)
                while (!d.isAfter(today)) d = d.plusWeeks(1)
                d
            }
            Cadence.MONTHLY -> {
                var ym = YearMonth.from(item.lastDate).plusMonths(1)
                var d = onDayOfMonth(ym, item.dayOfMonth)
                while (!d.isAfter(today)) {
                    ym = ym.plusMonths(1)
                    d = onDayOfMonth(ym, item.dayOfMonth)
                }
                d
            }
        }

    private fun onDayOfMonth(ym: YearMonth, day: Int): LocalDate =
        ym.atDay(day.coerceIn(1, ym.lengthOfMonth()))
}
