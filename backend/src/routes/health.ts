import type { FastifyInstance } from "fastify";

/**
 * GET /health — unauthenticated liveness probe.
 *
 * Deliberately returns only `{ status: "ok" }`: no version, build hash, uptime,
 * config, or dependency state. A liveness endpoint must reveal nothing an
 * attacker could use for fingerprinting or recon.
 */
export async function healthRoutes(app: FastifyInstance): Promise<void> {
  app.get("/health", async () => ({ status: "ok" as const }));
}
