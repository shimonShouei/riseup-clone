package com.riseup.clone.data.sync

import java.time.Instant

/**
 * Tiny persistence seam for a single value: when the ledger last synced
 * successfully. Split behind an interface for the same reason the credential
 * store is — the production impl ([PreferencesLastSyncedStore]) touches Android
 * (SharedPreferences), while [InMemoryLastSyncedStore] lets the orchestrator's
 * "lastSyncedAt survives / is republished" behaviour be unit-tested on the JVM.
 *
 * A whole DataStore/Room table would be overkill for one timestamp, so this is
 * intentionally the smallest thing that outlives the process.
 */
interface LastSyncedStore {

    /** The last successful sync time, or `null` if none has ever completed. */
    suspend fun read(): Instant?

    /** Record [at] as the most recent successful sync time. */
    suspend fun write(at: Instant)
}
