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
     * synchronously on submit) or a failed first sync's [syncError] + [errorMessage]
     * (the raw reason/message, kept for continuity) alongside a UI-level
     * [connectError] classification, so the screen can show a user-friendly,
     * case-specific message and the user can fix and retry.
     */
    data class Form(
        val validationError: String? = null,
        val syncError: SyncErrorReason? = null,
        val errorMessage: String? = null,
        val connectError: ConnectError? = null,
    ) : ConnectBankUiState

    /** Credentials saved; the first sync is running. Mirrors [com.riseup.clone.data.sync.SyncState.Syncing]. */
    data object Syncing : ConnectBankUiState

    /** Connected and first sync done — the app routes to the dashboard. */
    data object Connected : ConnectBankUiState
}

/**
 * The user-facing classification of a failed connect attempt. Distilled from the
 * sync outcome ([SyncErrorReason] + message) into the handful of cases the connect
 * screen shows distinct copy for. Kept UI-side (not in the sync layer) because it
 * folds in the OTP case, which the sync layer surfaces only as a stable message
 * string ([com.riseup.clone.data.scraper.RemoteBankScraper.OTP_REQUIRED_MESSAGE])
 * rather than its own [SyncErrorReason].
 */
enum class ConnectError {
    /** The bank rejected the login (wrong id/password/code, or a locked account). */
    INVALID_CREDENTIALS,

    /**
     * The device-unlock-bound credential key couldn't be used because the device
     * wasn't unlocked recently enough (M2-8). The user should unlock and retry.
     */
    AUTH_REQUIRED,

    /**
     * The credential key was permanently invalidated (screen lock removed or
     * biometrics re-enrolled), clearing the saved login — re-enter it to reconnect.
     */
    KEY_INVALIDATED,

    /** The self-hosted backend couldn't be reached (down, off-VPN, TLS/pin failure). */
    NETWORK,

    /** The account needs OTP/2FA, which the unattended backend can't satisfy yet. */
    OTP_REQUIRED,

    /** Anything else — a generic, try-again fallback. */
    GENERIC,
}
