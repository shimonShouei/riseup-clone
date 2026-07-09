package com.riseup.clone.ui.connect

import com.riseup.clone.data.sync.SyncErrorReason

/**
 * UI state for the connect-bank onboarding screen, modelled as a sealed set of
 * states the screen renders exhaustively — mirroring how
 * [com.riseup.clone.ui.dashboard.DashboardUiState] and
 * [com.riseup.clone.data.sync.SyncState] model their outcomes.
 *
 * The form field values (institution / username / password) are hoisted in the
 * composable, not here: this type carries only the *phase* of the flow plus the
 * errors the screen surfaces, so the ViewModel stays thin and testable.
 */
sealed interface ConnectBankUiState {

    /**
     * The form is editable. Carries a [validationError] (blank/invalid input, set
     * synchronously on submit) or a [syncError] with its [errorMessage] (a previous
     * attempt's failed first sync), so the user can fix and retry.
     */
    data class Form(
        val validationError: String? = null,
        val syncError: SyncErrorReason? = null,
        val errorMessage: String? = null,
    ) : ConnectBankUiState

    /** Credentials saved; the first sync is running. Mirrors [com.riseup.clone.data.sync.SyncState.Syncing]. */
    data object Syncing : ConnectBankUiState

    /** Connected and first sync done — the app routes to the dashboard. */
    data object Connected : ConnectBankUiState
}
