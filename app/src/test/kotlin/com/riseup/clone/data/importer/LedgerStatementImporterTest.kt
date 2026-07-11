package com.riseup.clone.data.importer

import com.riseup.clone.data.sync.SyncLedgerStore
import com.riseup.clone.domain.model.Account
import com.riseup.clone.domain.model.Transaction
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure-JVM tests for the CSV statement-import use-case, driven with `runTest` and a
 * trivial in-memory [SyncLedgerStore] fake (the same shape the sync orchestrator's
 * tests use). No Android, Room, or SAF — [LedgerStatementImporter] only touches the
 * framework-free [SyncLedgerStore] seam plus the domain scrape pipeline, so the
 * whole map → dedupe → store path is exercised here on real CSV text. The Android
 * pieces (the SAF picker, reading a `content://` Uri) live in the thin
 * `ImportStatementSection` composable and can't run headless on this build machine
 * (see the @Ignore'd [com.riseup.clone.data.PersistedTransactionRepositoryTest]).
 */
class LedgerStatementImporterTest {

    /** Minimal in-memory [SyncLedgerStore] that dedupes by id, like the real Room one. */
    private class FakeStore : SyncLedgerStore {
        val transactions = linkedMapOf<String, Transaction>()
        val accounts = linkedMapOf<String, Account>()
        var balance = 0.0

        override suspend fun existingTransactionIds(): Set<String> = transactions.keys.toSet()
        override suspend fun currentBalance(): Double = balance
        override suspend fun applySync(
            accounts: List<Account>,
            newTransactions: List<Transaction>,
            newBalance: Double,
        ) {
            accounts.forEach { this.accounts.putIfAbsent(it.id, it) }
            newTransactions.forEach { this.transactions.putIfAbsent(it.id, it) }
            balance = newBalance
        }
    }

    private val institution = "Bank Leumi"

    /** A realistic statement: one salary credit and two debits (a comma in Balance is quoted). */
    private val csv = """
        Date,Description,Debit,Credit,Balance,Reference
        01/01/2026,MASKORET HEVRAT HI-TECH,,14000.00,"15,234.56",TXN-1000
        08/01/2026,"SHUFERSAL DEAL 123 TLV",410.00,,"14,824.56",TXN-1001
        09/01/2026,CAFE CAFE,18.00,,"14,806.56",TXN-1002
    """.trimIndent()

    private fun importer(store: SyncLedgerStore) = LedgerStatementImporter(store)

    @Test
    fun `import maps and stores every row`() = runTest {
        val store = FakeStore()

        val result = importer(store).import(csv, institution)

        val imported = assertIs<ImportResult.Imported>(result)
        assertEquals(3, imported.newTransactions)
        assertEquals(3, store.transactions.size)
        assertEquals(1, store.accounts.size)
        // Balance == net of the mapped amounts (14000 - 410 - 18).
        assertEquals(13572.0, store.balance, 0.0001)
    }

    @Test
    fun `re-importing the same file dedupes - count and balance stay stable`() = runTest {
        val store = FakeStore()
        val importer = importer(store)

        importer.import(csv, institution)
        val countAfterFirst = store.transactions.size
        val balanceAfterFirst = store.balance

        val second = importer.import(csv, institution)

        val imported = assertIs<ImportResult.Imported>(second)
        assertEquals(0, imported.newTransactions)
        assertEquals(countAfterFirst, store.transactions.size)
        assertEquals(balanceAfterFirst, store.balance, 0.0001)
    }

    @Test
    fun `malformed CSV surfaces a failure`() = runTest {
        val store = FakeStore()
        // No Date/Description columns the parser can find.
        val malformed = "Col1,Col2\nfoo,bar"

        val result = importer(store).import(malformed, institution)

        assertIs<ImportResult.Failed>(result)
        assertTrue(store.transactions.isEmpty())
        assertEquals(0.0, store.balance, 0.0001)
    }

    @Test
    fun `a row with an unparseable date is a failure`() = runTest {
        val store = FakeStore()
        val badDate = """
            Date,Description,Debit,Credit,Balance,Reference
            not-a-date,CAFE,18.00,,100.00,TXN-1
        """.trimIndent()

        val result = importer(store).import(badDate, institution)

        assertIs<ImportResult.Failed>(result)
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `empty text is handled as a failure, not a crash`() = runTest {
        val store = FakeStore()

        val result = importer(store).import("   ", institution)

        assertIs<ImportResult.Failed>(result)
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `imported rows carry the mapper's stable reference-based ids`() = runTest {
        val store = FakeStore()

        importer(store).import(csv, institution)

        // Every reference-bearing row is keyed by a stable "ref:<account>:<ref>" id.
        assertTrue(store.transactions.keys.all { it.startsWith("ref:") })
    }
}
