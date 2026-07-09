package com.riseup.clone.domain.model

/** The kind of account transactions are drawn from. */
enum class AccountType {
    CHECKING,
    CREDIT_CARD,
    SAVINGS,
}

/**
 * A financial account whose transactions feed the [Ledger]. In M0 this is a single
 * seeded checking account; from M1 it is populated from a connected bank.
 */
data class Account(
    val id: String,
    val name: String,
    val institution: String,
    val type: AccountType = AccountType.CHECKING,
)
