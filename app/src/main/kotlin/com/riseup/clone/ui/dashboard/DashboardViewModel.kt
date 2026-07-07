package com.riseup.clone.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riseup.clone.data.SeededTransactionRepository
import com.riseup.clone.data.TransactionRepository
import com.riseup.clone.domain.engine.ForecastEngine
import com.riseup.clone.domain.model.Transaction
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(
    private val repository: TransactionRepository = SeededTransactionRepository(),
    private val engine: ForecastEngine = ForecastEngine(),
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /** Days of actual balance history shown before the projection. */
    private val historyDays = 14L

    /** Spending-breakdown window. */
    private val breakdownDays = 30L

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val today = clock()
        val ledger = repository.loadLedger(today)
        val content = withContext(Dispatchers.Default) {
            val forecast = engine.forecast(ledger.transactions, ledger.currentBalance, today)
            DashboardUiState.Ready(
                today = today,
                currentBalance = ledger.currentBalance,
                forecast = forecast,
                chart = buildChart(ledger.transactions, ledger.currentBalance, today, forecast),
                categories = buildBreakdown(ledger.transactions, today),
            )
        }
        _state.value = content
    }

    /**
     * Reconstructs the recent actual balance by walking backwards from the
     * current balance, then appends the projected series.
     */
    private fun buildChart(
        transactions: List<Transaction>,
        currentBalance: Double,
        today: LocalDate,
        forecast: com.riseup.clone.domain.model.ForecastResult,
    ): List<ChartPoint> {
        val byDate = transactions.groupBy { it.date }
        val history = ArrayDeque<ChartPoint>()
        // Current balance == balance at start of today == end of yesterday.
        var balance = currentBalance
        var day = today.minusDays(1)
        val firstDay = today.minusDays(historyDays)
        while (day >= firstDay) {
            history.addFirst(ChartPoint(day, balance, projected = false))
            balance -= byDate[day]?.sumOf { it.amount } ?: 0.0
            day = day.minusDays(1)
        }
        val projection = forecast.series.map { ChartPoint(it.date, it.balance, projected = true) }
        return history + projection
    }

    private fun buildBreakdown(
        transactions: List<Transaction>,
        today: LocalDate,
    ): List<CategorySpend> {
        val windowStart = today.minusDays(breakdownDays)
        val spend = transactions
            .filter { it.isDebit && it.date >= windowStart }
            .groupBy { it.category }
            .mapValues { (_, txs) -> -txs.sumOf { it.amount } }
        val total = spend.values.sum().takeIf { it > 0 } ?: return emptyList()
        return spend.entries
            .sortedByDescending { it.value }
            .map { (category, amount) ->
                CategorySpend(
                    category = category,
                    label = category.displayName,
                    amount = amount,
                    fraction = amount / total,
                )
            }
    }
}
