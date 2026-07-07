package com.riseup.clone.domain.model

import java.time.LocalDate

/** One point of the projected (or historical) balance series. */
data class DailyBalance(
    val date: LocalDate,
    val balance: Double,
)

/** A concrete projected future cash event (a recurring item on its expected date). */
data class ProjectedEvent(
    val date: LocalDate,
    val merchant: String,
    val amount: Double,
    val kind: RecurringKind,
)

/**
 * Result of running the forecast from [today] to the end of next month.
 */
data class ForecastResult(
    val today: LocalDate,
    val currentBalance: Double,
    /** Projected balance at the last day of the current month. */
    val monthEndBalance: Double,
    /** Projected balance at the last day of next month. */
    val nextMonthBalance: Double,
    /**
     * Most negative projected balance in the horizon; 0.0 if the projection
     * never goes below zero.
     */
    val overdraftDepth: Double,
    /** First projected date the balance crosses below zero, if it does. */
    val overdraftDate: LocalDate?,
    /** Daily projected balance from today (inclusive) to end of next month. */
    val series: List<DailyBalance>,
    /** Recurring items detected in the ledger. */
    val recurringItems: List<RecurringItem>,
    /** Future recurring events scheduled inside the horizon. */
    val projectedEvents: List<ProjectedEvent>,
    /** Estimated discretionary (non-recurring) spend per day, as a positive number. */
    val dailyDiscretionaryRate: Double,
)
