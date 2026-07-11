package com.riseup.clone.ui.connect

/**
 * UI state for an import action (file import or load-sample), modelled as a small
 * sealed set of phases the screen renders exhaustively — consistent with
 * [com.riseup.clone.ui.dashboard.DashboardUiState].
 *
 * The connect screen tracks the file-import and load-sample actions as two
 * independent [ImportUiState] flows, since either can be running on its own.
 */
sealed interface ImportUiState {

    /** No import in progress; the action is offered. */
    data object Idle : ImportUiState

    /** A picked file (or the sample) is being read and parsed. */
    data object Importing : ImportUiState

    /** The statement was imported; [count] rows were newly stored (0 on a re-import). */
    data class Success(val count: Int) : ImportUiState

    /** The import failed (cancelled reads are a no-op, not an error) with a user-facing [message]. */
    data class Error(val message: String) : ImportUiState
}
