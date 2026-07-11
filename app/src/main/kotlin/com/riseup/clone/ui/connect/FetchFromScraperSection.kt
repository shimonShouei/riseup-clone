package com.riseup.clone.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.riseup.clone.ui.theme.LocalFlowColors

/**
 * The "Statement URL" action: a second CSV *source* next to the file picker. The user
 * enters the statement URL from the scraper — its cloud upload link (works from any
 * network) or its LAN `serve` endpoint — prefilled from the last-used value, and taps
 * Fetch; the returned text flows into the same [StatementImporter] the file path uses,
 * so success/error render identically. Once entered, the URL is remembered and
 * auto-fetched on every app open, so this is normally a one-time step.
 *
 * State-in / events-out, matching [ImportStatementSection]: all decisions live in
 * [ConnectBankViewModel]; this composable only holds the editable URL field and
 * renders the fetch phase.
 */
@Composable
fun FetchFromScraperSection(
    fetchState: ImportUiState,
    savedUrl: String,
    enabled: Boolean,
    onFetch: (String) -> Unit,
    onContinue: () -> Unit,
    mutedColor: Color,
) {
    val fetching = fetchState is ImportUiState.Importing
    var url by remember { mutableStateOf(savedUrl) }
    // Adopt the persisted URL once it loads (async), unless the user has edited it.
    LaunchedEffect(savedUrl) {
        if (url.isBlank() && savedUrl.isNotBlank()) url = savedUrl
    }

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        enabled = enabled && !fetching,
        singleLine = true,
        label = { Text("Statement URL") },
        placeholder = { Text("https://…dropbox…?dl=1  or  http://192.168.1.23:8788/statement.csv") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedButton(
        onClick = { onFetch(url) },
        enabled = enabled && !fetching && url.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (fetching) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Fetching…")
        } else {
            Text("Fetch statement")
        }
    }

    when (val s = fetchState) {
        is ImportUiState.Success -> FetchSuccess(count = s.count, onContinue = onContinue)
        is ImportUiState.Error -> FetchError(message = s.message)
        else -> Unit
    }

    Text(
        "Paste the statement URL the scraper printed — its cloud upload link works from " +
            "any network; its LAN address only on the same Wi-Fi. Entered once, it's " +
            "remembered and refreshed automatically each time you open the app.",
        style = MaterialTheme.typography.labelSmall,
        color = mutedColor,
    )
}

@Composable
private fun FetchSuccess(count: Int, onContinue: () -> Unit) {
    val flow = LocalFlowColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(flow.positive.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (count > 0) "Fetched $count transactions." else "Already up to date — nothing new to add.",
            style = MaterialTheme.typography.bodyMedium,
            color = flow.positive,
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("View my dashboard")
        }
    }
}

@Composable
private fun FetchError(message: String) {
    val flow = LocalFlowColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(flow.negative.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = flow.negative,
        )
    }
}
