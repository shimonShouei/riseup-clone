import type { LibraryResult, LibraryTransaction } from "./types.js";

/**
 * Turns a library scrape result into a CSV statement in the EXACT format the
 * app's `CsvBankScraper` parses (domain/.../scraper/CsvBankScraper.kt), so the
 * phone imports the file unchanged.
 *
 * Header (fixed column order):
 *   Date,Description,Debit,Credit,Balance,Reference
 *
 * Per-column mapping from one library transaction:
 *  - Date        — `txn.date` (ISO) reformatted to day-first `dd/MM/yyyy` (UTC).
 *  - Description — raw `txn.description`, RFC-4180 quoted so commas survive.
 *  - Debit/Credit— unsigned magnitude of `txn.chargedAmount` in exactly ONE
 *                  column: negative -> Debit, positive (and zero) -> Credit.
 *  - Balance     — account running balance if the library reports one, else
 *                  blank (the parser ignores this column anyway).
 *  - Reference   — `txn.identifier` verbatim (drives the app's stable dedupe id).
 *
 * We intentionally emit NO Category column: the library's Hebrew categories
 * don't match the app's enum, so the app defaults every row to OTHER.
 */

export const CSV_HEADER = "Date,Description,Debit,Credit,Balance,Reference";

/** Newline between CSV records. `\r\n` is RFC-4180; the Kotlin parser accepts it. */
const EOL = "\r\n";

/**
 * RFC-4180 quote a field: wrap in double quotes and double any embedded quote
 * whenever the value contains a comma, quote, CR or LF. Plain values pass
 * through untouched so the file stays readable.
 */
export function csvQuote(value: string): string {
  if (/[",\r\n]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`;
  }
  return value;
}

/** Format an ISO date string as day-first `dd/MM/yyyy` using UTC (no TZ drift). */
export function formatDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    throw new Error(`Unparseable transaction date: '${iso}'`);
  }
  const dd = String(d.getUTCDate()).padStart(2, "0");
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const yyyy = String(d.getUTCFullYear()).padStart(4, "0");
  return `${dd}/${mm}/${yyyy}`;
}

/**
 * Split a signed charged amount into the unsigned (Debit, Credit) pair, exactly
 * one of which is populated. Negative is money out (Debit); positive and zero
 * are money in (Credit). Magnitudes are fixed to 2 decimals (currency).
 */
export function splitAmount(chargedAmount: number): { debit: string; credit: string } {
  if (!Number.isFinite(chargedAmount)) {
    throw new Error(`Non-numeric charged amount: '${chargedAmount}'`);
  }
  const magnitude = Math.abs(chargedAmount).toFixed(2);
  return chargedAmount < 0 ? { debit: magnitude, credit: "" } : { debit: "", credit: magnitude };
}

/** Build one CSV data row for a single transaction. `balance` is blank unless given. */
export function transactionToRow(txn: LibraryTransaction, balance?: number): string {
  const date = formatDate(txn.date);
  const description = csvQuote(txn.description ?? "");
  const { debit, credit } = splitAmount(txn.chargedAmount);
  const balanceCell = balance != null && Number.isFinite(balance) ? balance.toFixed(2) : "";
  const reference = txn.identifier != null ? csvQuote(String(txn.identifier)) : "";
  return [date, description, debit, credit, balanceCell, reference].join(",");
}

/**
 * Build the full CSV document from a library result. Emits the header followed
 * by one row per transaction across all accounts. The account's running
 * `balance` (if the library reports one) is attached only to that account's
 * LAST transaction, mirroring how a real statement shows the closing balance.
 */
export function resultToCsv(result: LibraryResult): string {
  const rows: string[] = [CSV_HEADER];
  for (const account of result.accounts ?? []) {
    const txns = account.txns ?? [];
    txns.forEach((txn, i) => {
      const isLast = i === txns.length - 1;
      rows.push(transactionToRow(txn, isLast ? account.balance : undefined));
    });
  }
  return rows.join(EOL) + EOL;
}

/** Total number of transactions across all accounts in a result. */
export function countTransactions(result: LibraryResult): number {
  return (result.accounts ?? []).reduce((n, a) => n + (a.txns?.length ?? 0), 0);
}
