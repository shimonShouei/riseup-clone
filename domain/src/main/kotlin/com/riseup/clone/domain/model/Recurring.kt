package com.riseup.clone.domain.model

import java.time.LocalDate

/** How often a recurring item repeats. */
enum class Cadence { WEEKLY, MONTHLY }

/** Semantic classification of a recurring item. */
enum class RecurringKind {
    /** Large recurring credit (paycheck). */
    SALARY,

    /** Large recurring debit (rent / mortgage). */
    RENT,

    /** Small recurring debit (Netflix, Spotify, electric bill, arnona...). */
    SUBSCRIPTION_OR_UTILITY,

    /** Recurring but not obviously one of the above. */
    OTHER,
}

/**
 * A detected recurring cash-flow item: same (normalized) merchant, similar
 * amount, steady cadence.
 */
data class RecurringItem(
    /** Normalized merchant key the cluster was built from. */
    val merchantKey: String,
    /** Display name (most recent raw merchant string). */
    val merchant: String,
    /** Signed typical amount (median of the cluster). */
    val amount: Double,
    val cadence: Cadence,
    val kind: RecurringKind,
    /** Day of month it usually lands on (only meaningful for MONTHLY). */
    val dayOfMonth: Int,
    /** Date of the most recent occurrence in the ledger. */
    val lastDate: LocalDate,
    /** Number of observed occurrences that formed the cluster. */
    val occurrences: Int,
    /** Ids of the transactions that belong to this recurring cluster. */
    val transactionIds: Set<String>,
)
