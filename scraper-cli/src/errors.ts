/**
 * Human-readable, non-leaky messages for a failed scrape.
 *
 * We surface a clear reason to the operator (this is a local, single-user tool)
 * but never forward the library's raw error message, a stack trace, or any
 * credential material.
 */

/**
 * Map an `israeli-bank-scrapers` `errorType` (present when `success === false`)
 * to a clear, actionable message. Unknown/absent types fall back to a generic
 * bank-side failure.
 */
export function messageFromLibraryError(errorType: string | undefined): string {
  switch (errorType) {
    case "INVALID_PASSWORD":
      return "Login rejected: check DISCOUNT_ID / DISCOUNT_PASSWORD / DISCOUNT_NUM in .env.";
    case "CHANGE_PASSWORD":
      return "The bank requires a password change; log in at the bank's site, then retry.";
    case "ACCOUNT_BLOCKED":
      return "The account is blocked by the bank; unlock it at the bank's site, then retry.";
    case "TWO_FACTOR_RETRIEVER_MISSING":
      return "The bank demanded a one-time code (2FA) that this unattended tool cannot provide.";
    case "TIMEOUT":
      return "The scrape timed out talking to the bank. Try again later.";
    default:
      return "The scrape failed talking to the bank (network or bank-side error). Try again later.";
  }
}
