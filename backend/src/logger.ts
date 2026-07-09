import type { FastifyServerOptions } from "fastify";

/**
 * Logger configuration (Fastify's built-in logger is pino).
 *
 * SECURITY.md R8/R9: logs MUST NOT contain request bodies, headers,
 * credentials, cookies, or tokens — only an allow-list of safe fields.
 *
 * Two layers enforce this:
 *  1. app.ts sets `disableRequestLogging: true` and logs an explicit allow-list
 *     of fields in an onResponse hook (route/method/status/duration). Bodies and
 *     headers are simply never handed to the logger.
 *  2. `redact` below is defence-in-depth: if a sensitive field ever slips into a
 *     log object (a future regression), it is stripped rather than printed.
 */
export const loggerOptions: FastifyServerOptions["logger"] = {
  level: process.env.LOG_LEVEL?.trim() || "info",
  redact: {
    paths: [
      "req.headers.authorization",
      "headers.authorization",
      "authorization",
      "password",
      "token",
      "credentials",
      "*.password",
      "*.token",
      "*.authorization",
      "credentials.password",
      "credentials.id",
      "credentials.num",
    ],
    remove: true,
  },
};
