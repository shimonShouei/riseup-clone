package com.riseup.clone.data.sync

import android.content.Context
import com.riseup.clone.data.local.LedgerDatabase
import com.riseup.clone.data.security.KeystoreCredentialStore
import com.riseup.clone.domain.scraper.BankScraper

/**
 * The one place the background worker gets its dependencies. A deliberately tiny
 * manual-DI seam (the app has no DI framework yet): it assembles a [LedgerSyncer]
 * from the on-device stores, leaving only the *which bank* choice pluggable via
 * [scraperProvider].
 *
 * That provider is set by the connect-bank flow (M1-6). Until a bank is
 * connected it stays `null`, so [buildSyncer] returns `null` and the worker
 * treats a run as a no-op — there is genuinely nothing to sync. Keeping the wiring
 * here (rather than inline in the worker) also lets tests / M1-6 swap in a fake
 * provider without touching the worker.
 */
object SyncGraph {

    /**
     * Supplies the [BankScraper] to sync, given a context, or `null` if no bank is
     * connected. Assigned during app startup / connect-bank (M1-6).
     */
    @Volatile
    var scraperProvider: ((Context) -> BankScraper?)? = null

    /**
     * Build a syncer wired to the real on-device stores, or `null` when no
     * [scraperProvider] has produced a scraper (no bank connected).
     */
    fun buildSyncer(context: Context): LedgerSyncer? {
        val scraper = scraperProvider?.invoke(context) ?: return null
        val dao = LedgerDatabase.build(context).ledgerDao()
        return LedgerSyncer(
            scraper = scraper,
            credentials = KeystoreCredentialStore(context),
            store = RoomSyncLedgerStore(dao),
            lastSynced = PreferencesLastSyncedStore(context),
        )
    }
}
