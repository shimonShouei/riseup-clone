package com.riseup.clone.data.sync

import android.content.Context
import com.riseup.clone.data.scraper.RemoteBankScraper
import com.riseup.clone.data.scraper.RemoteScraperConfig
import com.riseup.clone.data.security.KeystoreCredentialStore
import com.riseup.clone.domain.scraper.DateRange
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

/**
 * Production [BankConnector] backed by [RemoteBankScraper] — the live counterpart
 * to [CsvBankConnector]. It points [SyncGraph.scraperProvider] at a pinned-HTTPS
 * scraper for the self-hosted backend and runs one sync, reusing the exact same
 * mapper -> dedupe -> Room -> sync pipeline.
 *
 * This is the seam M2-6 wires the connect-Discount UI to: swap
 * [ConnectBankViewModel][com.riseup.clone.ui.connect.ConnectBankViewModel]'s
 * `connector` from [CsvBankConnector] to this, and supply the real
 * [RemoteScraperConfig] (tailnet URL + SPKI pins). [CsvBankConnector] is left
 * untouched and still works.
 *
 * ### Secrets
 * The backend bearer token is NOT hardcoded and NOT in [config] (which holds only
 * non-secret URL + public-key pins). It is read from the Android Keystore via
 * [tokenProvider], defaulting to [keystoreTokenProvider]. The connect flow / setup
 * is responsible for writing that token into the Keystore under
 * [BACKEND_TOKEN_KEY]; until it does, requests carry an empty bearer and the
 * backend answers 401 -> INVALID_CREDENTIALS (no silent success).
 *
 * @param config non-secret connection settings; defaults to the compile-safe
 *   [RemoteScraperConfig.PLACEHOLDER] so this is constructible today. M2-6 injects
 *   the real one.
 * @param tokenProvider yields the bearer token; blocking, called on the sync's IO
 *   thread. Injectable so tests supply a fake without touching the Keystore.
 */
class RemoteBankConnector(
    context: Context,
    private val config: RemoteScraperConfig = RemoteScraperConfig.PLACEHOLDER,
    private val tokenProvider: () -> String = keystoreTokenProvider(context),
) : BankConnector {

    private val appContext = context.applicationContext

    override fun registerProvider(institution: String) {
        // RemoteBankScraper is Discount-only today; the institution arg is accepted
        // for parity with the CsvBankConnector seam.
        SyncGraph.scraperProvider = { RemoteBankScraper.forDiscount(config, tokenProvider) }
    }

    override suspend fun runSync(institution: String): SyncOutcome {
        registerProvider(institution)
        AppSync.publish(SyncState.Syncing)

        val syncer = SyncGraph.buildSyncer(appContext) ?: run {
            val message = "No bank connected"
            AppSync.publish(SyncState.Error(SyncErrorReason.UNKNOWN, message))
            return SyncOutcome.Failed(SyncErrorReason.UNKNOWN, message)
        }

        // First import pulls a wide window; the periodic worker keeps the recent
        // window fresh. Stable mapper ids make the overlap harmless (rows dedupe).
        val outcome = syncer.sync(DateRange(FIRST_IMPORT_FROM, LocalDate.now()))
        AppSync.publish(syncer.state.value)

        if (outcome is SyncOutcome.Synced) {
            LedgerSyncWorker.schedulePeriodicSync(appContext)
        }
        return outcome
    }

    companion object {
        /**
         * Keystore key the backend bearer token is stored under. It reuses
         * [KeystoreCredentialStore] (token in the `password` field of a reserved
         * entry) so no second crypto path is introduced; the token is encrypted at
         * rest under the same non-exportable Keystore key as the bank credentials.
         */
        const val BACKEND_TOKEN_KEY = "__backend_bearer_token__"

        /** Import everything on the first sync; recurring passes use the 90-day window. */
        private val FIRST_IMPORT_FROM: LocalDate = LocalDate.of(2000, 1, 1)

        /**
         * Bearer-token provider backed by the Keystore. Blocking by design — it is
         * called from [RemoteBankScraper.scrape], already on `Dispatchers.IO`.
         * Returns an empty string when no token has been stored yet.
         */
        fun keystoreTokenProvider(context: Context): () -> String {
            val store = KeystoreCredentialStore(context.applicationContext)
            return { runBlocking { store.load(BACKEND_TOKEN_KEY)?.password.orEmpty() } }
        }
    }
}
