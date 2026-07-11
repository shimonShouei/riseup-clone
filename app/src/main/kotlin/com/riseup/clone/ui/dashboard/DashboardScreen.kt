package com.riseup.clone.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riseup.clone.domain.model.ForecastResult
import com.riseup.clone.domain.model.ProjectedEvent
import com.riseup.clone.ui.format.formatShekel
import com.riseup.clone.ui.format.formatShekelSigned
import com.riseup.clone.ui.theme.LocalFlowColors
import com.riseup.clone.ui.theme.MoneyLarge
import com.riseup.clone.ui.theme.MoneyMedium
import com.riseup.clone.ui.theme.MoneySmall
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, onRefresh: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        DashboardUiState.Loading -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        is DashboardUiState.Ready -> Dashboard(s, onRefresh)
    }
}

@Composable
private fun Dashboard(state: DashboardUiState.Ready, onRefresh: (() -> Unit)?) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(state.today, state.currentBalance, onRefresh) }
        item { HeadlineCard(state.forecast) }
        item { ChartCard(state) }
        item { UpcomingCard(state.forecast.projectedEvents) }
        item { BreakdownCard(state.categories) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Header(today: LocalDate, currentBalance: Double, onRefresh: (() -> Unit)?) {
    Row(
        Modifier.padding(top = 12.dp, bottom = 2.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Your cash flow",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Balance today  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalFlowColors.current.mutedText,
                )
                Text(
                    formatShekel(currentBalance),
                    style = MoneySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        // Manual refresh: re-fetch the remembered statement URL. Auto-fetch on open
        // covers the normal case; this is the on-demand path.
        if (onRefresh != null) {
            androidx.compose.material3.TextButton(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun HeadlineCard(forecast: ForecastResult) {
    val flow = LocalFlowColors.current
    val monthName = forecast.today.month.getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)
    val nextMonthName = forecast.today.month.plus(1).getDisplayName(JavaTextStyle.FULL, Locale.ENGLISH)

    AppCard {
        Text(
            "Forecast — end of $monthName",
            style = MaterialTheme.typography.labelLarge,
            color = flow.mutedText,
        )
        Text(
            formatShekel(forecast.monthEndBalance),
            style = MoneyLarge,
            color = if (forecast.monthEndBalance < 0) flow.negative else flow.positive,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("End of $nextMonthName", style = MaterialTheme.typography.labelMedium, color = flow.mutedText)
                Text(
                    formatShekel(forecast.nextMonthBalance),
                    style = MoneyMedium,
                    color = if (forecast.nextMonthBalance < 0) flow.negative else flow.positive,
                )
            }
            if (forecast.overdraftDepth < 0 && forecast.overdraftDate != null) {
                OverdraftChip(forecast.overdraftDepth, forecast.overdraftDate!!)
            }
        }
    }
}

@Composable
private fun OverdraftChip(depth: Double, date: LocalDate) {
    val flow = LocalFlowColors.current
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(flow.negative.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            "Into the minus",
            style = MaterialTheme.typography.labelSmall,
            color = flow.negative,
        )
        Text(
            "${formatShekel(depth)} · ${date.format(dayMonth)}",
            style = MoneySmall,
            color = flow.negative,
        )
    }
}

@Composable
private fun ChartCard(state: DashboardUiState.Ready) {
    AppCard {
        Text(
            "Projected balance",
            style = MaterialTheme.typography.labelLarge,
            color = LocalFlowColors.current.mutedText,
        )
        Spacer(Modifier.height(4.dp))
        ForecastChart(points = state.chart, today = state.today)
    }
}

@Composable
private fun UpcomingCard(events: List<ProjectedEvent>) {
    val flow = LocalFlowColors.current
    val upcoming = events.take(5)
    if (upcoming.isEmpty()) return
    AppCard {
        Text("Coming up", style = MaterialTheme.typography.labelLarge, color = flow.mutedText)
        Spacer(Modifier.height(6.dp))
        upcoming.forEachIndexed { i, event ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        event.merchant,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        event.date.format(dayMonth),
                        style = MaterialTheme.typography.labelSmall,
                        color = flow.mutedText,
                    )
                }
                Text(
                    formatShekelSigned(event.amount),
                    style = MoneySmall,
                    color = if (event.amount < 0) MaterialTheme.colorScheme.onSurface else flow.positive,
                )
            }
        }
    }
}

@Composable
private fun BreakdownCard(categories: List<CategorySpend>) {
    val flow = LocalFlowColors.current
    if (categories.isEmpty()) return
    AppCard {
        Text(
            "Spending — last 30 days",
            style = MaterialTheme.typography.labelLarge,
            color = flow.mutedText,
        )
        Spacer(Modifier.height(10.dp))
        val maxFraction = categories.first().fraction.coerceAtLeast(0.01)
        categories.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(104.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((row.fraction / maxFraction).toFloat())
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(flow.positive),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    formatShekel(row.amount),
                    style = MoneySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.width(72.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun AppCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
