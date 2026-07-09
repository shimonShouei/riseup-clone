package com.riseup.clone.data

import com.riseup.clone.domain.model.Ledger
import com.riseup.clone.domain.seed.SeedDataGenerator
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** M0 repository: a deterministic, generated ledger for one household. */
class SeededTransactionRepository(
    private val generator: SeedDataGenerator = SeedDataGenerator(),
) : TransactionRepository {

    override suspend fun loadLedger(today: LocalDate): Ledger =
        withContext(Dispatchers.Default) { generator.generate(today) }
}
