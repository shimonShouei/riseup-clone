package com.riseup.clone.data.sync

import com.riseup.clone.data.local.AccountEntity
import com.riseup.clone.data.local.LedgerDao
import com.riseup.clone.data.local.toEntity
import com.riseup.clone.domain.model.Account
import com.riseup.clone.domain.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed [SyncLedgerStore]. A thin translation over [LedgerDao] that keeps
 * the combined ledger balance stored the way the persisted repository reads it:
 * as the sum of the per-account balances, with the whole figure parked on the
 * first account and the rest zeroed (the same convention as
 * [com.riseup.clone.data.local.toAccountEntities]).
 *
 * All I/O runs on [Dispatchers.IO], matching the repositories.
 */
class RoomSyncLedgerStore(private val dao: LedgerDao) : SyncLedgerStore {

    override suspend fun existingTransactionIds(): Set<String> = withContext(Dispatchers.IO) {
        dao.getTransactionIds().toSet()
    }

    override suspend fun currentBalance(): Double = withContext(Dispatchers.IO) {
        dao.sumAccountBalances() ?: 0.0
    }

    override suspend fun applySync(
        accounts: List<Account>,
        newTransactions: List<Transaction>,
        newBalance: Double,
    ): Unit = withContext(Dispatchers.IO) {
        // New account rows carry a zero balance; the real figure is set atomically
        // by the DAO onto balanceAccountId (below) so the sum stays correct even if
        // the store already held other accounts.
        val accountRows = accounts.map { account ->
            AccountEntity(
                id = account.id,
                name = account.name,
                institution = account.institution,
                type = account.type,
                currentBalance = 0.0,
            )
        }
        dao.applySync(
            accounts = accountRows,
            balanceAccountId = accounts.firstOrNull()?.id,
            newBalance = newBalance,
            newTransactions = newTransactions.map { it.toEntity() },
        )
    }
}
