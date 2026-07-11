package com.riseup.clone.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riseup.clone.ui.theme.LocalFlowColors

/**
 * First-run "set up your cash flow" screen. No credentials and no backend config —
 * real data enters via a CSV statement:
 *
 * 1. **Statement URL (cloud or LAN)** — the primary path: a URL fetched + imported,
 *    remembered once and auto-fetched on later opens ([FetchFromScraperSection]).
 * 2. **Import statement (CSV)** — the manual file alternative ([ImportStatementSection]).
 * 3. **Load sample data** — the demo path (a bundled multi-month sample statement).
 *
 * A thin state-in / events-out shell: all decisions live in [ConnectBankViewModel].
 */
@Composable
fun ConnectBankScreen(viewModel: ConnectBankViewModel) {
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()
    val savedScraperUrl by viewModel.savedScraperUrl.collectAsStateWithLifecycle()
    val sampleState by viewModel.sampleState.collectAsStateWithLifecycle()
    val flow = LocalFlowColors.current

    val importing = importState is ImportUiState.Importing
    val fetching = fetchState is ImportUiState.Importing
    val loadingSample = sampleState is ImportUiState.Importing
    val busy = importing || fetching || loadingSample

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "Set up your cash flow",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Import a bank statement to forecast your cash flow. Everything stays " +
                "on your device — no bank login, no networking, nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = flow.mutedText,
        )

        Spacer(Modifier.height(4.dp))

        // 1. Primary path: fetch the CSV from a statement URL (cloud upload or LAN serve).
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Statement URL",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FetchFromScraperSection(
                    fetchState = fetchState,
                    savedUrl = savedScraperUrl,
                    enabled = !busy,
                    onFetch = { url -> viewModel.fetchFromScraper(url) },
                    onContinue = viewModel::continueToDashboard,
                    mutedColor = flow.mutedText,
                )
            }
        }

        // 2. Manual alternative: import a CSV statement file.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Import statement",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ImportStatementSection(
                    importState = importState,
                    enabled = !busy,
                    onCsvText = { csv -> viewModel.importStatement(csv) },
                    onReadError = viewModel::reportImportError,
                    onContinue = viewModel::continueToDashboard,
                    mutedColor = flow.mutedText,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // 3. Demo path: load bundled sample data.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Just exploring?",
                style = MaterialTheme.typography.labelLarge,
                color = flow.mutedText,
            )
            OutlinedButton(
                onClick = { viewModel.loadSample() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loadingSample) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading sample…")
                } else {
                    Text("Load sample data")
                }
            }
            val sampleError = sampleState as? ImportUiState.Error
            if (sampleError != null) {
                Text(
                    "Couldn't load the sample: ${sampleError.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = flow.negative,
                )
            }
            Text(
                "Loads a realistic multi-month sample statement so you can see the " +
                    "forecast without importing your own data.",
                style = MaterialTheme.typography.labelSmall,
                color = flow.mutedText,
            )
        }
    }
}
