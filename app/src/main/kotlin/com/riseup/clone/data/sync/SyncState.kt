package com.riseup.clone.data.sync

import java.time.Instant

/**
 * Observable status of a ledger refresh, modelled as a small sealed set the
 * ViewModel layer collects to drive UI (a spinner, a reload signal). Mirrors how
 * [com.riseup.clone.ui.dashboard.DashboardUiState] and
 * [com.riseup.clone.domain.scraper.ScrapeResult] model their outcomes as sealed
 * types so every case is handled explicitly.
 *
 * There is no live bank sync anymore: real data enters via a CSV statement import
 * (see [com.riseup.clone.data.importer.StatementImporter]). The importer publishes
 * [Success] on [AppSync] so an already-open dashboard reloads its ledger. Import
 * *failures* are surfaced to the user through the import UI state, not here, so this
 * type deliberately has no error case.
 */
sealed interface SyncState {

    /** No refresh has happened yet this process. */
    data object Idle : SyncState

    /** A refresh/import is currently running. */
    data object Syncing : SyncState

    /** The last import completed; [lastSyncedAt] is when it finished. */
    data class Success(val lastSyncedAt: Instant) : SyncState
}
