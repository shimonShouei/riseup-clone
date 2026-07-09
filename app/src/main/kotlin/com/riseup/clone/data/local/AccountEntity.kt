package com.riseup.clone.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.riseup.clone.domain.model.AccountType

/**
 * Room row for a connected [com.riseup.clone.domain.model.Account].
 *
 * We persist a per-account [currentBalance] here rather than deriving it from
 * transactions on every read: the domain [com.riseup.clone.domain.model.Ledger]
 * exposes only a single combined balance (opening balance + all movements), and
 * the opening balance is not itself a transaction. Storing it on the account
 * lets the ledger balance survive restarts and lets a future incremental sync
 * update a balance without replaying the whole history.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val institution: String,
    val type: AccountType,
    val currentBalance: Double,
)
