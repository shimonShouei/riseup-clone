package com.riseup.clone.data.sync

import android.content.Context
import com.riseup.clone.domain.scraper.BankScraper
import com.riseup.clone.domain.scraper.CsvBankScraper
import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.SampleStatementCsv
import com.riseup.clone.domain.scraper.ScrapedAccount
import java.time.LocalDate

/**
 * The seam the connect-bank flow drives to (a) register *which* [BankScraper] the
 * app should sync and (b) run a sync now. Kept as an interface so the ViewModel's
 * decision logic depends only on this contract — a fake stands in for JVM unit
 * tests, and a real HTTP-scraper connector can replace [CsvBankConnector] later
 * without touching the ViewModel.
 */
interface BankConnector {

    /**
     * Point [SyncGraph.scraperProvider] at the scraper for [institution]. Must be
     * called on every cold start for a connected bank, because the provider lives
     * in process memory and is reset when the process dies.
     */
    fun registerProvider(institution: String)

    /**
     * Register the provider for [institution] and run one foreground sync now,
     * publishing progress to [AppSync]. Returns the [SyncOutcome] so the caller can
     * branch on success vs. failure.
     */
    suspend fun runSync(institution: String): SyncOutcome
}

/**
 * Production [BankConnector] backed by [CsvBankScraper].
 *
 * With only a CSV provider available in M1, "connect a bank" is honestly a
 * *statement import* + credential-capture flow: it takes a realistic multi-month
 * sample statement ([SampleStatementCsv]) and runs it through the exact same seam a
 * live scraper would (raw CSV → [com.riseup.clone.domain.scraper.ScrapeMapper] →
 * Room). Dropping in a real HTTP scraper later is just a different [BankScraper]
 * behind this same connector.
 */
class CsvBankConnector(context: Context) : BankConnector {

    private val appContext = context.applicationContext

    override fun registerProvider(institution: String) {
        SyncGraph.scraperProvider = { buildScraper(institution) }
    }

    override suspend fun runSync(institution: String): SyncOutcome {
        registerProvider(institution)
        AppSync.publish(SyncState.Syncing)

        val syncer = SyncGraph.buildSyncer(appContext) ?: run {
            val message = "No bank connected"
            AppSync.publish(SyncState.Error(SyncErrorReason.UNKNOWN, message))
            return SyncOutcome.Failed(SyncErrorReason.UNKNOWN, message)
        }

        // First import pulls the whole statement (wide window); the periodic worker
        // then keeps the recent window fresh. The mapper's stable ids make the
        // overlap harmless (repeat rows dedupe).
        val outcome = syncer.sync(DateRange(FIRST_IMPORT_FROM, LocalDate.now()))
        AppSync.publish(syncer.state.value)

        if (outcome is SyncOutcome.Synced) {
            LedgerSyncWorker.schedulePeriodicSync(appContext)
        }
        return outcome
    }

    private fun buildScraper(institution: String): BankScraper {
        val account = ScrapedAccount(
            externalId = "$institution-checking",
            label = "Checking",
            institution = institution,
            rawType = "checking",
        )
        return CsvBankScraper(institution, account) { statementCsv() }
    }

    /**
     * Yields the raw CSV text to import. Until a live scraper exists, this is a
     * realistic multi-month statement synthesized from [SampleStatementCsv] (ending
     * near today, so the forecast has genuine history to work with). A real build
     * would instead return a user-picked download or a live fetch; throwing here
     * would surface as [com.riseup.clone.domain.scraper.FailureReason.NETWORK],
     * exactly as a live source failing to load would.
     */
    private fun statementCsv(): String = SampleStatementCsv.forToday(LocalDate.now())

    private companion object {
        /** Import everything on the first sync; recurring passes use the 90-day window. */
        val FIRST_IMPORT_FROM: LocalDate = LocalDate.of(2000, 1, 1)
    }
}
