package com.riseup.clone.domain.scraper

import com.riseup.clone.domain.model.Account
import com.riseup.clone.domain.model.AccountType
import com.riseup.clone.domain.model.Category
import com.riseup.clone.domain.model.Transaction
import com.riseup.clone.domain.text.MerchantNormalizer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Normalizes raw scraper DTOs into domain models. This is the *only* place that
 * understands a bank's quirks — sign convention, amount/date string formats,
 * merchant noise — so providers stay dumb and every provider's output lands in
 * the same clean domain shape.
 *
 * All functions are pure and deterministic: same raw in, same domain out. That
 * matters for [deriveTransactionId], whose stability lets repeated syncs
 * de-duplicate rows instead of piling up copies.
 */
object ScrapeMapper {

    /**
     * Date string formats we attempt, in order. Covers ISO plus the day-first
     * formats Israeli/European bank exports use. The first that parses wins.
     */
    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd",
        "dd/MM/yyyy",
        "d/M/yyyy",
        "dd.MM.yyyy",
    ).map(DateTimeFormatter::ofPattern)

    /** Direction tokens a bank might use for "money out". */
    private val DEBIT_TOKENS = setOf("DEBIT", "DR", "D", "-", "WITHDRAWAL", "CHIYUV")

    /** Direction tokens a bank might use for "money in". */
    private val CREDIT_TOKENS = setOf("CREDIT", "CR", "C", "+", "DEPOSIT", "ZIKUY")

    // ---- Whole-result mapping --------------------------------------------------

    /** Everything a successful scrape yields, in domain form. */
    data class Mapped(val accounts: List<Account>, val transactions: List<Transaction>)

    /** Map a whole [ScrapeResult.Success] to domain accounts + transactions. */
    fun map(success: ScrapeResult.Success): Mapped = Mapped(
        accounts = success.accounts.map(::toAccount),
        transactions = success.transactions.map(::toTransaction),
    )

    // ---- Account ---------------------------------------------------------------

    fun toAccount(raw: ScrapedAccount): Account = Account(
        id = raw.externalId,
        name = raw.label.trim(),
        institution = raw.institution.trim(),
        type = toAccountType(raw.rawType),
    )

    /**
     * Maps a bank's untranslated type label to an [AccountType], keying off
     * substrings so "Visa Credit Card", "CC", etc. all land on CREDIT_CARD.
     * Defaults to CHECKING (the app's primary account kind) when unknown/absent.
     */
    fun toAccountType(rawType: String?): AccountType {
        val t = rawType?.lowercase()?.trim().orEmpty()
        return when {
            t.isEmpty() -> AccountType.CHECKING
            "credit" in t || "card" in t || t == "cc" || "ashrai" in t -> AccountType.CREDIT_CARD
            "saving" in t || "deposit" in t || "hisachon" in t -> AccountType.SAVINGS
            else -> AccountType.CHECKING
        }
    }

    // ---- Transaction -----------------------------------------------------------

    fun toTransaction(raw: ScrapedTransaction): Transaction {
        val date = parseDate(raw.rawDate)
        val amount = parseAmount(raw.rawAmount, raw.rawDirection)
        val merchantKey = MerchantNormalizer.key(raw.rawMerchant)
        return Transaction(
            id = deriveTransactionId(raw, date = date, amount = amount, merchantKey = merchantKey),
            accountId = raw.accountExternalId,
            date = date,
            amount = amount,
            merchant = MerchantNormalizer.display(raw.rawMerchant),
            category = toCategory(raw.rawCategory),
        )
    }

    /**
     * Parses a raw date string by trying each supported format.
     * @throws IllegalArgumentException if none match.
     */
    fun parseDate(raw: String): LocalDate {
        val trimmed = raw.trim()
        for (fmt in DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, fmt)
            } catch (_: DateTimeParseException) {
                // try the next format
            }
        }
        throw IllegalArgumentException("Unparseable date: '$raw'")
    }

    /**
     * Parses a raw amount into a signed ILS [Double] using the domain convention
     * (positive = credit / money in, negative = debit / money out).
     *
     * Strips currency symbols, thousands separators and whitespace, then applies
     * the sign: if [rawDirection] is given it wins (magnitude forced to the right
     * sign); otherwise the sign embedded in [rawAmount] is honoured.
     *
     * @throws IllegalArgumentException if the numeric part cannot be parsed.
     */
    fun parseAmount(rawAmount: String, rawDirection: String? = null): Double {
        // Keep digits, a leading/embedded minus, and the decimal point. Drop ₪, $,
        // spaces, and thousands commas. (Amounts here are always dot-decimal.)
        val cleaned = rawAmount.trim().replace(Regex("[^0-9.\\-]"), "")
        val magnitude = cleaned.toDoubleOrNull()
            ?: throw IllegalArgumentException("Unparseable amount: '$rawAmount'")

        val dir = rawDirection?.trim()?.uppercase()
        return when {
            dir == null || dir.isEmpty() -> magnitude
            dir in DEBIT_TOKENS -> -abs(magnitude)
            dir in CREDIT_TOKENS -> abs(magnitude)
            else -> throw IllegalArgumentException("Unknown amount direction: '$rawDirection'")
        }
    }

    /**
     * Maps a bank's raw category label onto our [Category], case-insensitively.
     * Bank exports rarely carry our taxonomy, so this defaults to [Category.OTHER]
     * whenever the label is absent or unrecognized — categorization proper is a
     * separate concern handled downstream.
     */
    fun toCategory(rawCategory: String?): Category {
        val name = rawCategory?.trim()?.uppercase().orEmpty()
        if (name.isEmpty()) return Category.OTHER
        return Category.entries.firstOrNull { it.name == name } ?: Category.OTHER
    }

    /**
     * Derives a stable, deterministic transaction id.
     *
     * When the bank supplies a [ScrapedTransaction.reference] we trust it (scoped
     * by account so references are globally unique). Otherwise we hash the
     * content that identifies the row — account, date, normalized merchant, and
     * amount — so the same statement row always yields the same id across syncs
     * and a de-dup on id actually works. Two rows differing in any of those
     * fields get different ids.
     */
    fun deriveTransactionId(
        raw: ScrapedTransaction,
        date: LocalDate,
        amount: Double,
        merchantKey: String,
    ): String {
        raw.reference?.trim()?.takeIf { it.isNotEmpty() }?.let { ref ->
            return "ref:${raw.accountExternalId}:$ref"
        }
        // "%.2f" pins amount formatting so 5.0 and 5.00 hash identically.
        val seed = listOf(
            raw.accountExternalId,
            date.toString(),
            merchantKey,
            String.format("%.2f", amount),
        ).joinToString("|")
        return "sha:" + sha256Hex(seed).take(16)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) append("%02x".format(b))
        }
    }
}
