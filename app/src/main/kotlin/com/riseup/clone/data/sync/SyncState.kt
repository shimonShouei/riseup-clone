package com.riseup.clone.data.sync

import java.time.Instant

/**
 * Observable status of the background ledger sync, modelled as a sealed set of
 * states the ViewModel layer collects to drive UI (a spinner, a "last synced 5m
 * ago" line, an error banner). Mirrors how [com.riseup.clone.ui.dashboard.DashboardUiState]
 * and [com.riseup.clone.domain.scraper.ScrapeResult] model their outcomes as
 * sealed types so every case is handled explicitly.
 *
 * The orchestrator ([LedgerSyncer]) is the single writer: it moves [Idle] →
 * [Syncing] → [Success]/[Error] across one run. [Success.lastSyncedAt] is also
 * persisted (see [LastSyncedStore]) so the state can be re-published after a
 * process restart instead of resetting to [Idle].
 */
sealed interface SyncState {

    /** No sync has run yet this process, and none is in flight. */
    data object Idle : SyncState

    /** A sync is currently running. */
    data object Syncing : SyncState

    /** The last sync completed; [lastSyncedAt] is when it finished. */
    data class Success(val lastSyncedAt: Instant) : SyncState

    /** The last sync failed. [reason] lets the UI branch without string-matching. */
    data class Error(val reason: SyncErrorReason, val message: String) : SyncState
}

/**
 * Why a sync failed, at the granularity a user-facing surface cares about.
 * Folds the scraper's [com.riseup.clone.domain.scraper.FailureReason] plus the
 * "no credentials stored" case into one enum. See [LedgerSyncer] for how each
 * maps to a retry-vs-give-up decision.
 */
enum class SyncErrorReason {
    /** No credentials are stored for the institution — the bank isn't connected. */
    NO_CREDENTIALS,

    /** The bank rejected the stored login. Needs the user to re-authenticate. */
    INVALID_CREDENTIALS,

    /** Couldn't reach the bank. Transient — worth retrying. */
    NETWORK,

    /** The bank's payload was malformed. */
    PARSE_ERROR,

    /** Anything else. Treated as transient. */
    UNKNOWN,
}
