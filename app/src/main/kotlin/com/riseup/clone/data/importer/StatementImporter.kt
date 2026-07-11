package com.riseup.clone.data.importer

import com.riseup.clone.data.sync.SyncLedgerStore
import com.riseup.clone.domain.scraper.CsvBankScraper
import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.ScrapeMapper
import com.riseup.clone.domain.scraper.ScrapeResult
import com.riseup.clone.domain.scraper.ScrapedAccount
import com.riseup.clone.domain.scraper.ScraperCredentials
import java.time.LocalDate

/**
 * Outcome of importing one CSV statement, in the vocabulary the UI needs.
 * Deliberately tiny — either a count of newly stored rows or a user-facing failure
 * message — mirroring how [com.riseup.clone.ui.dashboard.DashboardUiState] models its
 * outcomes as a small sealed type.
 */
sealed interface ImportResult {

    /** The statement parsed; [newTransactions] rows were newly stored (0 on a re-import). */
    data class Imported(val newTransactions: Int) : ImportResult

    /** The statement could not be imported (empty file, malformed CSV, bad date/amount). */
    data class Failed(val message: String) : ImportResult
}

/**
 * The import use-case seam: turn raw statement CSV text into stored ledger rows,
 * reusing the exact scrape pipeline a live provider uses ([CsvBankScraper] →
 * [ScrapeMapper] → [SyncLedgerStore]). Kept framework-free (no Android, no Room —
 * only the [SyncLedgerStore] interface) so it is unit-testable on the JVM against
 * the same in-memory fake the sync orchestrator's tests use.
 *
 * The production implementation is [LedgerStatementImporter]; the UI wires it to
 * the Room store via [com.riseup.clone.data.sync.RoomSyncLedgerStore].
 */
interface StatementImporter {

    /**
     * Parse [csvText] as a statement for [institution]/[accountLabel] and persist
     * the transactions. Idempotent by the mapper's stable transaction id, so
     * re-importing the same file adds nothing (its [ImportResult.Imported.newTransactions]
     * is 0). Never throws for expected problems — a malformed/empty CSV comes back
     * as [ImportResult.Failed].
     */
    suspend fun import(
        csvText: String,
        institution: String,
        accountLabel: String = "Checking",
    ): ImportResult
}

/**
 * Production [StatementImporter]. Runs the CSV through [CsvBankScraper] (which
 * ignores credentials — a dummy [ScraperCredentials] keeps the seam honest),
 * maps the raw DTOs with [ScrapeMapper], dedupes against what's already stored by
 * stable id, and writes the new rows plus a refreshed balance through [store].
 *
 * This maps → dedupes → applies through the same [SyncLedgerStore] seam the Room
 * ledger is written by — no credentials, no networking, no worker machinery: a file
 * import has none of those concerns.
 */
class LedgerStatementImporter(
    private val store: SyncLedgerStore,
) : StatementImporter {

    override suspend fun import(
        csvText: String,
        institution: String,
        accountLabel: String,
    ): ImportResult {
        val inst = institution.trim().ifEmpty { DEFAULT_INSTITUTION }
        val label = accountLabel.trim().ifEmpty { DEFAULT_LABEL }
        val account = ScrapedAccount(
            externalId = externalId(inst, label),
            label = label,
            institution = inst,
            rawType = "checking",
        )
        // CsvBankScraper ignores credentials; a dummy keeps the interface honest.
        val scraper = CsvBankScraper(inst, account) { csvText }
        return when (val result = scraper.scrape(ScraperCredentials("", ""), FULL_RANGE)) {
            is ScrapeResult.Failure -> ImportResult.Failed(result.message)
            is ScrapeResult.Success -> persist(result)
        }
    }

    /** Map + dedupe + write, exactly as the sync orchestrator does on a scrape. */
    private suspend fun persist(success: ScrapeResult.Success): ImportResult {
        val mapped = ScrapeMapper.map(success)
        val existing = store.existingTransactionIds()
        // Dedupe by the mapper's stable id: keep only rows we haven't stored.
        val fresh = mapped.transactions.filterNot { it.id in existing }
        val newBalance = store.currentBalance() + fresh.sumOf { it.amount }
        store.applySync(accounts = mapped.accounts, newTransactions = fresh, newBalance = newBalance)
        return ImportResult.Imported(fresh.size)
    }

    /**
     * A stable per-account id derived from the institution + account label, so
     * re-importing the same statement under the same labels lands on the same
     * account (and therefore the same, deduped, transaction ids).
     */
    private fun externalId(institution: String, label: String): String =
        "import:" + "$institution:$label".lowercase().replace(Regex("\\s+"), "-")

    private companion object {
        const val DEFAULT_INSTITUTION = "Imported statement"
        const val DEFAULT_LABEL = "Checking"

        /**
         * A statement carries whatever history it carries, so import everything: a
         * range wide enough to swallow any realistic transaction date. (The scraper
         * still validates each row's date and rejects an unparseable one.)
         */
        val FULL_RANGE = DateRange(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31))
    }
}
