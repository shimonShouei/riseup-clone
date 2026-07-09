package com.riseup.clone.domain.scraper

import com.riseup.clone.domain.model.Ledger
import com.riseup.clone.domain.seed.SeedDataGenerator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Renders a realistic, multi-month bank statement as CSV text in the exact format
 * [CsvBankScraper] parses, synthesized from [SeedDataGenerator].
 *
 * ### Why this exists
 * M1 has no live bank scraper yet, so the connect-bank flow needs *something* rich
 * to import. A tiny fixed fixture makes the dashboard look empty: with only a few
 * rows there is no history, so the forecast engine detects no recurring items and
 * never produces the overdraft-dip hero visual. Generating the statement from the
 * same seed the demo dashboard uses means "connecting a bank" imports ~4 months of
 * today-relative history through the **real** scrape → [ScrapeMapper] → Room
 * pipeline — the result is indistinguishable from a genuine import, and the
 * dashboard fills in properly.
 *
 * The seed stays demo data: it enters the persisted ledger *only* via this explicit
 * import path (never merged behind the user's back). A real bank's rows would flow
 * through the identical pipeline and simply replace this sample.
 */
object SampleStatementCsv {

    private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /**
     * CSV for a statement whose history ends near [today], using [generator] as the
     * source of transactions (defaults to the standard deterministic seed).
     */
    fun forToday(today: LocalDate, generator: SeedDataGenerator = SeedDataGenerator()): String =
        render(generator.generate(today))

    /**
     * Columns match [CsvBankScraper]'s documented format. `Category` carries the
     * domain category name so the mapper preserves it (the parser supports an
     * optional Category column); `Reference` carries the seed id so repeated syncs
     * derive the same stable transaction id and de-duplicate.
     */
    private fun render(ledger: Ledger): String {
        val sb = StringBuilder("Date,Description,Debit,Credit,Balance,Reference,Category\n")
        // Running balance, for realism (the parser ignores it). currentBalance is the
        // balance after the last transaction, so the opening balance is that minus
        // every movement; walk forward from there.
        var balance = ledger.currentBalance - ledger.transactions.sumOf { it.amount }
        for (t in ledger.transactions) {
            balance += t.amount
            val debit = if (t.amount < 0) money(abs(t.amount)) else ""
            val credit = if (t.amount > 0) money(t.amount) else ""
            sb.append(t.date.format(DATE)).append(',')
                .append(quote(t.merchant)).append(',')
                .append(debit).append(',')
                .append(credit).append(',')
                .append(quote(money(balance))).append(',')
                .append(t.id).append(',')
                .append(t.category.name)
                .append('\n')
        }
        return sb.toString()
    }

    private fun money(v: Double): String = String.format("%.2f", v)

    /** RFC-4180 quoting so descriptions containing commas survive the round-trip. */
    private fun quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
