package com.riseup.clone.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Data-access for the persisted ledger. Reads return rows in a stable order
 * (transactions oldest-first) so the forecast engine sees the same history it
 * would have seen from the in-memory seed. Upserts use REPLACE so a future
 * incremental sync can re-write a row by primary key idempotently.
 */
@Dao
interface LedgerDao {

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun getAccounts(): List<AccountEntity>

    @Query("SELECT * FROM transactions ORDER BY date, id")
    suspend fun getTransactions(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccounts(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransactions(transactions: List<TransactionEntity>)

    /** Seeds accounts before transactions so the foreign key always resolves. */
    @Transaction
    suspend fun replaceLedger(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
    ) {
        upsertAccounts(accounts)
        upsertTransactions(transactions)
    }

    // ---- Incremental sync (M1-5) ----------------------------------------------

    /**
     * Just the stored transaction ids. Sync loads these to skip rows it already
     * has, so a repeat scrape of overlapping history never double-counts.
     */
    @Query("SELECT id FROM transactions")
    suspend fun getTransactionIds(): List<String>

    /**
     * The combined ledger balance as the repository reads it — the sum of every
     * account row's balance. `null` (SUM over no rows) means an empty store.
     */
    @Query("SELECT SUM(currentBalance) FROM accounts")
    suspend fun sumAccountBalances(): Double?

    /**
     * Insert accounts that are new, leaving any existing row (and, crucially, its
     * transactions) untouched. IGNORE — not REPLACE — because REPLACE is a
     * delete+insert that would cascade-delete the account's stored transactions.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewAccounts(accounts: List<AccountEntity>)

    /**
     * Append only transactions whose id isn't stored yet. IGNORE makes the write
     * idempotent by primary key, so overlapping syncs are naturally deduped even
     * without the pre-filter the orchestrator also applies.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewTransactions(transactions: List<TransactionEntity>)

    @Query("UPDATE accounts SET currentBalance = 0")
    suspend fun clearAccountBalances()

    @Query("UPDATE accounts SET currentBalance = :balance WHERE id = :accountId")
    suspend fun setAccountBalance(accountId: String, balance: Double)

    /**
     * Atomically apply one sync pass: register any new accounts, re-home the whole
     * combined balance onto [balanceAccountId] (all other account rows zeroed, so
     * `sum(currentBalance)` still equals the ledger balance the way [toLedger]
     * reads it), then append new transactions. Account metadata is not rewritten
     * on repeat syncs — only the balance moves — which keeps the FK-parent rows
     * stable and avoids any cascade delete of stored history.
     */
    @Transaction
    suspend fun applySync(
        accounts: List<AccountEntity>,
        balanceAccountId: String?,
        newBalance: Double,
        newTransactions: List<TransactionEntity>,
    ) {
        insertNewAccounts(accounts)
        clearAccountBalances()
        if (balanceAccountId != null) setAccountBalance(balanceAccountId, newBalance)
        insertNewTransactions(newTransactions)
    }
}
