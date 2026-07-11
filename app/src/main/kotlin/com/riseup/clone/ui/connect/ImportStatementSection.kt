package com.riseup.clone.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.riseup.clone.ui.theme.LocalFlowColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MIME types offered to the Storage Access Framework picker. Real CSV files are
 * often mislabelled by providers (as `text/plain`, `application/octet-stream`, or
 * nothing at all), so we advertise the CSV types plus an all-files fallback so the
 * user can always reach the file; parsing — not the picker — is the real validation.
 */
private val CSV_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "*/*",
)

/**
 * The "Import statement (CSV)" action plus its status surface. Self-contained: it
 * owns the SAF file picker ([ActivityResultContracts.OpenDocument]), reads the
 * picked `Uri` off the main thread ([Dispatchers.IO], UTF-8), and hands the decoded
 * text to [onCsvText]. Cancelling the picker is a no-op; an unreadable file routes
 * to [onReadError]. Parsing outcomes arrive back as [importState].
 *
 * State-in / events-out, matching [ConnectBankScreen]: all decisions live in
 * [ConnectBankViewModel]; this composable only drives the picker and renders phase.
 */
@Composable
fun ImportStatementSection(
    importState: ImportUiState,
    enabled: Boolean,
    onCsvText: (String) -> Unit,
    onReadError: (String) -> Unit,
    onContinue: () -> Unit,
    mutedColor: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importing = importState is ImportUiState.Importing

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // Null Uri == the user cancelled the picker: a no-op, not an error.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) onReadError("Couldn't read the selected file. Try another export.")
            else onCsvText(text)
        }
    }

    OutlinedButton(
        onClick = { launcher.launch(CSV_MIME_TYPES) },
        enabled = enabled && !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (importing) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Importing…")
        } else {
            Text("Import statement (CSV)")
        }
    }

    when (val s = importState) {
        is ImportUiState.Success -> ImportSuccess(count = s.count, onContinue = onContinue)
        is ImportUiState.Error -> ImportError(message = s.message)
        else -> Unit
    }

    Text(
        "Import a CSV your bank exported, or one produced by the statement scraper.",
        style = MaterialTheme.typography.labelSmall,
        color = mutedColor,
    )
}

@Composable
private fun ImportSuccess(count: Int, onContinue: () -> Unit) {
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
            if (count > 0) "Imported $count transactions." else "Statement already imported — nothing new to add.",
            style = MaterialTheme.typography.bodyMedium,
            color = flow.positive,
        )
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("View my dashboard")
        }
    }
}

@Composable
private fun ImportError(message: String) {
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
            "Couldn't import that file. $message",
            style = MaterialTheme.typography.bodySmall,
            color = flow.negative,
        )
    }
}
