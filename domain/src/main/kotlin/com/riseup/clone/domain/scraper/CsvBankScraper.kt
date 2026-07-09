package com.riseup.clone.domain.scraper

/**
 * A [BankScraper] that reads a bank's exported CSV statement instead of driving a
 * live web session. This is a real, useful provider — every Israeli bank lets you
 * download an account statement as CSV — and it exercises the full seam end to
 * end (raw DTOs → [ScrapeMapper] → domain) without needing live secrets.
 *
 * Structurally it behaves exactly like a live provider would: it "authenticates"
 * and "fetches" via [csvProvider], emits raw [ScrapedTransaction]s (no parsing of
 * numbers/dates — that's the mapper's job), and filters to the requested range.
 * Dropping in a real HTTP scraper later is just a different [BankScraper] with the
 * same signature.
 *
 * ### Expected CSV format (documented, header-driven)
 * A header row naming the columns, then one row per transaction. Columns are
 * matched by name (case-insensitive) so column order/extra columns don't matter:
 * ```
 * Date,Description,Debit,Credit,Balance,Reference
 * 08/01/2026,"SHUFERSAL DEAL 123 TLV",410.00,,"1,234.56",TXN-1001
 * 01/01/2026,MASKORET HEVRAT HI-TECH,,14000.00,15234.56,TXN-1000
 * ```
 * - `Date`        — day-first (dd/MM/yyyy); parsed by the mapper.
 * - `Description` — raw merchant string; normalized by the mapper.
 * - `Debit`/`Credit` — the bank's sign convention: unsigned magnitude in exactly
 *   one of the two columns. Which column is populated becomes the row's
 *   [ScrapedTransaction.rawDirection]. (A single signed `Amount` column is also
 *   accepted as a fallback.)
 * - `Balance`     — running balance; ignored (kept in the format for realism).
 * - `Reference`   — optional stable transaction id; seeds the domain id.
 *
 * Fields may be double-quoted; quoted fields may contain commas (e.g. thousands
 * separators in `Balance`).
 *
 * @param institution human name of the bank the statement came from.
 * @param account the account the statement belongs to. CSV statements are
 *   per-account and rarely carry account metadata, so it's supplied by whoever
 *   configured the export (mirrors how a real per-account fetch knows its account).
 * @param csvProvider yields the raw CSV text. A lambda so the source (test
 *   resource, file, download) is the caller's choice and can fail lazily.
 */
class CsvBankScraper(
    override val institution: String,
    private val account: ScrapedAccount,
    private val csvProvider: () -> String,
) : BankScraper {

    override fun scrape(credentials: ScraperCredentials, range: DateRange): ScrapeResult {
        val text = try {
            csvProvider()
        } catch (e: Exception) {
            return ScrapeResult.Failure(FailureReason.NETWORK, "Could not read CSV source", e)
        }

        val rows = try {
            parse(text)
        } catch (e: CsvFormatException) {
            return ScrapeResult.Failure(FailureReason.PARSE_ERROR, e.message ?: "Malformed CSV", e)
        }

        // Filter to the requested window using the mapper's date parsing so the
        // provider and mapper agree on how a raw date string is interpreted.
        val inRange = rows.filter { row ->
            val date = try {
                ScrapeMapper.parseDate(row.rawDate)
            } catch (_: IllegalArgumentException) {
                return ScrapeResult.Failure(
                    FailureReason.PARSE_ERROR,
                    "Unparseable date in row: '${row.rawDate}'",
                )
            }
            date in range
        }

        return ScrapeResult.Success(accounts = listOf(account), transactions = inRange)
    }

    /** Parses CSV text into raw transaction DTOs. */
    private fun parse(text: String): List<ScrapedTransaction> {
        val lines = text.lineSequence()
            .map { it.trim('﻿') } // strip a UTF-8 BOM if present on line 1
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) throw CsvFormatException("Empty CSV (no header)")

        val header = splitCsvLine(lines.first()).map { it.trim().lowercase() }
        fun col(vararg names: String): Int? =
            names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }

        val dateIdx = col("date", "value date", "ta'arich")
            ?: throw CsvFormatException("CSV missing a 'Date' column; header=$header")
        val descIdx = col("description", "merchant", "details", "peirut")
            ?: throw CsvFormatException("CSV missing a 'Description' column; header=$header")
        val debitIdx = col("debit", "chiyuv")
        val creditIdx = col("credit", "zikuy")
        val amountIdx = col("amount", "sum")
        val refIdx = col("reference", "ref", "asmachta")
        val categoryIdx = col("category")
        if (debitIdx == null && creditIdx == null && amountIdx == null) {
            throw CsvFormatException("CSV needs Debit/Credit or Amount columns; header=$header")
        }

        return lines.drop(1).mapIndexed { i, line ->
            val fields = splitCsvLine(line)
            // +2: 1 for the dropped header, 1 for 1-based line numbers.
            if (fields.size < header.size) {
                throw CsvFormatException(
                    "Row ${i + 2} has ${fields.size} fields, expected ${header.size}: $line",
                )
            }
            fun at(idx: Int?): String = idx?.let { fields.getOrNull(it) }?.trim().orEmpty()

            val debit = at(debitIdx)
            val credit = at(creditIdx)
            val (rawAmount, rawDirection) = when {
                debit.isNotEmpty() -> debit to "DEBIT"
                credit.isNotEmpty() -> credit to "CREDIT"
                else -> at(amountIdx) to null // single signed-amount fallback
            }

            ScrapedTransaction(
                accountExternalId = account.externalId,
                rawDate = at(dateIdx),
                rawMerchant = at(descIdx),
                rawAmount = rawAmount,
                rawDirection = rawDirection,
                rawCategory = at(categoryIdx).ifEmpty { null },
                reference = at(refIdx).ifEmpty { null },
            )
        }
    }

    /**
     * Splits one CSV line on commas, honouring RFC-4180-style double quotes:
     * quoted fields may contain commas, and "" inside a quoted field is a literal
     * quote. Intentionally minimal — bank statement CSVs don't need more.
     */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++ // escaped quote
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** Thrown internally when the CSV is structurally malformed; surfaced as [FailureReason.PARSE_ERROR]. */
    private class CsvFormatException(message: String) : Exception(message)
}
