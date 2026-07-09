package com.riseup.clone.ui.connect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.riseup.clone.data.BackendConfigStore
import com.riseup.clone.data.ConnectionStore
import com.riseup.clone.data.PreferencesBackendConfigStore
import com.riseup.clone.data.PreferencesConnectionStore
import com.riseup.clone.data.scraper.RemoteBankScraper
import com.riseup.clone.data.scraper.RemoteScraperConfig
import com.riseup.clone.data.security.CredentialStore
import com.riseup.clone.data.security.KeystoreCredentialStore
import com.riseup.clone.data.sync.BankConnector
import com.riseup.clone.data.sync.CsvBankConnector
import com.riseup.clone.data.sync.RemoteBankConnector
import com.riseup.clone.data.sync.SyncErrorReason
import com.riseup.clone.data.sync.SyncOutcome
import com.riseup.clone.domain.scraper.ScraperCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Decision logic for the connect-bank onboarding flow, deliberately expressed over
 * interfaces ([CredentialStore], [ConnectionStore], [BackendConfigStore], and a
 * [BankConnector] selector) so every branch is unit-testable on the JVM with the
 * in-memory fakes — no Compose, Room, or Keystore needed. The composable is a thin
 * state-in / events-out shell.
 *
 * On submit: validate → save credentials (and, for the remote path, the backend
 * URL + bearer token) → run the first sync through the connector chosen for the
 * institution → surface the outcome. Only a *successful* first sync flips the
 * persistent connected flag, so a saved-but-unsynced attempt can't route the user
 * past onboarding.
 *
 * ### Two connect paths
 * - **Bank Discount** connects through the live [RemoteBankConnector] (self-hosted
 *   backend, pinned HTTPS): three real login fields (`id` / `password` / `num`)
 *   plus a backend URL and bearer token.
 * - **Every other institution** keeps the existing sample/CSV path via
 *   [CsvBankConnector].
 *
 * The connector is picked by institution behind the [connectorFor] seam, so this
 * class stays interface-based and a fake connector drops in for tests.
 *
 * [connected] is the app-level routing signal (see MainActivity): `null` while the
 * launch check is in flight, `true` → dashboard, `false` → show this screen. It is
 * derived from [ConnectionStore] so it persists across restarts; on a connected
 * cold start we also re-register the scraper provider (it lives in process memory).
 */
class ConnectBankViewModel(
    private val credentialStore: CredentialStore,
    private val connectionStore: ConnectionStore,
    private val backendConfig: BackendConfigStore,
    private val connectorFor: (institution: String) -> BankConnector,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ConnectBankUiState>(ConnectBankUiState.Form())
    val uiState: StateFlow<ConnectBankUiState> = _uiState.asStateFlow()

    /** null = still determining at launch, true = go to dashboard, false = show connect form. */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    init {
        viewModelScope.launch {
            val institution = connectionStore.connectedInstitution()
            if (institution != null) {
                // Re-establish the in-memory scraper provider so background and
                // manual syncs work after a cold start — using the same connector
                // (CSV vs. remote) this institution was connected through.
                connectorFor(institution).registerProvider(institution)
                _connected.value = true
            } else {
                _connected.value = false
            }
        }
    }

    /**
     * Attempt to connect [institution] with [username]/[password] via the sample/CSV
     * path (every institution except Bank Discount). Blank fields are rejected
     * synchronously; otherwise credentials are saved and the first sync runs.
     */
    fun submit(institution: String, username: String, password: String) {
        val bank = institution.trim()
        val user = username.trim()
        val validationError = when {
            bank.isEmpty() -> "Choose your bank"
            user.isEmpty() -> "Enter your username"
            password.isEmpty() -> "Enter your password"
            else -> null
        }
        if (validationError != null) {
            _uiState.value = ConnectBankUiState.Form(validationError = validationError)
            return
        }

        runConnect(bank, ScraperCredentials(user, password))
    }

    /**
     * Attempt to connect **Bank Discount** through the remote backend. Captures the
     * bank's three real login fields ([id] = ת״ז/user ID, [password], [num] =
     * קוד משתמש/user code) plus the [backendUrl] (non-secret) and [backendToken]
     * (secret). All are validated non-blank before anything is saved or synced.
     *
     * Storage (all reused, no new crypto path):
     * - Credentials → [CredentialStore] under the institution as
     *   `ScraperCredentials(username = id, password = password, extra = {num})`,
     *   exactly the shape [RemoteBankScraper] reads.
     * - Backend token → the Keystore-backed [CredentialStore] under the reserved
     *   [RemoteBankConnector.BACKEND_TOKEN_KEY] (never plain prefs / BuildConfig).
     * - Backend URL → the non-secret [BackendConfigStore].
     */
    fun submitDiscount(
        id: String,
        password: String,
        num: String,
        backendUrl: String,
        backendToken: String,
    ) {
        val cleanId = id.trim()
        val cleanNum = num.trim()
        val cleanUrl = backendUrl.trim()
        val validationError = when {
            cleanId.isEmpty() -> "Enter your ת\"ז (user ID)"
            password.isEmpty() -> "Enter your password"
            cleanNum.isEmpty() -> "Enter your קוד משתמש (user code)"
            cleanUrl.isEmpty() -> "Enter your backend URL"
            // R3: the backend MUST be reached over HTTPS. Reject a cleartext URL up
            // front — over http:// there is no TLS, so certificate pinning (R13) is
            // never applied and credentials would be sent in the clear.
            !cleanUrl.startsWith("https://", ignoreCase = true) ->
                "Backend URL must start with https://"
            backendToken.isEmpty() -> "Enter your backend token"
            else -> null
        }
        if (validationError != null) {
            _uiState.value = ConnectBankUiState.Form(validationError = validationError)
            return
        }

        val credentials = ScraperCredentials(
            username = cleanId,
            password = password,
            extra = mapOf(RemoteBankScraper.NUM_KEY to cleanNum),
        )
        runConnect(RemoteBankScraper.DISCOUNT_INSTITUTION, credentials) {
            // Persist the backend URL first so the connector selected below reads it.
            backendConfig.setBaseUrl(cleanUrl)
            // The backend token and bank credentials are written to the
            // KeystoreCredentialStore, whose key is now bound to a recent device
            // unlock (M2-8; backend/SECURITY.md §3 refinement 2). Saving here runs in
            // the foreground right after the user unlocked to reach this screen, so
            // the encrypt succeeds within the key's auth-validity window.
            credentialStore.save(
                RemoteBankConnector.BACKEND_TOKEN_KEY,
                ScraperCredentials(username = "", password = backendToken),
            )
        }
    }

    /**
     * Shared connect pipeline: move to Syncing, run any [preSync] side effects
     * (remote path saves URL + token), save the bank credentials, run the first
     * sync via the connector chosen for [institution], and surface the outcome.
     */
    private fun runConnect(
        institution: String,
        credentials: ScraperCredentials,
        preSync: suspend () -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.value = ConnectBankUiState.Syncing
            preSync()
            credentialStore.save(institution, credentials)

            _uiState.value = when (val outcome = connectorFor(institution).runSync(institution)) {
                is SyncOutcome.Synced -> {
                    connectionStore.setConnected(institution)
                    _connected.value = true
                    ConnectBankUiState.Connected
                }
                is SyncOutcome.Retry -> errorState(outcome.reason, outcome.message)
                is SyncOutcome.Failed -> errorState(outcome.reason, outcome.message)
            }
        }
    }

    /**
     * Classify a failed sync into the user-facing [ConnectError] the screen renders.
     *
     * OTP/2FA maps to the dedicated [SyncErrorReason.OTP_REQUIRED] (M2-5 maps the
     * backend's 409 to `Failure(OTP_REQUIRED, …)`, a permanent failure). We surface a
     * distinct OTP state and do NOT re-run the sync: `runConnect` is a one-shot user
     * action, so simply returning the state (never re-invoking sync) is what "don't
     * retry-loop on OTP" means (R16). The message check is a defensive fallback.
     */
    private fun errorState(reason: SyncErrorReason, message: String): ConnectBankUiState.Form {
        val connectError = when {
            reason == SyncErrorReason.OTP_REQUIRED ||
                message == RemoteBankScraper.OTP_REQUIRED_MESSAGE -> ConnectError.OTP_REQUIRED
            reason == SyncErrorReason.INVALID_CREDENTIALS -> ConnectError.INVALID_CREDENTIALS
            // Unlock-bound key states (M2-8): "unlock and retry" vs. "re-enter creds".
            reason == SyncErrorReason.AUTH_REQUIRED -> ConnectError.AUTH_REQUIRED
            reason == SyncErrorReason.KEY_INVALIDATED -> ConnectError.KEY_INVALIDATED
            reason == SyncErrorReason.NETWORK -> ConnectError.NETWORK
            else -> ConnectError.GENERIC
        }
        return ConnectBankUiState.Form(
            syncError = reason,
            errorMessage = message,
            connectError = connectError,
        )
    }

    companion object {
        /** Builds a ViewModel wired to the real on-device stores + connector selection. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            val backendConfig = PreferencesBackendConfigStore(app)
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConnectBankViewModel(
                        credentialStore = KeystoreCredentialStore(app),
                        connectionStore = PreferencesConnectionStore(app),
                        backendConfig = backendConfig,
                        connectorFor = { institution -> connectorForInstitution(app, backendConfig, institution) },
                    ) as T
            }
        }

        /**
         * Route Bank Discount through the live [RemoteBankConnector] (with the
         * user-provided base URL layered onto the compiled-in, non-secret pins) and
         * every other institution through the sample/CSV [CsvBankConnector]. The
         * bearer token is NOT passed here — [RemoteBankConnector] reads it from the
         * Keystore under [RemoteBankConnector.BACKEND_TOKEN_KEY].
         */
        private fun connectorForInstitution(
            context: Context,
            backendConfig: BackendConfigStore,
            institution: String,
        ): BankConnector =
            if (institution == RemoteBankScraper.DISCOUNT_INSTITUTION) {
                // Pins stay fixed in RemoteScraperConfig (real values dropped in with
                // the cert); only the non-secret base URL is user-supplied.
                val baseUrl = backendConfig.baseUrl() ?: RemoteScraperConfig.PLACEHOLDER.baseUrl
                RemoteBankConnector(context, RemoteScraperConfig.PLACEHOLDER.copy(baseUrl = baseUrl))
            } else {
                CsvBankConnector(context)
            }
    }
}
