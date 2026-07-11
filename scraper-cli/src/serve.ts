import { createServer, type Server } from "node:http";
import { networkInterfaces } from "node:os";
import { loadConfig, loadServeConfig } from "./config.js";
import { countTransactions, resultToCsv } from "./csv.js";
import { messageFromLibraryError } from "./errors.js";
import type { ScrapeFn } from "./types.js";

/**
 * "serve" mode: scrape ONCE at startup, then expose that snapshot over plain HTTP
 * on the LAN so the phone app can pull the CSV with one tap instead of the user
 * hand-copying a file.
 *
 * Security posture (documented in the README too): LAN-only, unauthenticated,
 * plaintext. That is acceptable because the machine serves only transaction data
 * on the user's own home network. Credentials are used solely for the scrape
 * (same as file mode) and are NEVER served or logged. We do not log request
 * bodies or headers.
 *
 * The heavy, hard-to-test pieces (routing, LAN-IP selection, URL building) are
 * pure functions so they can be unit-tested with no network and no bank.
 */

/** A fully-decided HTTP response (status + content type + body), from routing alone. */
export interface RouteResponse {
  readonly status: number;
  readonly contentType: string;
  readonly body: string;
}

/**
 * Map a request (method + url) to a response, given the pre-scraped CSV snapshot.
 * Pure and network-free so it is trivially unit-testable.
 *
 *  - `GET /statement.csv` -> 200, the CSV (`text/csv`).
 *  - `GET /health`        -> 200, `{"status":"ok"}` (`application/json`).
 *  - any other path       -> 404.
 *  - any non-GET/HEAD     -> 405.
 */
export function routeRequest(
  method: string | undefined,
  url: string | undefined,
  csv: string,
): RouteResponse {
  if (method !== "GET" && method !== "HEAD") {
    return { status: 405, contentType: "text/plain; charset=utf-8", body: "Method Not Allowed\n" };
  }
  // Strip any query string; we route on the path only.
  const path = (url ?? "/").split("?")[0] ?? "/";
  switch (path) {
    case "/statement.csv":
      return { status: 200, contentType: "text/csv; charset=utf-8", body: csv };
    case "/health":
      return { status: 200, contentType: "application/json; charset=utf-8", body: '{"status":"ok"}' };
    default:
      return { status: 404, contentType: "text/plain; charset=utf-8", body: "Not Found\n" };
  }
}

/** Minimal shape of one `os.networkInterfaces()` address entry we care about. */
interface InterfaceAddress {
  readonly family: string | number;
  readonly internal: boolean;
  readonly address: string;
}

/**
 * Pick the non-internal IPv4 addresses from an `os.networkInterfaces()`-shaped
 * object — i.e. the machine's LAN addresses the phone can actually reach. Handles
 * both the modern (`family: "IPv4"`) and legacy (`family: 4`) representations.
 */
export function pickLanIpv4s(interfaces: Record<string, InterfaceAddress[] | undefined>): string[] {
  const addresses: string[] = [];
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries ?? []) {
      const isIpv4 = entry.family === "IPv4" || entry.family === 4;
      if (isIpv4 && !entry.internal) {
        addresses.push(entry.address);
      }
    }
  }
  return addresses;
}

/**
 * Build the reachable `…/statement.csv` URLs to print at startup. When bound to a
 * wildcard host (`0.0.0.0` / `::`), list every LAN IPv4 the phone could use;
 * otherwise just the concrete host. Falls back to loopback if no LAN IP is found.
 */
export function reachableStatementUrls(host: string, port: number, lanIpv4s: string[]): string[] {
  const isWildcard = host === "0.0.0.0" || host === "::" || host === "";
  const hosts = isWildcard ? [...lanIpv4s] : [host];
  if (hosts.length === 0) {
    hosts.push("127.0.0.1");
  }
  return hosts.map((h) => `http://${h}:${port}/statement.csv`);
}

/**
 * Create (but do not start) an HTTP server that serves a single CSV snapshot.
 * Exposed for testing over loopback without a real scrape.
 */
export function createStatementServer(csv: string): Server {
  return createServer((req, res) => {
    // We deliberately do not log request bodies or headers.
    const { status, contentType, body } = routeRequest(req.method, req.url, csv);
    res.writeHead(status, { "Content-Type": contentType });
    if (req.method === "HEAD") {
      res.end();
    } else {
      res.end(body);
    }
  });
}

/** Seams so `runServe` can be exercised without real config, sockets, or clock. */
export interface ServeDeps {
  loadConfigFn?: typeof loadConfig;
  loadServeConfigFn?: typeof loadServeConfig;
  getInterfaces?: () => Record<string, InterfaceAddress[] | undefined>;
  log?: (message: string) => void;
  now?: () => Date;
}

/**
 * Core serve run: scrape ONCE, build the CSV, start the HTTP server bound to the
 * configured host/port, and print the reachable URL(s). Returns the running
 * `Server` (the event loop keeps the process alive until Ctrl-C). Restarting the
 * process re-scrapes — we intentionally do NOT re-scrape per request.
 */
export async function runServe(scrapeFn: ScrapeFn, deps: ServeDeps = {}): Promise<Server> {
  const config = (deps.loadConfigFn ?? loadConfig)();
  const serveConfig = (deps.loadServeConfigFn ?? loadServeConfig)();
  const log = deps.log ?? console.log;
  const now = deps.now ?? (() => new Date());
  const getInterfaces = deps.getInterfaces ?? networkInterfaces;

  const result = await scrapeFn({ credentials: config.credentials, startDate: config.startDate });
  if (!result.success) {
    // Same non-leaky message as file mode; then bail (no server).
    throw new Error(`Scrape failed: ${messageFromLibraryError(result.errorType)}`);
  }

  const csv = resultToCsv(result);
  const capturedAt = now();
  const server = createStatementServer(csv);

  await new Promise<void>((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(serveConfig.port, serveConfig.host, () => {
      server.off("error", rejectPromise);
      resolvePromise();
    });
  });

  const lanIpv4s = pickLanIpv4s(getInterfaces());
  const urls = reachableStatementUrls(serveConfig.host, serveConfig.port, lanIpv4s);
  const accountCount = result.accounts?.length ?? 0;
  const txCount = countTransactions(result);

  log(
    `Scraped ${accountCount} account(s), ${txCount} transaction(s) — snapshot captured ${capturedAt.toISOString()}.`,
  );
  log(
    `Serving on ${serveConfig.host}:${serveConfig.port}. This snapshot is served until you stop with Ctrl-C; restart to re-scrape.`,
  );
  for (const url of urls) {
    log(`Serving statement at: ${url}`);
  }
  log(`Health check: replace /statement.csv with /health.`);

  return server;
}
