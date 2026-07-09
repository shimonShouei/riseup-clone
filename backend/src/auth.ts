import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";

/**
 * Constant-time bearer-token comparison.
 *
 * We hash both the presented token and the expected token to a fixed 32-byte
 * SHA-256 digest before comparing. This means:
 *  - `timingSafeEqual` always receives equal-length buffers (it throws on a
 *    length mismatch), and
 *  - the comparison leaks no timing information about the token contents *or*
 *    its length (a naive `===` or a raw length check would).
 */
export function tokensMatch(provided: string, expected: string): boolean {
  const a = createHash("sha256").update(provided).digest();
  const b = createHash("sha256").update(expected).digest();
  return timingSafeEqual(a, b);
}

function extractBearer(header: string | undefined): string | null {
  if (!header) return null;
  const match = /^Bearer (.+)$/.exec(header);
  return match?.[1] ?? null;
}

/**
 * Build the bearer-auth hook bound to the configured token.
 *
 * SECURITY.md R1: reject any request without a valid token with 401, BEFORE any
 * other work. Registered as an `onRequest` hook so it runs before body parsing,
 * schema validation, and the route handler — nothing credential-bearing is
 * touched until auth passes.
 *
 * The token itself is never logged. The 401 gives no hint about whether the
 * token was missing vs. wrong, to avoid an auth oracle.
 */
export function makeAuthHook(expectedToken: string) {
  return async function authHook(req: FastifyRequest, reply: FastifyReply): Promise<FastifyReply | void> {
    const token = extractBearer(req.headers.authorization);
    if (token === null || !tokensMatch(token, expectedToken)) {
      return reply.code(401).send({ error: "unauthorized" });
    }
    // token valid → fall through to validation + handler.
  };
}
