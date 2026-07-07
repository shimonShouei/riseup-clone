package com.riseup.clone.domain.model

import java.time.LocalDate

/**
 * Spending / income categories used for the dashboard breakdown.
 */
enum class Category {
    SALARY,
    RENT,
    UTILITIES,
    SUBSCRIPTIONS,
    GROCERIES,
    CAFES,
    RESTAURANTS,
    FUEL,
    HEALTH,
    OTHER,
}

/**
 * A single ledger entry. Amounts are in Israeli Shekels (ILS):
 * positive = credit (money in), negative = debit (money out).
 */
data class Transaction(
    val id: String,
    val accountId: String,
    val date: LocalDate,
    val amount: Double,
    val merchant: String,
    val category: Category = Category.OTHER,
) {
    val isDebit: Boolean get() = amount < 0
    val isCredit: Boolean get() = amount > 0
}
