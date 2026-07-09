import Fastify, { type FastifyError, type FastifyInstance } from "fastify";
import { makeAuthHook } from "./auth.js";
import { loggerOptions } from "./logger.js";
import { healthRoutes } from "./routes/health.js";
import { scrapeRoutes } from "./routes/scrape.js";
import type { ScrapeFn } from "./scraper/types.js";

export interface BuildAppOptions {
  authToken: string;
  /** TLS material. When present the instance serves HTTPS (SECURITY.md R3). */
  https?: { key: Buffer; cert: Buffer };
  /**
   * Injectable scrape seam. Tests pass a fake (no Chrome/network); production
   * omits it and the route lazily loads the real headless-Chrome scraper.
   */
  scrape?: ScrapeFn;
  /** Overall wall-clock budget for one scrape (ms). */
  scrapeTimeoutMs?: number;
}

const baseOptions = {
  logger: loggerOptions,
  // R8: never auto-log requests (which could include headers). We emit our own
  // allow-listed line in the onResponse hook below instead.
  disableRequestLogging: true,
  // Small body limit: the real payload is a tiny credentials object; refuse
  // anything large to blunt memory-exhaustion attempts.
  bodyLimit: 64 * 1024,
  // R4: strict, predictable validation. Reject unexpected fields (do NOT
  // silently strip them, which is Fastify's default), and do not coerce types
  // or inject defaults — the input must be exactly the allow-listed shape.
  ajv: {
    customOptions: {
      removeAdditional: false,
      coerceTypes: false,
      useDefaults: false,
      allErrors: false,
    },
  },
} as const;

/**
 * Construct the Fastify app. Kept as a pure factory (no listen, no process env)
 * so tests can drive it in-memory via `app.inject()` — no network, no Chrome.
 */
export function buildApp(opts: BuildAppOptions): FastifyInstance {
  const app: FastifyInstance = opts.https
    ? Fastify({ ...baseOptions, https: opts.https })
    : Fastify(baseOptions);

  const authHook = makeAuthHook(opts.authToken);

  // R9: log ONLY an allow-list of non-sensitive fields, and only on response.
  // No bodies, no headers, no query, no credentials.
  app.addHook("onResponse", async (req, reply) => {
    req.log.info(
      {
        route: req.routeOptions.url ?? req.url,
        method: req.method,
        statusCode: reply.statusCode,
        durationMs: Math.round(reply.elapsedTime),
      },
      "request",
    );
  });

  // Safe error handler: never leak a stack trace, raw message, or any payload
  // to the client or the logs (T2/T4). Clients get a coarse, stable code.
  app.setErrorHandler((err: FastifyError, req, reply) => {
    const status = err.statusCode ?? 500;
    req.log.error(
      { route: req.routeOptions.url ?? req.url, statusCode: status, code: err.code ?? "unknown" },
      "request_error",
    );
    const clientError = status >= 500 ? "internal_error" : (err.code ?? "bad_request");
    reply.code(status).send({ error: clientError });
  });

  app.register(healthRoutes);
  app.register(scrapeRoutes, { authHook, scrape: opts.scrape, scrapeTimeoutMs: opts.scrapeTimeoutMs });

  return app;
}
