package com.riseup.clone.ui.dashboard

import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.model.ForecastResult
import java.time.LocalDate

/** One point on the dashboard chart; history and projection share one series. */
data class ChartPoint(
    val date: LocalDate,
    val balance: Double,
    val projected: Boolean,
)

data class CategorySpend(
    val category: Category,
    val label: String,
    /** Positive ₪ spent in the window. */
    val amount: Double,
    /** Share of the total spend, 0..1. */
    val fraction: Double,
)

sealed interface DashboardUiState {
    data object Loading : DashboardUiState

    data class Ready(
        val today: LocalDate,
        val currentBalance: Double,
        val forecast: ForecastResult,
        /** Recent actual balance (last ~2 weeks) followed by the projection. */
        val chart: List<ChartPoint>,
        /** Last-30-days spending by category, largest first. */
        val categories: List<CategorySpend>,
    ) : DashboardUiState
}

val Category.displayName: String
    get() = when (this) {
        Category.SALARY -> "Salary"
        Category.RENT -> "Rent"
        Category.UTILITIES -> "Utilities"
        Category.SUBSCRIPTIONS -> "Subscriptions"
        Category.GROCERIES -> "Groceries"
        Category.CAFES -> "Cafes"
        Category.RESTAURANTS -> "Restaurants"
        Category.FUEL -> "Fuel"
        Category.HEALTH -> "Health & Pharm"
        Category.OTHER -> "Other"
    }
