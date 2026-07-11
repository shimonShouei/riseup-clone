import type { DiscountCredentials } from "./types.js";

/**
 * CLI configuration, sourced entirely from the environment (a local `.env`).
 *
 * Credentials are read into memory here and passed straight to the scrape seam;
 * they are never printed, logged, or written to disk. Validation errors name the
 * missing FIELD only — never its value.
 */
export interface CliConfig {
  readonly credentials: DiscountCredentials;
  readonly startDate: Date;
  readonly outPath: string;
}

/** How far back to fetch when `START_DATE` is omitted. */
const DEFAULT_LOOKBACK_MONTHS = 12;
/** Default output path for the CSV statement. */
export const DEFAULT_OUT_PATH = "./discount-statement.csv";
/** Default interface binding for serve mode — all interfaces, so the LAN phone can reach it. */
export const DEFAULT_SERVE_HOST = "0.0.0.0";
/** Default TCP port for serve mode. */
export const DEFAULT_SERVE_PORT = 8788;

/** Start-of-window default: ~12 months before now (UTC midnight). */
function defaultStartDate(now: Date = new Date()): Date {
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  d.setUTCMonth(d.getUTCMonth() - DEFAULT_LOOKBACK_MONTHS);
  return d;
}

/**
 * Parse and validate configuration from `env`. Throws an `Error` with a clear,
 * secret-free message if a required credential is missing or an optional value
 * is malformed.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): CliConfig {
  const id = env.DISCOUNT_ID?.trim();
  const password = env.DISCOUNT_PASSWORD?.trim();
  const num = env.DISCOUNT_NUM?.trim();

  const missing = [
    ["DISCOUNT_ID", id],
    ["DISCOUNT_PASSWORD", password],
    ["DISCOUNT_NUM", num],
  ]
    .filter(([, value]) => !value)
    .map(([name]) => name);
  if (missing.length > 0) {
    throw new Error(
      `Missing required credential(s) in .env: ${missing.join(", ")}. ` +
        "Copy .env.example to .env and fill them in.",
    );
  }

  let startDate: Date;
  const rawStart = env.START_DATE?.trim();
  if (rawStart) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(rawStart)) {
      throw new Error(`START_DATE must be an ISO date (YYYY-MM-DD); got '${rawStart}'.`);
    }
    startDate = new Date(`${rawStart}T00:00:00.000Z`);
    if (Number.isNaN(startDate.getTime())) {
      throw new Error(`START_DATE is not a valid date: '${rawStart}'.`);
    }
  } else {
    startDate = defaultStartDate();
  }

  const outPath = env.OUT?.trim() || DEFAULT_OUT_PATH;

  // Non-null assertions are safe: the `missing` check above guarantees presence.
  return {
    credentials: { id: id!, password: password!, num: num! },
    startDate,
    outPath,
  };
}

/** Serve-mode network binding, sourced from the environment. */
export interface ServeConfig {
  readonly host: string;
  readonly port: number;
}

/**
 * Parse serve-mode network config from `env`. `SERVE_HOST`/`SERVE_PORT` override
 * the defaults (`0.0.0.0` / `8788`). Throws a clear message on a malformed port.
 * No credentials are involved here.
 */
export function loadServeConfig(env: NodeJS.ProcessEnv = process.env): ServeConfig {
  const host = env.SERVE_HOST?.trim() || DEFAULT_SERVE_HOST;

  let port = DEFAULT_SERVE_PORT;
  const rawPort = env.SERVE_PORT?.trim();
  if (rawPort) {
    const parsed = Number(rawPort);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
      throw new Error(`SERVE_PORT must be an integer between 1 and 65535; got '${rawPort}'.`);
    }
    port = parsed;
  }

  return { host, port };
}
