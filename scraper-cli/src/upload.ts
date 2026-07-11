import { loadConfig } from "./config.js";
import { countTransactions, resultToCsv } from "./csv.js";
import { DropboxUploader, loadDropboxConfig } from "./dropbox.js";
import { messageFromLibraryError } from "./errors.js";
import type { ScrapeFn } from "./types.js";

/**
 * "upload" mode: scrape ONCE, then push the CSV snapshot to the user's own cloud
 * storage and print a STABLE direct-download URL. The phone app pastes that URL in
 * once and can then fetch the statement from ANY network — not just the LAN that
 * `serve` mode is limited to.
 *
 * Only transactions leave the machine: bank credentials are used solely for the
 * scrape (exactly as in file/serve mode) and are never uploaded, printed, or logged.
 *
 * The cloud backend is abstracted behind the [Uploader] seam, so the scrape/CSV code
 * here never mentions Dropbox. Swapping in S3/R2/Google Drive later is a new
 * [Uploader] implementation with no change to this file.
 */

/** A file to push to cloud storage: raw bytes plus their content type. */
export interface UploadRequest {
  readonly content: Uint8Array;
  readonly contentType: string;
}

/** The outcome of an upload: the STABLE direct-download URL to paste into the app. */
export interface UploadResult {
  readonly downloadUrl: string;
}

/**
 * The cloud-storage seam. A provider takes file bytes and returns a stable
 * direct-download URL. The scrape/CSV code depends ONLY on this interface, so a new
 * backend (Amazon S3, Cloudflare R2, Google Drive, …) is a new class implementing
 * `upload` with no change to `upload.ts`. The destination (path/bucket/key) is
 * provider configuration held by the concrete uploader — not part of this generic
 * request — because each backend addresses storage differently.
 */
export interface Uploader {
  upload(req: UploadRequest): Promise<UploadResult>;
}

/** Content type of the statement we upload. */
export const CSV_CONTENT_TYPE = "text/csv; charset=utf-8";

/** Seams so `runUpload` can be exercised without real config, network, or a bank. */
export interface UploadDeps {
  loadConfigFn?: typeof loadConfig;
  /** Builds the cloud uploader. Default: Dropbox, configured from `.env`. */
  makeUploader?: () => Uploader;
  log?: (message: string) => void;
  logError?: (message: string) => void;
}

/**
 * Core upload run, with the scrape and the uploader injected so it is testable with
 * no bank, no Chrome, and no real cloud call. Constructs the uploader FIRST so a
 * missing/invalid cloud config fails fast, before the expensive scrape. Returns the
 * process exit code (0 on success).
 */
export async function runUpload(scrapeFn: ScrapeFn, deps: UploadDeps = {}): Promise<number> {
  const config = (deps.loadConfigFn ?? loadConfig)();
  const log = deps.log ?? console.log;
  const logError = deps.logError ?? console.error;

  // Build the uploader up front so a bad/missing cloud config fails before we scrape.
  const makeUploader = deps.makeUploader ?? (() => new DropboxUploader(loadDropboxConfig()));
  let uploader: Uploader;
  try {
    uploader = makeUploader();
  } catch (err) {
    logError(`Upload config error: ${messageOf(err)}`);
    return 1;
  }

  const result = await scrapeFn({ credentials: config.credentials, startDate: config.startDate });
  if (!result.success) {
    logError(`Scrape failed: ${messageFromLibraryError(result.errorType)}`);
    return 1;
  }

  const csv = resultToCsv(result);
  const content = new TextEncoder().encode(csv);

  let downloadUrl: string;
  try {
    ({ downloadUrl } = await uploader.upload({ content, contentType: CSV_CONTENT_TYPE }));
  } catch (err) {
    logError(`Upload failed: ${messageOf(err)}`);
    return 1;
  }

  const accountCount = result.accounts?.length ?? 0;
  const txCount = countTransactions(result);
  log(
    `Scraped ${accountCount} account(s), ${txCount} transaction(s).\n` +
      "Uploaded the CSV statement to your cloud storage (transactions only — no credentials).\n" +
      "Paste this URL into the app ONCE; it stays stable, and each upload refreshes what it serves:\n" +
      `  ${downloadUrl}`,
  );
  return 0;
}

/** Extract a message without ever forwarding a raw error object (may carry context). */
function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
