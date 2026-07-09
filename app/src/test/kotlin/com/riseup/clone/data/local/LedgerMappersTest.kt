package com.riseup.clone.data.local

import com.riseup.clone.domain.model.AccountType
import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.seed.SeedDataGenerator
import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Pure-JVM checks on the entity <-> domain mappers and type converters. No
 * Android runtime is involved, so these always run in CI even without a device.
 */
class LedgerMappersTest {

    private val converters = Converters()

    @Test
    fun `LocalDate survives the epoch-day converter`() {
        val date = LocalDate.parse("2026-07-07")
        val stored = converters.localDateToEpochDay(date)
        assertEquals(date, converters.epochDayToLocalDate(stored))
    }

    @Test
    fun `enums survive their name converters`() {
        assertEquals(
            Category.GROCERIES,
            converters.nameToCategory(converters.categoryToName(Category.GROCERIES)),
        )
        assertEquals(
            AccountType.CREDIT_CARD,
            converters.nameToAccountType(converters.accountTypeToName(AccountType.CREDIT_CARD)),
        )
    }

    @Test
    fun `seeded ledger round-trips through the entity mappers`() {
        val today = LocalDate.parse("2026-07-07")
        val ledger = SeedDataGenerator().generate(today)

        val accountRows = ledger.toAccountEntities()
        val txRows = ledger.transactions.map { it.toEntity() }
        val restored = toLedger(accountRows, txRows)

        assertEquals(ledger.accounts, restored.accounts)
        assertEquals(ledger.transactions, restored.transactions)
        assertEquals(ledger.currentBalance, restored.currentBalance, 0.0001)
    }
}
