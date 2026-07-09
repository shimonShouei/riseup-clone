package com.riseup.clone.ui.connect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.riseup.clone.data.ConnectionStore
import com.riseup.clone.data.PreferencesConnectionStore
import com.riseup.clone.data.security.CredentialStore
import com.riseup.clone.data.security.KeystoreCredentialStore
import com.riseup.clone.data.sync.BankConnector
import com.riseup.clone.data.sync.CsvBankConnector
import com.riseup.clone.data.sync.SyncOutcome
import com.riseup.clone.domain.scraper.ScraperCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Decision logic for the connect-bank onboarding flow, deliberately expressed over
 * interfaces ([CredentialStore], [ConnectionStore], [BankConnector]) so every
 * branch is unit-testable on the JVM with the in-memory fakes — no Compose, Room,
 * or Keystore needed. The composable is a thin state-in / events-out shell.
 *
 * On submit: validate → save credentials → run the first sync → surface the
 * outcome. Only a *successful* first sync flips the persistent connected flag, so a
 * saved-but-unsynced attempt can't route the user past onboarding.
 *
 * [connected] is the app-level routing signal (see MainActivity): `null` while the
 * launch check is in flight, `true` → dashboard, `false` → show this screen. It is
 * derived from [ConnectionStore] so it persists across restarts; on a connected
 * cold start we also re-register the scraper provider (it lives in process memory).
 */
class ConnectBankViewModel(
    private val credentialStore: CredentialStore,
    private val connectionStore: ConnectionStore,
    private val connector: BankConnector,
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
                // manual syncs work after a cold start.
                connector.registerProvider(institution)
                _connected.value = true
            } else {
                _connected.value = false
            }
        }
    }

    /**
     * Attempt to connect [institution] with [username]/[password]. Blank fields are
     * rejected synchronously; otherwise credentials are saved and the first sync
     * runs, moving [uiState] Syncing → Connected / Form(error).
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

        viewModelScope.launch {
            _uiState.value = ConnectBankUiState.Syncing
            credentialStore.save(bank, ScraperCredentials(user, password))

            _uiState.value = when (val outcome = connector.runSync(bank)) {
                is SyncOutcome.Synced -> {
                    connectionStore.setConnected(bank)
                    _connected.value = true
                    ConnectBankUiState.Connected
                }
                is SyncOutcome.Retry ->
                    ConnectBankUiState.Form(syncError = outcome.reason, errorMessage = outcome.message)
                is SyncOutcome.Failed ->
                    ConnectBankUiState.Form(syncError = outcome.reason, errorMessage = outcome.message)
            }
        }
    }

    companion object {
        /** Builds a ViewModel wired to the real on-device stores + CSV connector. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConnectBankViewModel(
                        credentialStore = KeystoreCredentialStore(app),
                        connectionStore = PreferencesConnectionStore(app),
                        connector = CsvBankConnector(app),
                    ) as T
            }
        }
    }
}
