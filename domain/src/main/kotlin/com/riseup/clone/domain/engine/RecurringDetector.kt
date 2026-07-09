package com.riseup.clone.domain.engine

import com.riseup.clone.domain.model.Cadence
import com.riseup.clone.domain.model.RecurringItem
import com.riseup.clone.domain.model.RecurringKind
import com.riseup.clone.domain.model.Transaction
import com.riseup.clone.domain.text.MerchantNormalizer
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Detects recurring cash-flow items by clustering transactions on
 * (normalized merchant, similar amount) and checking the cadence of the
 * resulting date sequence.
 */
class RecurringDetector(
    /** Minimum occurrences before a cluster can be called recurring. */
    private val minOccurrences: Int = 3,
    /** Relative amount tolerance for two amounts to sit in the same cluster. */
    private val amountTolerance: Double = 0.20,
    /** Absolute tolerance floor in ILS (helps small amounts like ₪29.90 vs ₪32.90). */
    private val amountToleranceFloor: Double = 25.0,
    /** Salary threshold: monthly credit at least this large. */
    private val salaryThreshold: Double = 7_000.0,
    /** Rent threshold: monthly debit at least this large (absolute value). */
    private val rentThreshold: Double = 3_000.0,
    /** Subscriptions / utilities: recurring debit below this (absolute value). */
    private val subscriptionCeiling: Double = 1_500.0,
) {

    fun detect(transactions: List<Transaction>): List<RecurringItem> {
        val byMerchant = transactions.groupBy { normalizeMerchant(it.merchant) }
        val items = mutableListOf<RecurringItem>()
        for ((key, group) in byMerchant) {
            if (key.isBlank()) continue
            for (cluster in clusterByAmount(group)) {
                val item = toRecurringItem(key, cluster) ?: continue
                items += item
            }
        }
        return items.sortedByDescending { abs(it.amount) }
    }

    /**
     * Normalizes a raw merchant/description string into a clustering key.
     * Delegates to the shared [MerchantNormalizer] so recurring detection and
     * bank-scraper mapping agree on merchant identity.
     */
    fun normalizeMerchant(raw: String): String = MerchantNormalizer.key(raw)

    /** Splits one merchant's transactions into clusters of similar amounts. */
    private fun clusterByAmount(group: List<Transaction>): List<List<Transaction>> {
        val sorted = group.sortedBy { it.amount }
        val clusters = mutableListOf<MutableList<Transaction>>()
        for (tx in sorted) {
            val cluster = clusters.lastOrNull()
            if (cluster != null && sameAmountBand(medianAmount(cluster), tx.amount)) {
                cluster += tx
            } else {
                clusters += mutableListOf(tx)
            }
        }
        return clusters
    }

    private fun sameAmountBand(reference: Double, candidate: Double): Boolean {
        if (reference == 0.0 && candidate == 0.0) return true
        // Credits and debits never share a cluster.
        if (reference.compareTo(0.0) * candidate.compareTo(0.0) < 0) return false
        val tolerance = maxOf(abs(reference) * amountTolerance, amountToleranceFloor)
        return abs(reference - candidate) <= tolerance
    }

    private fun medianAmount(txs: List<Transaction>): Double =
        median(txs.map { it.amount })

    private fun toRecurringItem(key: String, cluster: List<Transaction>): RecurringItem? {
        if (cluster.size < minOccurrences) return null
        val byDate = cluster.sortedBy { it.date }
        val gaps = byDate.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.isEmpty()) return null
        // Two transactions on the same day (e.g. a double charge) break cadence
        // math; treat zero-gaps as noise and bail out if they dominate.
        val realGaps = gaps.filter { it > 0 }
        if (realGaps.size < minOccurrences - 1) return null

        val medianGap = median(realGaps.map { it.toDouble() })
        val cadence = when {
            medianGap in 5.0..9.0 -> Cadence.WEEKLY
            medianGap in 24.0..37.0 -> Cadence.MONTHLY
            else -> return null
        }
        // Cadence must be steady, not just a coincidental median: every gap
        // within a loose band of the cadence length.
        val expected = if (cadence == Cadence.WEEKLY) 7.0 else 30.4
        val steady = realGaps.all { abs(it - expected) <= expected * 0.35 }
        if (!steady) return null

        val amount = medianAmount(byDate)
        val last = byDate.last()
        return RecurringItem(
            merchantKey = key,
            merchant = last.merchant,
            amount = amount,
            cadence = cadence,
            kind = classify(amount, cadence),
            dayOfMonth = median(byDate.map { it.date.dayOfMonth.toDouble() }).toInt(),
            lastDate = last.date,
            occurrences = byDate.size,
            transactionIds = byDate.map { it.id }.toSet(),
        )
    }

    private fun classify(amount: Double, cadence: Cadence): RecurringKind = when {
        amount >= salaryThreshold && cadence == Cadence.MONTHLY -> RecurringKind.SALARY
        amount <= -rentThreshold && cadence == Cadence.MONTHLY -> RecurringKind.RENT
        amount < 0 && abs(amount) <= subscriptionCeiling -> RecurringKind.SUBSCRIPTION_OR_UTILITY
        else -> RecurringKind.OTHER
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
