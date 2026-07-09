package com.riseup.clone.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [BackendConfigStore] backed by a small [SharedPreferences] file. The backend
 * base URL is non-secret metadata (the *token* lives encrypted in
 * [com.riseup.clone.data.security.KeystoreCredentialStore]), so a plain prefs
 * entry is the smallest thing that survives a restart — matching how
 * [PreferencesConnectionStore] stores the connected institution.
 *
 * Reads are synchronous (an in-memory cache hit after the first load) so the
 * connector-selection lambda can build the remote connector without suspending.
 */
class PreferencesBackendConfigStore(context: Context) : BackendConfigStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun baseUrl(): String? = prefs.getString(KEY_BASE_URL, null)

    override fun setBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }

    private companion object {
        const val PREFS_NAME = "backend_config"
        const val KEY_BASE_URL = "backend_base_url"
    }
}
