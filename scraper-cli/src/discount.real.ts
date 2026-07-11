import { CompanyTypes, createScraper } from "israeli-bank-scrapers";
import puppeteer, { type Browser } from "puppeteer";
import { makeDiscountScrape } from "./discount.js";
import type { LibraryResult, ScrapeFn } from "./types.js";

/**
 * The ONLY module that imports `israeli-bank-scrapers` + `puppeteer`. Everything
 * else talks to the `ScrapeFn` seam, so the CSV builder and tests never load
 * Chrome.
 *
 * We own the browser instead of letting the library manage it so the `finally`
 * in `makeDiscountScrape` is the single, always-run teardown point (no lingering
 * profile/cache/session). We launch headless (equivalent to the library's
 * `showBrowser: false`) and keep `verbose: false` so no request bodies, cookies,
 * or bank traffic are ever logged.
 */
export const realDiscountScrape: ScrapeFn = makeDiscountScrape({
  launch: () =>
    puppeteer.launch({
      headless: true,
      args: ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"],
    }),

  createScraper: (browser, startDate, navTimeoutMs) => {
    const scraper = createScraper({
      companyId: CompanyTypes.discount,
      startDate,
      verbose: false,
      // We own Chrome's lifecycle for a guaranteed teardown. `browser` is a real
      // puppeteer Browser, narrowed to BrowserHandle by the seam.
      browser: browser as unknown as Browser,
      skipCloseBrowser: true,
      defaultTimeout: navTimeoutMs,
    });
    return {
      scrape: (credentials) =>
        // The library's richer result is a structural superset of LibraryResult.
        scraper.scrape(credentials) as unknown as Promise<LibraryResult>,
    };
  },
});
