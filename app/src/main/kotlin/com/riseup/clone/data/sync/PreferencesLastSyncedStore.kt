package com.riseup.clone.data.sync

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [LastSyncedStore] backed by a small [SharedPreferences] file. The timestamp is
 * public metadata (not a secret), so a plain prefs entry is the smallest thing
 * that survives a restart — no DataStore dependency, no Room migration.
 *
 * I/O runs on [Dispatchers.IO], consistent with the other on-disk stores.
 */
class PreferencesLastSyncedStore(context: Context) : LastSyncedStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun read(): Instant? = withContext(Dispatchers.IO) {
        // -1 sentinel: SharedPreferences has no "absent" for a long primitive.
        prefs.getLong(KEY_LAST_SYNCED_EPOCH_MS, -1L)
            .takeIf { it >= 0L }
            ?.let(Instant::ofEpochMilli)
    }

    override suspend fun write(at: Instant): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_LAST_SYNCED_EPOCH_MS, at.toEpochMilli()).apply()
    }

    private companion object {
        const val PREFS_NAME = "ledger_sync"
        const val KEY_LAST_SYNCED_EPOCH_MS = "last_synced_epoch_ms"
    }
}
