import { CompanyTypes, createScraper } from "israeli-bank-scrapers";
import puppeteer, { type Browser } from "puppeteer";
import { makeDiscountScrape } from "./discount.js";
import type { LibraryResult, ScrapeFn } from "./types.js";

/**
 * The ONLY module that imports `israeli-bank-scrapers` + `puppeteer`. Everything
 * else talks to the `ScrapeFn` seam, so tests and the mapper never load Chrome.
 *
 * Why we own the browser instead of letting the library manage it:
 * SECURITY.md R5/R6 require a guaranteed teardown of Chrome after every scrape
 * (no lingering profile/cache/session). By launching Chrome ourselves and
 * passing it in with `skipCloseBrowser: true`, the `finally` in `makeDiscountScrape`
 * is the single, always-run teardown point. We launch headless (equivalent to
 * the library's `showBrowser: false`) and keep `verbose: false` so no request
 * bodies, cookies, or bank traffic are ever logged (R8).
 */
export const realDiscountScrape: ScrapeFn = makeDiscountScrape({
  launch: () =>
    puppeteer.launch({
      headless: true,
      // Hardened defaults for running Chromium inside a locked-down container.
      args: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"],
    }),

  createScraper: (browser, startDate, navTimeoutMs) => {
    const scraper = createScraper({
      companyId: CompanyTypes.discount,
      startDate,
      // R8: never enable the library's verbose logging.
      verbose: false,
      // We own Chrome's lifecycle for a guaranteed teardown (R6). `browser` is a
      // real puppeteer Browser, narrowed to BrowserHandle by the seam.
      browser: browser as unknown as Browser,
      skipCloseBrowser: true,
      // Per-page navigation timeout; the route adds an overall request timeout.
      defaultTimeout: navTimeoutMs,
    });
    return {
      scrape: (credentials) =>
        // The library's richer result is a structural superset of LibraryResult.
        scraper.scrape(credentials) as unknown as Promise<LibraryResult>,
    };
  },
});
