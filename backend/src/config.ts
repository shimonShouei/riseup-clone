/**
 * Runtime configuration — sourced entirely from the environment.
 *
 * Security rationale:
 *  - The bearer token and TLS material are NEVER hardcoded or committed; they
 *    arrive via env / Docker secrets / mounted files at boot (SECURITY.md R7).
 *  - We fail *closed*: if the auth token is missing the process refuses to
 *    start, so there is no "accidentally open" mode.
 *  - Bind host defaults to loopback (SECURITY.md R2); it must be opted out of
 *    explicitly, and even then the server warns.
 */

export interface AppConfig {
  readonly authToken: string;
  readonly bindHost: string;
  readonly port: number;
  readonly tlsCertPath: string | undefined;
  readonly tlsKeyPath: string | undefined;
  readonly logLevel: string;
  /** Overall wall-clock budget for one scrape before it is aborted (ms). */
  readonly scrapeTimeoutMs: number;
}

const MIN_TOKEN_LENGTH = 32;
/** Default overall scrape budget: 3 minutes. A hung scrape must not wedge the box. */
const DEFAULT_SCRAPE_TIMEOUT_MS = 180_000;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const authToken = env.AUTH_TOKEN?.trim();
  if (!authToken) {
    // R1/R7: no token, no service. Never invent a default.
    throw new Error(
      "AUTH_TOKEN is required and must be injected via env/secret (never hardcoded). Refusing to start.",
    );
  }
  if (authToken.length < MIN_TOKEN_LENGTH) {
    throw new Error(
      `AUTH_TOKEN must be at least ${MIN_TOKEN_LENGTH} chars of high-entropy secret (e.g. \`openssl rand -hex 32\`).`,
    );
  }

  // R2: default to localhost. A wildcard bind is possible but discouraged and
  // is warned about at listen time (see server.ts).
  const bindHost = env.BIND_HOST?.trim() || "127.0.0.1";

  const port = Number.parseInt(env.PORT ?? "8443", 10);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`PORT must be an integer 1-65535 (got ${env.PORT ?? "undefined"}).`);
  }

  const tlsCertPath = env.TLS_CERT_PATH?.trim() || undefined;
  const tlsKeyPath = env.TLS_KEY_PATH?.trim() || undefined;
  if (Boolean(tlsCertPath) !== Boolean(tlsKeyPath)) {
    throw new Error(
      "TLS requires BOTH TLS_CERT_PATH and TLS_KEY_PATH, or neither (plain HTTP behind a pinned proxy).",
    );
  }

  const logLevel = env.LOG_LEVEL?.trim() || "info";

  const scrapeTimeoutRaw = env.SCRAPE_TIMEOUT_MS?.trim();
  const scrapeTimeoutMs = scrapeTimeoutRaw ? Number.parseInt(scrapeTimeoutRaw, 10) : DEFAULT_SCRAPE_TIMEOUT_MS;
  if (!Number.isInteger(scrapeTimeoutMs) || scrapeTimeoutMs < 1000 || scrapeTimeoutMs > 600_000) {
    throw new Error(`SCRAPE_TIMEOUT_MS must be an integer 1000-600000 (got ${scrapeTimeoutRaw ?? "undefined"}).`);
  }

  return { authToken, bindHost, port, tlsCertPath, tlsKeyPath, logLevel, scrapeTimeoutMs };
}
