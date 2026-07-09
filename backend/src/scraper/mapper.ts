import { ScrapeError } from "./errors.js";
import type { LibraryResult, ScrapeResponse, ScrapedAccount, ScrapedTransaction } from "./types.js";

/** Human-readable institution name emitted for every Discount account. */
export const DISCOUNT_INSTITUTION = "Discount Bank";

/**
 * Map the raw library result to the app's DTO contract
 * (`ScrapedAccount` / `ScrapedTransaction`). Emits RAW string values only — the
 * app's `ScrapeMapper` owns all parsing (dates, amounts, sign, merchant cleanup).
 *
 * Sign convention: the library's `chargedAmount` is already signed (negative =
 * debit). We put that signed value straight into `rawAmount` and leave
 * `rawDirection` null — the mapper's documented "signed amount" fallback. We do
 * NOT pre-parse or re-sign anything here.
 *
 * `reference` uses the bank's transaction identifier (Asmachta) when present —
 * it drives stable dedupe ids on the app side.
 *
 * Throws a coarse `ScrapeError(502, "parse_error")` if the library reports
 * success but hands back a structurally invalid row, so a bad payload becomes a
 * clean 502 rather than leaking an exception.
 */
export function mapResult(result: LibraryResult): ScrapeResponse {
  const accounts: ScrapedAccount[] = [];
  const transactions: ScrapedTransaction[] = [];

  for (const account of result.accounts ?? []) {
    if (typeof account.accountNumber !== "string" || account.accountNumber.length === 0) {
      throw new ScrapeError(502, "parse_error");
    }
    const externalId = account.accountNumber;

    accounts.push({
      externalId,
      label: externalId,
      institution: DISCOUNT_INSTITUTION,
      rawType: account.cardType ?? null,
    });

    for (const txn of account.txns ?? []) {
      if (typeof txn.date !== "string" || typeof txn.chargedAmount !== "number") {
        throw new ScrapeError(502, "parse_error");
      }
      transactions.push({
        accountExternalId: externalId,
        rawDate: txn.date,
        rawMerchant: txn.description ?? "",
        // Signed magnitude as text; direction left to the mapper's fallback.
        rawAmount: String(txn.chargedAmount),
        rawDirection: null,
        rawCategory: txn.category ?? null,
        reference: txn.identifier != null ? String(txn.identifier) : null,
      });
    }
  }

  return { accounts, transactions };
}
