package com.riseup.clone.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remembers the single statement URL — the scraper's cloud upload link (reachable from
 * any network) or its LAN `serve` endpoint — so the user enters it ONCE and it prefills
 * (and auto-fetches) on every later visit.
 *
 * This is a plain, non-secret string — a public URL, not a credential — so a small
 * [SharedPreferences] entry is enough (see [PreferencesScraperUrlStore]). Behind an
 * interface so the connect-screen logic can be unit-tested on the JVM with
 * [InMemoryScraperUrlStore], mirroring [ConnectionStore]/[InMemoryConnectionStore].
 */
interface ScraperUrlStore {

    /** The saved scraper URL, or `null` if the user hasn't fetched before. */
    suspend fun savedUrl(): String?

    /** Remember [url] as the scraper endpoint (survives restarts). */
    suspend fun saveUrl(url: String)
}

/**
 * [ScraperUrlStore] backed by a small [SharedPreferences] file. I/O runs on
 * [Dispatchers.IO], consistent with the other on-disk stores.
 */
class PreferencesScraperUrlStore(context: Context) : ScraperUrlStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun savedUrl(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_URL, null)
    }

    override suspend fun saveUrl(url: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_URL, url).apply()
    }

    private companion object {
        const val PREFS_NAME = "scraper_endpoint"
        const val KEY_URL = "scraper_url"
    }
}

/** In-memory [ScraperUrlStore] for JVM tests — no Android, no disk. */
class InMemoryScraperUrlStore(initial: String? = null) : ScraperUrlStore {
    private var url: String? = initial
    override suspend fun savedUrl(): String? = url
    override suspend fun saveUrl(url: String) {
        this.url = url
    }
}
