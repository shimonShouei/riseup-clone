import type { FastifyInstance, onRequestHookHandler } from "fastify";
import { ScrapeError } from "../scraper/errors.js";
import { scrapeErrorFromLibrary } from "../scraper/errors.js";
import { mapResult } from "../scraper/mapper.js";
import type { DiscountCredentials, ScrapeFn } from "../scraper/types.js";

/**
 * Request body contract for POST /scrape.
 *
 * SECURITY.md R4: accept ONLY an allow-listed `companyId` (Discount for now) and
 * validate/whitelist every field. `additionalProperties: false` rejects anything
 * unexpected; there are no free-form URLs. Fastify validates against this schema
 * *after* auth (R1) and rejects a bad body with 400 — but note it never echoes
 * the offending values, only JSON-pointer paths, so credentials cannot leak via
 * a validation error.
 */
const scrapeBodySchema = {
  type: "object",
  required: ["companyId", "credentials"],
  additionalProperties: false,
  properties: {
    companyId: { type: "string", enum: ["discount"] },
    credentials: {
      type: "object",
      required: ["id", "password", "num"],
      additionalProperties: false,
      properties: {
        id: { type: "string", minLength: 1, maxLength: 64 },
        password: { type: "string", minLength: 1, maxLength: 256 },
        num: { type: "string", minLength: 1, maxLength: 64 },
      },
    },
    // Optional ISO date lower-bound (YYYY-MM-DD). Pattern only — no `format`
    // dependency needed.
    startDate: { type: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$" },
  },
} as const;

/** Typed view of the validated body (schema guarantees the shape at runtime). */
interface ScrapeBody {
  companyId: "discount";
  credentials: DiscountCredentials;
  startDate?: string;
}

interface ScrapeRoutesOptions {
  authHook: onRequestHookHandler;
  /**
   * Injectable scrape seam (SECURITY.md R6 teardown lives inside it). Tests pass
   * a fake; production defaults to the real headless-Chrome scraper, loaded
   * lazily so this module — and the tests that import it — stay free of
   * puppeteer unless a real scrape is actually performed.
   */
  scrape?: ScrapeFn;
  /** Overall wall-clock budget for one scrape (ms). */
  scrapeTimeoutMs?: number;
}

/** Default overall request timeout if the caller does not configure one. */
const DEFAULT_SCRAPE_TIMEOUT_MS = 180_000;
/** How far back to fetch when the request omits `startDate`. */
const DEFAULT_LOOKBACK_MONTHS = 12;

let defaultScrapePromise: Promise<ScrapeFn> | undefined;
/** Lazily import the real (Chrome-driving) scraper only when first needed. */
async function getDefaultScrape(): Promise<ScrapeFn> {
  defaultScrapePromise ??= import("../scraper/discount.real.js").then((m) => m.realDiscountScrape);
  return defaultScrapePromise;
}

/** Resolve the scrape window start from the (validated) request or a default. */
function resolveStartDate(startDate: string | undefined): Date {
  if (startDate) {
    // Schema guarantees YYYY-MM-DD; parse as UTC midnight.
    return new Date(`${startDate}T00:00:00.000Z`);
  }
  const d = new Date();
  d.setUTCMonth(d.getUTCMonth() - DEFAULT_LOOKBACK_MONTHS);
  return d;
}

/**
 * Race a scrape against an overall timeout so a hung Chrome/bank session cannot
 * wedge the service. On timeout we reject with a coarse 504; the underlying
 * scrape's own `finally` still tears the browser down when it eventually settles.
 */
function withTimeout(work: Promise<unknown>, timeoutMs: number): Promise<unknown> {
  let timer: NodeJS.Timeout;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => reject(new ScrapeError(504, "timeout")), timeoutMs);
  });
  return Promise.race([work, timeout]).finally(() => clearTimeout(timer));
}

export async function scrapeRoutes(app: FastifyInstance, opts: ScrapeRoutesOptions): Promise<void> {
  const timeoutMs = opts.scrapeTimeoutMs ?? DEFAULT_SCRAPE_TIMEOUT_MS;

  app.post(
    "/scrape",
    {
      onRequest: opts.authHook, // R1: auth runs before validation + handler.
      schema: { body: scrapeBodySchema },
    },
    async (request, reply) => {
      // Auth + shape validation have passed. Credentials live ONLY in this local
      // for the duration of the call — never logged (R8), never persisted (R5),
      // never echoed in an error (T2/T4).
      const body = request.body as ScrapeBody;
      const credentials = body.credentials;
      const startDate = resolveStartDate(body.startDate);

      const scrapeFn = opts.scrape ?? (await getDefaultScrape());

      try {
        const result = await withTimeout(scrapeFn({ credentials, startDate }), timeoutMs);
        const libraryResult = result as Awaited<ReturnType<ScrapeFn>>;

        if (!libraryResult.success) {
          // Login/bank failure: map to a coarse outcome. errorMessage is dropped.
          throw scrapeErrorFromLibrary(libraryResult.errorType);
        }

        // R5: transient JSON only — nothing is written to disk/db/cache.
        return await reply.code(200).send(mapResult(libraryResult));
      } catch (err) {
        if (err instanceof ScrapeError) {
          // R8/R9: log the coarse outcome only — no creds, no message, no stack.
          request.log.error(
            { route: "/scrape", outcome: err.outcome, statusCode: err.httpStatus },
            "scrape_failed",
          );
          return await reply.code(err.httpStatus).send({ error: err.outcome });
        }
        // Unexpected (e.g. Chrome launch crash): let the global error handler emit
        // a coarse 500 `internal_error` with no leak.
        throw err;
      }
    },
  );
}
