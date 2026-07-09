package com.riseup.clone.domain.text

/**
 * Canonical merchant/description normalization, shared across the domain so that
 * everything that reasons about "the same merchant" agrees on what that means.
 *
 * Bank descriptions are noisy: mixed case, trailing branch numbers, reference
 * codes and punctuation ("SHUFERSAL DEAL 123 T-A" vs "shufersal deal 77 t a").
 * [key] collapses all of that into a stable clustering key; [display] derives a
 * tidy, human-readable label from the same normalization so the two never drift.
 *
 * This logic used to live inside `RecurringDetector`; it was extracted here when
 * the bank-scraper mapper needed the exact same notion of merchant identity.
 */
object MerchantNormalizer {

    /**
     * A stable clustering / dedup key: lowercase, digits and punctuation stripped,
     * whitespace collapsed. Two descriptions that differ only by case, branch
     * number or punctuation produce the same key.
     */
    fun key(raw: String): String =
        raw.lowercase()
            .replace(Regex("[0-9]"), " ")
            .replace(Regex("[^\\p{L} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * A human-facing label derived from [key]: same word set (branch-number and
     * punctuation noise already gone), title-cased. Empty in, empty out.
     */
    fun display(raw: String): String =
        key(raw)
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
}
