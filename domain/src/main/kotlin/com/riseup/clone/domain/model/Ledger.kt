package com.riseup.clone.domain.model

/**
 * A household ledger: the connected [accounts], their combined [transactions]
 * history, and the [currentBalance] that history implies right now.
 *
 * Independent of how it was sourced — the deterministic seed generator (M0) and
 * real bank sync (M1) both produce this same shape, so everything downstream
 * (forecast engine, dashboard) is agnostic to the data source.
 */
data class Ledger(
    val accounts: List<Account>,
    val transactions: List<Transaction>,
    val currentBalance: Double,
)
