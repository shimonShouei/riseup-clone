package com.riseup.clone.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ConnectionStore] backed by a small [SharedPreferences] file. The connected
 * institution name is public metadata (not a secret — the *credentials* live
 * encrypted in [com.riseup.clone.data.security.KeystoreCredentialStore]), so a
 * plain prefs entry is the smallest thing that survives a restart, matching how
 * [com.riseup.clone.data.sync.PreferencesLastSyncedStore] stores its timestamp.
 *
 * I/O runs on [Dispatchers.IO], consistent with the other on-disk stores.
 */
class PreferencesConnectionStore(context: Context) : ConnectionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun connectedInstitution(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_INSTITUTION, null)
    }

    override suspend fun setConnected(institution: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_INSTITUTION, institution).apply()
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_INSTITUTION).apply()
    }

    private companion object {
        const val PREFS_NAME = "bank_connection"
        const val KEY_INSTITUTION = "connected_institution"
    }
}
