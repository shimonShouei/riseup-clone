/**
 * Types shared across the scrape seam.
 *
 * IMPORTANT: this module (and everything it imports) MUST stay free of the heavy
 * `israeli-bank-scrapers` / `puppeteer` dependency so the mapper, error mapping,
 * and route can be unit-tested under any Node version with no Chrome. The single
 * module that touches the real library is `discount.real.ts`.
 */

/** Discount login material. Held in RAM only for one scrape (SECURITY.md R5/R6). */
export interface DiscountCredentials {
  readonly id: string;
  readonly password: string;
  readonly num: string;
}

/**
 * Output DTOs — MUST deserialize into the app's
 * `com.riseup.clone.domain.scraper.BankScraper` shapes so `ScrapeMapper` is
 * reused unchanged. Values are RAW strings; the app does all parsing.
 */
export interface ScrapedAccount {
  readonly externalId: string;
  readonly label: string;
  readonly institution: string;
  readonly rawType: string | null;
}

export interface ScrapedTransaction {
  readonly accountExternalId: string;
  readonly rawDate: string;
  readonly rawMerchant: string;
  readonly rawAmount: string;
  readonly rawDirection: string | null;
  readonly rawCategory: string | null;
  readonly reference: string | null;
}

export interface ScrapeResponse {
  readonly accounts: ScrapedAccount[];
  readonly transactions: ScrapedTransaction[];
}

/**
 * The minimal subset of `israeli-bank-scrapers`' `ScraperScrapingResult` we
 * consume. Declared locally (not imported from the library) so the mapper/tests
 * never pull in Chrome. The real seam casts the library result to this shape.
 */
export interface LibraryTransaction {
  readonly identifier?: string | number;
  readonly date: string;
  readonly chargedAmount: number;
  readonly originalAmount?: number;
  readonly description: string;
  readonly memo?: string;
  readonly status?: string;
  readonly category?: string;
  readonly type?: string;
}

export interface LibraryAccount {
  readonly accountNumber: string;
  readonly cardType?: string;
  readonly txns?: LibraryTransaction[];
}

export interface LibraryResult {
  readonly success: boolean;
  readonly accounts?: LibraryAccount[];
  readonly errorType?: string;
  readonly errorMessage?: string;
}

export interface ScrapeRequest {
  readonly credentials: DiscountCredentials;
  readonly startDate: Date;
}

/**
 * The injectable seam. In production this is `realDiscountScrape` (drives a
 * headless Chrome via the library and tears it down). In tests it is a fake
 * returning a library-shaped result — no network, no Chrome.
 */
export type ScrapeFn = (req: ScrapeRequest) => Promise<LibraryResult>;
