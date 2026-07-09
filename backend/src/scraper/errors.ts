/**
 * Coarse, non-leaky failure mapping for the scrape endpoint.
 *
 * SECURITY.md T2/R8: clients (and logs) get a stable, coarse outcome code only —
 * never the library's raw error message, a stack trace, a URL, or any credential
 * material. The codes align with the app's `FailureReason` buckets so
 * `RemoteBankScraper` (M2-5) can branch without string-matching.
 */

export type ScrapeOutcome =
  | "invalid_credentials"
  | "account_blocked"
  | "otp_required"
  | "network"
  | "timeout"
  | "parse_error"
  | "internal_error";

/**
 * A mapped scrape failure carrying the HTTP status + coarse code to return.
 * Constructed only from an allow-listed outcome — it never wraps the underlying
 * error object, so nothing sensitive can ride along into a response or log.
 */
export class ScrapeError extends Error {
  readonly httpStatus: number;
  readonly outcome: ScrapeOutcome;

  constructor(httpStatus: number, outcome: ScrapeOutcome) {
    super(outcome);
    this.name = "ScrapeError";
    this.httpStatus = httpStatus;
    this.outcome = outcome;
  }
}

/**
 * Map an `israeli-bank-scrapers` `errorType` (present when `success === false`)
 * to a coarse outcome + status. Unknown/absent types fall back to a bank-side
 * network error (502): the request reached us fine, the failure is downstream.
 */
export function scrapeErrorFromLibrary(errorType: string | undefined): ScrapeError {
  switch (errorType) {
    // Login rejected — wrong password / bad id/num, or a forced password change
    // that blocks an automated login. Both are "your credentials won't get in".
    case "INVALID_PASSWORD":
    case "CHANGE_PASSWORD":
      return new ScrapeError(401, "invalid_credentials");

    // Bank locked the account (often anti-automation). Distinct so the app can
    // tell the user to unlock at the bank rather than re-enter credentials.
    case "ACCOUNT_BLOCKED":
      return new ScrapeError(403, "account_blocked");

    // The bank demanded OTP/2FA we cannot satisfy unattended. Surfaced distinctly
    // (SECURITY.md T10/R16) so the app shows a 2FA message and does NOT retry-loop.
    case "TWO_FACTOR_RETRIEVER_MISSING":
      return new ScrapeError(409, "otp_required");

    case "TIMEOUT":
      return new ScrapeError(504, "timeout");

    // GENERIC / GENERAL_ERROR and anything unrecognized: treat as a bank/network
    // failure. Coarse on purpose — we do not forward the library's message.
    default:
      return new ScrapeError(502, "network");
  }
}
