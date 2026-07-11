import { writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { countTransactions, resultToCsv } from "./csv.js";
import { loadConfig } from "./config.js";
import { messageFromLibraryError } from "./errors.js";
import type { ScrapeFn } from "./types.js";

/**
 * Local Bank Discount scraper CLI.
 *
 * Reads credentials from a local `.env`, drives `israeli-bank-scrapers` headless
 * once, then either:
 *  - writes a CSV statement the app imports (default), or
 *  - `serve` subcommand: serves that CSV snapshot over plain HTTP on the LAN so
 *    the phone app can pull it (see `serve.ts`), or
 *  - `upload` subcommand: pushes the CSV to the user's own cloud storage and prints
 *    a stable download URL so the app can fetch it from ANY network (see `upload.ts`).
 *
 * Credentials live in memory only for the scrape and are never printed or served.
 */

/** Load `.env` into `process.env` if present (Node >=20.12 built-in; no dep). */
function loadDotEnv(): void {
  const loader = (process as unknown as { loadEnvFile?: (path?: string) => void }).loadEnvFile;
  if (typeof loader === "function") {
    try {
      loader(".env");
    } catch {
      // No .env file — fine, values may come from the real environment.
    }
  }
}

/** Core run, with the scrape seam injected so it stays testable without Chrome. */
export async function run(scrapeFn: ScrapeFn): Promise<number> {
  const config = loadConfig();

  const result = await scrapeFn({ credentials: config.credentials, startDate: config.startDate });

  if (!result.success) {
    console.error(`Scrape failed: ${messageFromLibraryError(result.errorType)}`);
    return 1;
  }

  const csv = resultToCsv(result);
  const outPath = resolve(config.outPath);
  await writeFile(outPath, csv, "utf8");

  const accountCount = result.accounts?.length ?? 0;
  const txCount = countTransactions(result);
  console.log(
    `Scraped ${accountCount} account(s), ${txCount} transaction(s).\n` +
      `Wrote CSV statement to: ${outPath}\n` +
      "Import this file in the app. (Categories import as \"Other\".)",
  );
  return 0;
}

async function main(): Promise<void> {
  loadDotEnv();
  const args = process.argv.slice(2);
  const isServe = args.includes("serve");
  const isUpload = args.includes("upload");
  try {
    // Lazily import the real (Chrome-driving) scraper so nothing here loads
    // puppeteer until an actual run is performed.
    const { realDiscountScrape } = await import("./discount.real.js");

    if (isServe) {
      // Scrape once, then keep serving the snapshot. Do NOT process.exit: the
      // running HTTP server keeps the event loop alive until Ctrl-C.
      const { runServe } = await import("./serve.js");
      await runServe(realDiscountScrape);
      return;
    }

    if (isUpload) {
      // Scrape once, upload the CSV to cloud storage, print the stable URL, exit.
      const { runUpload } = await import("./upload.js");
      const exitCode = await runUpload(realDiscountScrape);
      process.exit(exitCode);
    }

    const exitCode = await run(realDiscountScrape);
    process.exit(exitCode);
  } catch (err) {
    // Never print the error object wholesale — it could carry context. Print the
    // message only (config/errors modules keep credentials out of messages).
    const message = err instanceof Error ? err.message : String(err);
    console.error(`Error: ${message}`);
    process.exit(1);
  }
}

// Run only when executed directly (not when imported by a test).
if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("main.js")) {
  void main();
}
