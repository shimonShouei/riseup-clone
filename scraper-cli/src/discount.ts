import type { DiscountCredentials, LibraryResult, ScrapeFn, ScrapeRequest } from "./types.js";

/**
 * Browser + scraper lifecycle for a single Discount scrape, with the actual
 * Chrome/library calls factored out behind a small dependency seam.
 *
 * This module deliberately does NOT import `puppeteer` or `israeli-bank-scrapers`
 * (that lives in `discount.real.ts`). Keeping the lifecycle here means the
 * teardown contract can be unit-tested with fakes — no Chrome, no network, any
 * Node version.
 */

/** Minimal browser handle — only what we need to guarantee teardown. */
export interface BrowserHandle {
  close(): Promise<void>;
}

/** Minimal scraper handle — one login+fetch returning a library-shaped result. */
export interface ScraperHandle {
  scrape(credentials: DiscountCredentials): Promise<LibraryResult>;
}

export interface DiscountDeps {
  /** Launch a headless browser we own for exactly one scrape. */
  launch: () => Promise<BrowserHandle>;
  /** Build the library scraper bound to our browser + the requested window. */
  createScraper: (browser: BrowserHandle, startDate: Date, navTimeoutMs: number) => ScraperHandle;
  /** Per-page navigation timeout handed to the library. */
  navTimeoutMs?: number;
}

/** Default per-page navigation timeout (library `defaultTimeout`). */
export const DEFAULT_NAV_TIMEOUT_MS = 60_000;

/**
 * Build a `ScrapeFn` from the browser/scraper seam.
 *
 * We launch a browser we own, run exactly one scrape, and ALWAYS close it in
 * `finally` — even when the scrape throws — so no Chrome profile, cache, cookie
 * jar, or bank session lingers on disk. Credentials are passed straight through
 * as function arguments and never stored, logged, or written anywhere.
 */
export function makeDiscountScrape(deps: DiscountDeps): ScrapeFn {
  const navTimeoutMs = deps.navTimeoutMs ?? DEFAULT_NAV_TIMEOUT_MS;

  return async function discountScrape({ credentials, startDate }: ScrapeRequest): Promise<LibraryResult> {
    const browser = await deps.launch();
    try {
      const scraper = deps.createScraper(browser, startDate, navTimeoutMs);
      return await scraper.scrape(credentials);
    } finally {
      // Teardown must never mask the scrape's real outcome, and a failed close
      // must not itself throw — swallow it (nothing sensitive is in the error).
      await browser.close().catch(() => {
        /* best-effort teardown */
      });
    }
  };
}
