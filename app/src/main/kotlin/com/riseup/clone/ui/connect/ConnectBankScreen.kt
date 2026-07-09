package com.riseup.clone.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riseup.clone.ui.theme.LocalFlowColors

/** Banks the CSV connector can import a statement for. */
private val SUPPORTED_BANKS = listOf("Bank Leumi", "Bank Hapoalim", "Discount Bank", "Mizrahi-Tefahot")

/**
 * Connect-bank onboarding: choose an institution, enter credentials, and trigger
 * the first sync. A thin state-in / events-out shell — all decisions live in
 * [ConnectBankViewModel]. Field values are hoisted here; the ViewModel owns only
 * the flow phase ([ConnectBankUiState]).
 *
 * Honest M1 copy: with only a CSV provider wired, connecting imports your account
 * statement rather than driving a live bank login. The interface is what matters —
 * a live scraper drops in behind it later.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectBankScreen(viewModel: ConnectBankViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val flow = LocalFlowColors.current

    var institution by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val syncing = state is ConnectBankUiState.Syncing
    val form = state as? ConnectBankUiState.Form

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
            "Connect your bank",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "We import your account statement to forecast your cash flow. " +
                "Your credentials are encrypted on-device and never leave it.",
            style = MaterialTheme.typography.bodyMedium,
            color = flow.mutedText,
        )

        Spacer(Modifier.height(4.dp))
        Text("Your bank", style = MaterialTheme.typography.labelLarge, color = flow.mutedText)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUPPORTED_BANKS.forEach { bank ->
                FilterChip(
                    selected = institution == bank,
                    onClick = { institution = bank },
                    enabled = !syncing,
                    label = { Text(bank) },
                )
            }
        }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !syncing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !syncing,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Validation / sync error surface.
        val errorText = form?.validationError ?: form?.errorMessage
        if (errorText != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(flow.negative.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(errorText, style = MaterialTheme.typography.bodySmall, color = flow.negative)
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { viewModel.submit(institution, username, password) },
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(0.dp))
                Text("  Connecting…")
            } else {
                Text("Connect & sync")
            }
        }

        Text(
            "M1 imports a bundled sample statement (CSV) through the same sync path a " +
                "live bank connection will use.",
            style = MaterialTheme.typography.labelSmall,
            color = flow.mutedText,
        )
    }
}
