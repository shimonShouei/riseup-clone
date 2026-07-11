package com.riseup.clone.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.riseup.clone.data.PersistedTransactionRepository
import com.riseup.clone.data.SeededTransactionRepository
import com.riseup.clone.data.TransactionRepository
import com.riseup.clone.data.local.LedgerDatabase
import com.riseup.clone.data.sync.AppSync
import com.riseup.clone.data.sync.SyncState
import com.riseup.clone.domain.engine.ForecastEngine
import com.riseup.clone.domain.model.Transaction
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads the household ledger from [repository] (the persisted Room store in the
 * real app; see [factory]) and derives the dashboard's forecast/chart/breakdown.
 *
 * Refresh surface: the ViewModel observes the process-wide [AppSync] state and
 * reloads the ledger whenever a statement import publishes [SyncState.Success], so
 * freshly imported rows appear without leaving the screen.
 */
class DashboardViewModel(
    private val repository: TransactionRepository,
    private val engine: ForecastEngine = ForecastEngine(),
    private val clock: () -> LocalDate = { LocalDate.now() },
    private val syncState: StateFlow<SyncState> = AppSync.state,
) : ViewModel() {

    private val _state = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    /** Days of actual balance history shown before the projection. */
    private val historyDays = 14L

    /** Spending-breakdown window. */
    private val breakdownDays = 30L

    init {
        viewModelScope.launch { load() }
        // Reload the ledger whenever a statement import finishes writing new data, so
        // the dashboard reflects imported transactions without a manual refresh.
        viewModelScope.launch {
            syncState.collect { if (it is SyncState.Success) load() }
        }
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

    companion object {
        /**
         * Builds a ViewModel reading the persisted Room ledger (real mode) or the
         * in-memory seed ([demo] mode — kept reachable so the seeded hero visual can
         * still be previewed). In real mode [PersistedTransactionRepository] seeds
         * itself on an empty DB, so the dashboard shows the seed forecast until a
         * statement import writes real data.
         */
        fun factory(context: Context, demo: Boolean = false): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repo: TransactionRepository = if (demo) {
                        SeededTransactionRepository()
                    } else {
                        PersistedTransactionRepository(LedgerDatabase.build(app).ledgerDao())
                    }
                    return DashboardViewModel(repository = repo) as T
                }
            }
        }
    }
}
