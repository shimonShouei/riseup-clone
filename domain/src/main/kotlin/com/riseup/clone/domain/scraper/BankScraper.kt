package com.riseup.clone.domain.scraper

import java.time.LocalDate

/**
 * The boundary that fetches real bank data. A provider logs in with
 * [ScraperCredentials], pulls everything in a [DateRange], and returns the
 * result as *raw* DTOs ([ScrapedAccount] / [ScrapedTransaction]) that are
 * deliberately distinct from the domain model.
 *
 * Keeping the return type raw is the whole point of this seam: whatever shape a
 * bank hands back (all-caps merchant strings, locale date strings, magnitudes in
 * separate debit/credit columns, a bank-specific sign convention) survives
 * untouched to the [ScrapeMapper], which is the single place that normalizes it
 * into [com.riseup.clone.domain.model.Transaction] / .model.Account.
 *
 * Implementations range from an offline statement-file parser (see
 * [CsvBankScraper]) to a future live HTTP scraper that drives a bank's web
 * session. Both look identical from here, so the repository layer that wires a
 * provider in (a later task) never learns which kind it holds.
 */
interface BankScraper {

    /** Human-readable name of the institution this provider talks to (e.g. "Bank Leumi"). */
    val institution: String

    /**
     * Log in and fetch accounts + transactions within [range] (inclusive).
     *
     * Never throws for expected failures (bad credentials, network, malformed
     * payload); those come back as [ScrapeResult.Failure] so callers can react
     * without a try/catch. Purely programmer errors may still throw.
     */
    fun scrape(credentials: ScraperCredentials, range: DateRange): ScrapeResult
}

/**
 * Login material for a provider. [extra] carries provider-specific fields (a
 * national ID, a card's last-4, an OTP) so the interface stays stable as new
 * banks with odd login flows are added.
 */
data class ScraperCredentials(
    val username: String,
    val password: String,
    val extra: Map<String, String> = emptyMap(),
) {
    /**
     * Redacted on purpose: the auto-generated data-class `toString()` would leak
     * the password (and any secret in [extra], e.g. an OTP or national ID) into
     * logs, crash reports, and debugger output. Anything holding real login
     * material must be safe to print, so every field is masked here.
     */
    override fun toString(): String = "ScraperCredentials(REDACTED)"
}

/** Inclusive date window to fetch, expressed in the caller's (domain) date type. */
data class DateRange(val from: LocalDate, val to: LocalDate) {
    init {
        require(!from.isAfter(to)) { "DateRange.from ($from) must not be after to ($to)" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

/**
 * A raw account exactly as a bank reports it. [externalId] is the bank's own
 * identifier (account/card number), [rawType] is the bank's untranslated type
 * label — the mapper decides how it becomes an [com.riseup.clone.domain.model.AccountType].
 */
data class ScrapedAccount(
    val externalId: String,
    val label: String,
    val institution: String,
    val rawType: String? = null,
)

/**
 * A raw transaction row, every field a string because that is how it arrives
 * over the wire / in an export. Normalization (sign, amount parsing, date
 * parsing, merchant cleanup, id derivation) is the mapper's job, not the
 * provider's.
 *
 * @param accountExternalId links this row to its [ScrapedAccount.externalId].
 * @param rawDate the date string in the bank's locale format (unparsed).
 * @param rawMerchant the noisy description/merchant string (unnormalized).
 * @param rawAmount the amount as text: may carry a currency symbol, thousands
 *   separators, and/or an embedded sign.
 * @param rawDirection the bank's sign convention hint (e.g. "DEBIT"/"CREDIT")
 *   when amounts come as unsigned magnitudes in separate columns; null when the
 *   sign is embedded in [rawAmount].
 * @param rawCategory the bank's own category label, if any; usually absent.
 * @param reference the bank's stable transaction reference, if any — the most
 *   reliable seed for a stable domain id.
 */
data class ScrapedTransaction(
    val accountExternalId: String,
    val rawDate: String,
    val rawMerchant: String,
    val rawAmount: String,
    val rawDirection: String? = null,
    val rawCategory: String? = null,
    val reference: String? = null,
)

/** Success-or-failure outcome of a scrape. */
sealed interface ScrapeResult {

    /** A completed scrape. Transactions are already filtered to the requested range. */
    data class Success(
        val accounts: List<ScrapedAccount>,
        val transactions: List<ScrapedTransaction>,
    ) : ScrapeResult

    /** A scrape that could not complete. [reason] lets callers branch without string-matching. */
    data class Failure(
        val reason: FailureReason,
        val message: String,
        val cause: Throwable? = null,
    ) : ScrapeResult
}

/** Coarse, provider-agnostic failure buckets. */
enum class FailureReason {
    /** Login rejected (wrong password, locked account, expired session). */
    INVALID_CREDENTIALS,

    /** Could not reach the bank / statement source. */
    NETWORK,

    /** Reached the source but its payload was structurally malformed. */
    PARSE_ERROR,

    /** Anything not covered above. */
    UNKNOWN,
}
