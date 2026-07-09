package com.riseup.clone.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, observable [SyncState] the UI collects.
 *
 * Each [LedgerSyncer] owns its own state flow, but a syncer is built per run (see
 * [SyncGraph.buildSyncer]) and the background [LedgerSyncWorker] builds its own in
 * a separate context — so there is no single flow a screen can watch for "how did
 * the last foreground sync go?". This tiny singleton is that shared surface: the
 * connect-bank first sync and the dashboard's manual re-sync publish their progress
 * here (Syncing → Success/Error), and the dashboard reflects it as a banner and a
 * trigger to reload the ledger.
 *
 * Deliberately minimal (a single hot [StateFlow], no scope) — it holds only the
 * latest state, mirroring how [SyncState] is modelled as the UI's vocabulary.
 */
object AppSync {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** The latest foreground sync state. Republished by whoever runs a sync. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Publish [next] as the current sync state. */
    fun publish(next: SyncState) {
        _state.value = next
    }
}
