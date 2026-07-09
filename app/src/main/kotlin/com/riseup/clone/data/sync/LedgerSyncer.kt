package com.riseup.clone.data.sync

import com.riseup.clone.data.security.CredentialStore
import com.riseup.clone.domain.scraper.BankScraper
import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.FailureReason
import com.riseup.clone.domain.scraper.ScrapeMapper
import com.riseup.clone.domain.scraper.ScrapeResult
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The real sync logic, deliberately framework-free so it can be unit-tested on
 * the JVM without WorkManager, Room, or an Android runtime — the thin
 * [LedgerSyncWorker] is only an adapter that calls [sync] and maps the outcome to
 * a `WorkManager` result.
 *
 * One [sync] pass: load the stored credentials for the scraper's institution,
 * scrape the window, map the raw DTOs to domain via [ScrapeMapper], **dedupe**
 * against what's already stored (by the mapper's stable transaction id), write
 * the new rows plus a refreshed balance through the [SyncLedgerStore] seam, and
 * publish the result on [state] (persisting the success time via [lastSynced]).
 *
 * ### Balance
 * The scraper reports no balance, so the stored balance is the running net of the
 * transactions we've accumulated: on each pass it moves by the sum of the
 * *newly inserted* (deduped) transactions' amounts. A repeat sync therefore adds
 * nothing and leaves the balance unchanged — the same property that keeps the
 * transaction count stable. It stays consistent with how the repository reads the
 * balance (sum of the per-account balances; see [SyncLedgerStore.applySync]).
 *
 * ### Retry policy
 * [sync] never throws for expected failures; it returns a [SyncOutcome] telling
 * the worker whether to retry. NETWORK (and unexpected exceptions) are transient
 * → [SyncOutcome.Retry]; bad/absent credentials and malformed payloads are
 * permanent → [SyncOutcome.Failed]. Either way [state] becomes [SyncState.Error].
 */
class LedgerSyncer(
    private val scraper: BankScraper,
    private val credentials: CredentialStore,
    private val store: SyncLedgerStore,
    private val lastSynced: LastSyncedStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** Observable sync status for the ViewModel layer. [LedgerSyncer] is the only writer. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Republish the persisted last-sync time as [SyncState.Success] if we're still
     * [SyncState.Idle], so a freshly built syncer surfaces the previous result
     * after a process restart instead of looking like it never ran. No-op once a
     * sync has started this process.
     */
    suspend fun hydrate() {
        if (_state.value !is SyncState.Idle) return
        val at = withContext(ioDispatcher) { lastSynced.read() } ?: return
        // Guard against a race with a sync that started meanwhile.
        if (_state.value is SyncState.Idle) _state.value = SyncState.Success(at)
    }

    /**
     * Run one sync over [range]. Safe to call off the main thread; all I/O is
     * confined to [ioDispatcher]. Returns the outcome the worker uses to decide
     * success / retry / give-up.
     */
    suspend fun sync(range: DateRange): SyncOutcome = withContext(ioDispatcher) {
        _state.value = SyncState.Syncing
        try {
            val creds = credentials.load(scraper.institution)
                ?: return@withContext fail(
                    SyncErrorReason.NO_CREDENTIALS,
                    "No stored credentials for ${scraper.institution}",
                    retry = false,
                )

            when (val result = scraper.scrape(creds, range)) {
                is ScrapeResult.Success -> persist(result)
                is ScrapeResult.Failure -> fail(result.reason.toSyncReason(), result.message, result.reason.isTransient())
            }
        } catch (e: Exception) {
            // Unexpected (I/O, programmer error surfacing at runtime): treat as
            // transient so the worker's backoff gets another attempt.
            fail(SyncErrorReason.UNKNOWN, e.message ?: e.toString(), retry = true)
        }
    }

    /** Map + dedupe + write a successful scrape, then mark [SyncState.Success]. */
    private suspend fun persist(success: ScrapeResult.Success): SyncOutcome {
        val mapped = ScrapeMapper.map(success)
        val existing = store.existingTransactionIds()
        // Dedupe by the mapper's stable id: keep only rows we haven't stored.
        val fresh = mapped.transactions.filterNot { it.id in existing }
        val newBalance = store.currentBalance() + fresh.sumOf { it.amount }

        store.applySync(accounts = mapped.accounts, newTransactions = fresh, newBalance = newBalance)

        val at = clock()
        lastSynced.write(at)
        _state.value = SyncState.Success(at)
        return SyncOutcome.Synced(newTransactions = fresh.size, at = at)
    }

    /** Publish an error state and return the matching retry/give-up outcome. */
    private fun fail(reason: SyncErrorReason, message: String, retry: Boolean): SyncOutcome {
        _state.value = SyncState.Error(reason, message)
        return if (retry) SyncOutcome.Retry(reason, message) else SyncOutcome.Failed(reason, message)
    }

    private fun FailureReason.toSyncReason(): SyncErrorReason = when (this) {
        FailureReason.INVALID_CREDENTIALS -> SyncErrorReason.INVALID_CREDENTIALS
        FailureReason.OTP_REQUIRED -> SyncErrorReason.OTP_REQUIRED
        FailureReason.NETWORK -> SyncErrorReason.NETWORK
        FailureReason.PARSE_ERROR -> SyncErrorReason.PARSE_ERROR
        FailureReason.UNKNOWN -> SyncErrorReason.UNKNOWN
    }

    /**
     * NETWORK and UNKNOWN are worth another attempt; bad creds, a malformed payload,
     * and OTP/2FA-required are permanent. OTP in particular must NOT retry
     * (SECURITY.md R16 / T10): an unattended re-login against a 2FA-gated account
     * only risks an anti-automation lock, so the background worker gives up here.
     */
    private fun FailureReason.isTransient(): Boolean =
        this == FailureReason.NETWORK || this == FailureReason.UNKNOWN
}

/**
 * What one [LedgerSyncer.sync] pass concluded, in the vocabulary the worker needs
 * to pick a `WorkManager` result. Separate from [SyncState] (which is for the UI)
 * so the worker never has to interpret UI state.
 */
sealed interface SyncOutcome {

    /** Completed; [newTransactions] rows were newly stored at [at]. */
    data class Synced(val newTransactions: Int, val at: Instant) : SyncOutcome

    /** Transient failure — the worker should `Result.retry()`. */
    data class Retry(val reason: SyncErrorReason, val message: String) : SyncOutcome

    /** Permanent failure — the worker should `Result.failure()`. */
    data class Failed(val reason: SyncErrorReason, val message: String) : SyncOutcome
}
