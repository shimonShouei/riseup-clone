package com.riseup.clone.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, observable [SyncState] the UI collects.
 *
 * Real data enters via a CSV statement import, which builds a fresh
 * [com.riseup.clone.data.importer.LedgerStatementImporter] per run — so there is no
 * single flow a screen can watch for "did the last import land new data?". This tiny
 * singleton is that shared surface: the importer publishes [SyncState.Success] here,
 * and an already-open dashboard reflects it by reloading the ledger.
 *
 * Deliberately minimal (a single hot [StateFlow], no scope) — it holds only the
 * latest state, mirroring how [SyncState] is modelled as the UI's vocabulary.
 */
object AppSync {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** The latest import state. Republished by whoever runs an import. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Publish [next] as the current sync state. */
    fun publish(next: SyncState) {
        _state.value = next
    }
}
