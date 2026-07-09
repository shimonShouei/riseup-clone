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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riseup.clone.data.scraper.RemoteBankScraper
import com.riseup.clone.ui.theme.LocalFlowColors

/** The institution routed through the live remote backend (M2). Others use the CSV path. */
private val DISCOUNT = RemoteBankScraper.DISCOUNT_INSTITUTION

/** Banks offered in the picker. [DISCOUNT] connects live; the rest import a sample statement. */
private val SUPPORTED_BANKS = listOf("Bank Leumi", "Bank Hapoalim", DISCOUNT, "Mizrahi-Tefahot")

/**
 * Connect-bank onboarding: choose an institution, enter credentials, and trigger
 * the first sync. A thin state-in / events-out shell — all decisions live in
 * [ConnectBankViewModel]. Field values are hoisted here; the ViewModel owns only
 * the flow phase ([ConnectBankUiState]).
 *
 * Two shapes depending on the chosen bank:
 * - **Bank Discount** connects live through the self-hosted backend: three real
 *   login fields (ת״ז / password / קוד משתמש) plus a clearly-separated "Backend"
 *   section for the base URL and the (masked) bearer token.
 * - **Every other bank** keeps the M1 sample/CSV path (username + password), which
 *   imports a bundled statement through the same sync seam a live login uses.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectBankScreen(viewModel: ConnectBankViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val flow = LocalFlowColors.current

    var institution by rememberSaveable { mutableStateOf("") }

    // CSV path fields.
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Discount (remote) path fields.
    var discountId by rememberSaveable { mutableStateOf("") }
    var discountPassword by rememberSaveable { mutableStateOf("") }
    var discountNum by rememberSaveable { mutableStateOf("") }
    var backendUrl by rememberSaveable { mutableStateOf("") }
    var backendToken by rememberSaveable { mutableStateOf("") }

    val syncing = state is ConnectBankUiState.Syncing
    val form = state as? ConnectBankUiState.Form
    val isDiscount = institution == DISCOUNT

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

        if (isDiscount) {
            DiscountFields(
                id = discountId,
                onIdChange = { discountId = it },
                password = discountPassword,
                onPasswordChange = { discountPassword = it },
                num = discountNum,
                onNumChange = { discountNum = it },
                backendUrl = backendUrl,
                onBackendUrlChange = { backendUrl = it },
                backendToken = backendToken,
                onBackendTokenChange = { backendToken = it },
                enabled = !syncing,
                mutedColor = flow.mutedText,
            )
        } else {
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
        }

        // Validation / sync error surface — friendly, case-specific copy.
        val errorText = form?.validationError
            ?: form?.connectError?.let { connectErrorMessage(it) }
            ?: form?.errorMessage
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
            onClick = {
                if (isDiscount) {
                    viewModel.submitDiscount(
                        id = discountId,
                        password = discountPassword,
                        num = discountNum,
                        backendUrl = backendUrl,
                        backendToken = backendToken,
                    )
                } else {
                    viewModel.submit(institution, username, password)
                }
            },
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
            if (isDiscount) {
                "Bank Discount connects live through your self-hosted backend over a " +
                    "pinned connection. Your login and backend token stay encrypted on-device."
            } else {
                "This bank imports a bundled sample statement (CSV) through the same sync " +
                    "path a live bank connection uses."
            },
            style = MaterialTheme.typography.labelSmall,
            color = flow.mutedText,
        )
    }
}

/**
 * Bank Discount's real login fields plus the clearly-separated "Backend" section.
 * The bearer token is masked ([PasswordVisualTransformation]) and, like the
 * password, is never logged.
 */
@Composable
private fun DiscountFields(
    id: String,
    onIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    num: String,
    onNumChange: (String) -> Unit,
    backendUrl: String,
    onBackendUrlChange: (String) -> Unit,
    backendToken: String,
    onBackendTokenChange: (String) -> Unit,
    enabled: Boolean,
    mutedColor: androidx.compose.ui.graphics.Color,
) {
    OutlinedTextField(
        value = id,
        onValueChange = onIdChange,
        label = { Text("ת״ז (user ID)") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("סיסמה (password)") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = num,
        onValueChange = onNumChange,
        label = { Text("קוד משתמש (user code)") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(6.dp))
    Text("Backend", style = MaterialTheme.typography.labelLarge, color = mutedColor)
    Text(
        "The self-hosted scrape backend on your private network.",
        style = MaterialTheme.typography.labelSmall,
        color = mutedColor,
    )
    OutlinedTextField(
        value = backendUrl,
        onValueChange = onBackendUrlChange,
        label = { Text("Backend URL (https://…:8443)") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = backendToken,
        onValueChange = onBackendTokenChange,
        label = { Text("Backend token") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** User-friendly copy for each failed-connect classification. */
private fun connectErrorMessage(error: ConnectError): String = when (error) {
    ConnectError.INVALID_CREDENTIALS ->
        "Those login details were rejected. Double-check your ID, password, and user code."
    ConnectError.AUTH_REQUIRED ->
        "Unlock your device, then tap Connect & sync again."
    ConnectError.KEY_INVALIDATED ->
        "Your device lock or biometrics changed, so your saved login was cleared. Enter it again to reconnect."
    ConnectError.NETWORK ->
        "Can't reach your backend — is it running and are you on the VPN?"
    ConnectError.OTP_REQUIRED ->
        "This account needs 2FA, which isn't supported yet."
    ConnectError.GENERIC ->
        "Something went wrong connecting. Please try again."
}
