package com.riseup.clone.data

import com.riseup.clone.domain.seed.SeededLedger
import java.time.LocalDate

/**
 * Source of the household ledger. M0 ships a seeded implementation; later
 * milestones swap in real bank data behind the same interface.
 */
interface TransactionRepository {
    suspend fun loadLedger(today: LocalDate): SeededLedger
}
