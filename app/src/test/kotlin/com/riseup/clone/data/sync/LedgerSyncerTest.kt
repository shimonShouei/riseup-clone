package com.riseup.clone.data.sync

import com.riseup.clone.data.security.InMemoryCredentialStore
import com.riseup.clone.domain.model.Account
import com.riseup.clone.domain.model.Transaction
import com.riseup.clone.domain.scraper.BankScraper
import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.FailureReason
import com.riseup.clone.domain.scraper.ScrapeMapper
import com.riseup.clone.domain.scraper.ScrapeResult
import com.riseup.clone.domain.scraper.ScrapedAccount
import com.riseup.clone.domain.scraper.ScrapedTransaction
import com.riseup.clone.domain.scraper.ScraperCredentials
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure-JVM tests for the sync orchestrator, driven with `runTest` and fakes (a
 * canned [BankScraper], the in-memory credential/last-synced stores, and a
 * fake [SyncLedgerStore]). No WorkManager, Room, or Android runtime — the whole
 * point of keeping [LedgerSyncer] framework-free. The worker shell and the real
 * Room/Keystore round-trips are covered by the [org.junit.Ignore]d
 * [LedgerSyncWorkerTest] (can't run on this build machine).
 */
class LedgerSyncerTest {

    private val institution = "Test Bank"
    private val range = DateRange(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-07-01"))
    private val fixedNow = Instant.parse("2026-07-07T10:15:30Z")

    // ---- Fakes -----------------------------------------------------------------

    /** A [BankScraper] that returns whatever result it's handed, recording call count. */
    private class FakeScraper(
        override val institution: String,
        var result: ScrapeResult,
    ) : BankScraper {
        var scrapeCount = 0
        override fun scrape(credentials: ScraperCredentials, range: DateRange): ScrapeResult {
            scrapeCount++
            return result
        }
    }

    /** Minimal in-memory [SyncLedgerStore] that actually dedupes, like the real one. */
    private class FakeStore : SyncLedgerStore {
        val transactions = linkedMapOf<String, Transaction>()
        val accounts = linkedMapOf<String, Account>()
        var balance = 0.0
        var applyCount = 0

        override suspend fun existingTransactionIds(): Set<String> = transactions.keys.toSet()
        override suspend fun currentBalance(): Double = balance
        override suspend fun applySync(
            accounts: List<Account>,
            newTransactions: List<Transaction>,
            newBalance: Double,
        ) {
            applyCount++
            accounts.forEach { this.accounts.putIfAbsent(it.id, it) }
            // Mirror the DAO's insert-ignore: never overwrite an existing id.
            newTransactions.forEach { this.transactions.putIfAbsent(it.id, it) }
            balance = newBalance
        }
    }

    private fun scrapedAccount() = ScrapedAccount(
        externalId = "acc-1",
        label = "Checking",
        institution = institution,
        rawType = "checking",
    )

    private fun scrapedTxns() = listOf(
        ScrapedTransaction("acc-1", "2026-05-01", "MASKORET", "10000.00", "CREDIT", reference = "T-1"),
        ScrapedTransaction("acc-1", "2026-05-03", "SHUFERSAL", "410.00", "DEBIT", reference = "T-2"),
        ScrapedTransaction("acc-1", "2026-05-04", "CAFE", "18.00", "DEBIT", reference = "T-3"),
    )

    private fun success() = ScrapeResult.Success(listOf(scrapedAccount()), scrapedTxns())

    private fun syncer(
        scraper: BankScraper,
        store: SyncLedgerStore = FakeStore(),
        credentials: InMemoryCredentialStore = InMemoryCredentialStore(),
        lastSynced: LastSyncedStore = InMemoryLastSyncedStore(),
    ) = LedgerSyncer(
        scraper = scraper,
        credentials = credentials,
        store = store,
        lastSynced = lastSynced,
        clock = { fixedNow },
    )

    private suspend fun withCreds() = InMemoryCredentialStore().apply {
        save(institution, ScraperCredentials("user", "pass"))
    }

    // ---- Happy path ------------------------------------------------------------

    @Test
    fun `happy path maps and writes all transactions`() = runTest {
        val store = FakeStore()
        val scraper = FakeScraper(institution, success())
        val syncer = syncer(scraper, store, withCreds())

        val outcome = syncer.sync(range)

        val synced = assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(3, synced.newTransactions)
        assertEquals(3, store.transactions.size)
        assertEquals(1, store.accounts.size)
        // Balance == net of the mapped amounts (10000 - 410 - 18).
        assertEquals(9572.0, store.balance, 0.0001)
    }

    @Test
    fun `stored ids match the mapper's stable ids`() = runTest {
        val store = FakeStore()
        val syncer = syncer(FakeScraper(institution, success()), store, withCreds())

        syncer.sync(range)

        val expectedIds = ScrapeMapper.map(success()).transactions.map { it.id }.toSet()
        assertEquals(expectedIds, store.transactions.keys)
    }

    // ---- Dedupe ----------------------------------------------------------------

    @Test
    fun `repeat sync dedupes - count and balance stay stable`() = runTest {
        val store = FakeStore()
        val syncer = syncer(FakeScraper(institution, success()), store, withCreds())

        syncer.sync(range)
        val countAfterFirst = store.transactions.size
        val balanceAfterFirst = store.balance

        val second = syncer.sync(range)

        val synced = assertIs<SyncOutcome.Synced>(second)
        assertEquals(0, synced.newTransactions)
        assertEquals(countAfterFirst, store.transactions.size)
        assertEquals(balanceAfterFirst, store.balance, 0.0001)
    }

    @Test
    fun `incremental sync only adds the new rows and advances the balance`() = runTest {
        val store = FakeStore()
        val creds = withCreds()
        val scraper = FakeScraper(institution, success())
        val syncer = syncer(scraper, store, creds)

        syncer.sync(range)

        // A later scrape re-reports the old rows plus one new debit.
        val extra = ScrapedTransaction("acc-1", "2026-06-01", "FUEL", "300.00", "DEBIT", reference = "T-4")
        scraper.result = ScrapeResult.Success(listOf(scrapedAccount()), scrapedTxns() + extra)

        val outcome = syncer.sync(range)

        val synced = assertIs<SyncOutcome.Synced>(outcome)
        assertEquals(1, synced.newTransactions)
        assertEquals(4, store.transactions.size)
        assertEquals(9572.0 - 300.0, store.balance, 0.0001)
    }

    // ---- Failures --------------------------------------------------------------

    @Test
    fun `network failure signals retry and sets an error state`() = runTest {
        val store = FakeStore()
        val scraper = FakeScraper(
            institution,
            ScrapeResult.Failure(FailureReason.NETWORK, "offline"),
        )
        val syncer = syncer(scraper, store, withCreds())

        val outcome = syncer.sync(range)

        val retry = assertIs<SyncOutcome.Retry>(outcome)
        assertEquals(SyncErrorReason.NETWORK, retry.reason)
        assertEquals(SyncState.Error(SyncErrorReason.NETWORK, "offline"), syncer.state.value)
        assertEquals(0, store.applyCount) // nothing written
    }

    @Test
    fun `invalid credentials is a permanent failure`() = runTest {
        val scraper = FakeScraper(
            institution,
            ScrapeResult.Failure(FailureReason.INVALID_CREDENTIALS, "rejected"),
        )
        val syncer = syncer(scraper, credentials = withCreds())

        val outcome = syncer.sync(range)

        val failed = assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(SyncErrorReason.INVALID_CREDENTIALS, failed.reason)
    }

    @Test
    fun `otp required is a permanent failure and does not retry-loop`() = runTest {
        // R16 / T10: an OTP/2FA-required scrape must be permanent (Failed, not Retry)
        // so the background worker gives up instead of re-attempting an automated
        // login against a 2FA-gated account.
        val scraper = FakeScraper(
            institution,
            ScrapeResult.Failure(FailureReason.OTP_REQUIRED, "otp_required"),
        )
        val syncer = syncer(scraper, credentials = withCreds())

        val outcome = syncer.sync(range)

        val failed = assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(SyncErrorReason.OTP_REQUIRED, failed.reason)
    }

    @Test
    fun `missing credentials fails without scraping`() = runTest {
        val scraper = FakeScraper(institution, success())
        // No credentials saved for the institution.
        val syncer = syncer(scraper, credentials = InMemoryCredentialStore())

        val outcome = syncer.sync(range)

        val failed = assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(SyncErrorReason.NO_CREDENTIALS, failed.reason)
        assertEquals(0, scraper.scrapeCount)
        assertIs<SyncState.Error>(syncer.state.value)
    }

    @Test
    fun `unexpected exception is treated as transient retry`() = runTest {
        val scraper = object : BankScraper {
            override val institution = this@LedgerSyncerTest.institution
            override fun scrape(credentials: ScraperCredentials, range: DateRange): ScrapeResult =
                throw IllegalStateException("boom")
        }
        val syncer = syncer(scraper, credentials = withCreds())

        val outcome = syncer.sync(range)

        assertIs<SyncOutcome.Retry>(outcome)
        assertEquals(SyncErrorReason.UNKNOWN, (syncer.state.value as SyncState.Error).reason)
    }

    // ---- State + lastSyncedAt --------------------------------------------------

    @Test
    fun `state transitions Idle then Syncing then Success`() = runTest {
        val store = FakeStore()
        // Observe the intermediate Syncing by having the scraper capture state mid-run.
        lateinit var syncer: LedgerSyncer
        val observing = object : BankScraper {
            override val institution = this@LedgerSyncerTest.institution
            var stateDuringScrape: SyncState? = null
            override fun scrape(credentials: ScraperCredentials, range: DateRange): ScrapeResult {
                stateDuringScrape = syncer.state.value
                return success()
            }
        }
        syncer = syncer(observing, store, withCreds())

        assertEquals(SyncState.Idle, syncer.state.value)
        syncer.sync(range)

        assertEquals(SyncState.Syncing, observing.stateDuringScrape)
        assertEquals(SyncState.Success(fixedNow), syncer.state.value)
    }

    @Test
    fun `lastSyncedAt is persisted on success`() = runTest {
        val lastSynced = InMemoryLastSyncedStore()
        val syncer = syncer(
            FakeScraper(institution, success()),
            credentials = withCreds(),
            lastSynced = lastSynced,
        )

        assertNull(lastSynced.read())
        syncer.sync(range)

        assertEquals(fixedNow, lastSynced.read())
    }

    @Test
    fun `lastSyncedAt is not written on failure`() = runTest {
        val lastSynced = InMemoryLastSyncedStore()
        val syncer = syncer(
            FakeScraper(institution, ScrapeResult.Failure(FailureReason.NETWORK, "offline")),
            credentials = withCreds(),
            lastSynced = lastSynced,
        )

        syncer.sync(range)

        assertNull(lastSynced.read())
    }

    @Test
    fun `hydrate republishes the persisted time as Success while Idle`() = runTest {
        val persisted = Instant.parse("2026-07-01T08:00:00Z")
        val syncer = syncer(
            FakeScraper(institution, success()),
            lastSynced = InMemoryLastSyncedStore(initial = persisted),
        )

        assertEquals(SyncState.Idle, syncer.state.value)
        syncer.hydrate()

        assertEquals(SyncState.Success(persisted), syncer.state.value)
    }

    @Test
    fun `hydrate is a no-op when nothing was ever synced`() = runTest {
        val syncer = syncer(FakeScraper(institution, success()))

        syncer.hydrate()

        assertEquals(SyncState.Idle, syncer.state.value)
    }

    @Test
    fun `hydrate does not clobber a live result`() = runTest {
        val syncer = syncer(
            FakeScraper(institution, success()),
            credentials = withCreds(),
            lastSynced = InMemoryLastSyncedStore(initial = Instant.parse("2020-01-01T00:00:00Z")),
        )

        syncer.sync(range) // -> Success(fixedNow)
        syncer.hydrate()   // must not overwrite with the stale persisted value

        assertEquals(SyncState.Success(fixedNow), syncer.state.value)
    }
}
