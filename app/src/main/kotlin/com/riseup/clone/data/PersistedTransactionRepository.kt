package com.riseup.clone.data

import com.riseup.clone.data.local.LedgerDao
import com.riseup.clone.data.local.toAccountEntities
import com.riseup.clone.data.local.toEntity
import com.riseup.clone.data.local.toLedger
import com.riseup.clone.domain.model.Ledger
import com.riseup.clone.domain.seed.SeedDataGenerator
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [TransactionRepository] backed by the Room store, so the ledger survives app
 * restarts and can later be updated incrementally by real bank sync.
 *
 * First run (empty DB): the store is seeded from [SeedDataGenerator] so the
 * dashboard looks identical to the in-memory M0 build. Every subsequent run
 * reads straight from the DB and ignores the seed — real sync (a later task)
 * will write into the same tables and simply supersede the seed rows.
 */
class PersistedTransactionRepository(
    private val dao: LedgerDao,
    private val seeder: SeedDataGenerator = SeedDataGenerator(),
) : TransactionRepository {

    override suspend fun loadLedger(today: LocalDate): Ledger = withContext(Dispatchers.IO) {
        if (dao.accountCount() == 0) {
            val seeded = seeder.generate(today)
            dao.replaceLedger(
                accounts = seeded.toAccountEntities(),
                transactions = seeded.transactions.map { it.toEntity() },
            )
        }
        toLedger(accounts = dao.getAccounts(), transactions = dao.getTransactions())
    }
}
