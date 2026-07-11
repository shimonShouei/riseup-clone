package com.riseup.clone.data.sync

import com.riseup.clone.domain.model.Account
import com.riseup.clone.domain.model.Transaction

/**
 * The write-side seam the statement importer persists through. Deliberately
 * framework-free (no Room, no Android): it exposes exactly what
 * [com.riseup.clone.data.importer.LedgerStatementImporter] needs — read the ids
 * already stored (to dedupe), read the current balance (to refresh it), and apply
 * one batch — so the importer can be unit-tested on the JVM against a trivial
 * in-memory fake instead of a real database.
 *
 * The production implementation is [RoomSyncLedgerStore]; tests inject a fake.
 */
interface SyncLedgerStore {

    /**
     * The ids of every transaction already stored. The importer subtracts these
     * from a statement's mapped transactions so re-importing overlapping history
     * inserts each row at most once.
     */
    suspend fun existingTransactionIds(): Set<String>

    /**
     * The current combined ledger balance, read the same way the repository reads
     * it (sum of the per-account balances). An empty store reads as `0.0`.
     */
    suspend fun currentBalance(): Double

    /**
     * Persist one import pass: register [accounts], append [newTransactions]
     * (already deduped by the importer), and set the stored balance to [newBalance].
     * Implementations must be idempotent by transaction id and must leave
     * [newBalance] readable back via [currentBalance].
     */
    suspend fun applySync(
        accounts: List<Account>,
        newTransactions: List<Transaction>,
        newBalance: Double,
    )
}
