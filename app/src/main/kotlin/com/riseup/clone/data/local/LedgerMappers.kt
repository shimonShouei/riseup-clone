package com.riseup.clone.data.local

import com.riseup.clone.domain.model.Account
import com.riseup.clone.domain.model.Ledger
import com.riseup.clone.domain.model.Transaction

/**
 * Pure mappers between the Room entities and the domain [Ledger]. Kept free of
 * any Android runtime so they can be unit-tested on the plain JVM, and so the
 * persistence layer stays a thin translation over the domain shape.
 */

fun AccountEntity.toDomain(): Account =
    Account(id = id, name = name, institution = institution, type = type)

fun TransactionEntity.toDomain(): Transaction =
    Transaction(
        id = id,
        accountId = accountId,
        date = date,
        amount = amount,
        merchant = merchant,
        category = category,
    )

fun Transaction.toEntity(): TransactionEntity =
    TransactionEntity(
        id = id,
        accountId = accountId,
        date = date,
        amount = amount,
        merchant = merchant,
        category = category,
    )

/**
 * Rebuilds a [Ledger] from stored rows. The combined balance is the sum of the
 * per-account balances, mirroring how the seed's opening balance folds into a
 * single ledger figure.
 */
fun toLedger(accounts: List<AccountEntity>, transactions: List<TransactionEntity>): Ledger =
    Ledger(
        accounts = accounts.map { it.toDomain() },
        transactions = transactions.map { it.toDomain() },
        currentBalance = accounts.sumOf { it.currentBalance },
    )

/**
 * Splits a domain [Ledger] into account rows for storage. The domain only
 * carries one combined balance, so we attach it to the first account and zero
 * the rest; summing them back on read reproduces the original figure exactly.
 */
fun Ledger.toAccountEntities(): List<AccountEntity> =
    accounts.mapIndexed { index, account ->
        AccountEntity(
            id = account.id,
            name = account.name,
            institution = account.institution,
            type = account.type,
            currentBalance = if (index == 0) currentBalance else 0.0,
        )
    }
