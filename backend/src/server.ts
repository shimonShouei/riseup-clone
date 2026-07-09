import { readFileSync } from "node:fs";
import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";

/**
 * Process entry point. Loads config from the environment (failing closed if the
 * bearer token is absent), wires TLS if configured, and binds to the configured
 * host — localhost by default (SECURITY.md R2).
 */
async function main(): Promise<void> {
  const config = loadConfig();

  const https =
    config.tlsCertPath && config.tlsKeyPath
      ? {
          // R7: TLS key is read from a runtime-mounted file (0600, non-root),
          // never baked into the image or committed.
          key: readFileSync(config.tlsKeyPath),
          cert: readFileSync(config.tlsCertPath),
        }
      : undefined;

  const app = buildApp({
    authToken: config.authToken,
    scrapeTimeoutMs: config.scrapeTimeoutMs,
    // `scrape` intentionally omitted: the route lazily loads the real
    // headless-Chrome scraper on first use, so the process boots fast and does
    // not require Chrome present just to serve /health.
    ...(https ? { https } : {}),
  });

  if (!https) {
    // R3: the app is designed to serve HTTPS. Plain HTTP is only acceptable
    // behind a TLS-terminating reverse proxy that presents the *pinned* cert.
    app.log.warn(
      "TLS is DISABLED (no TLS_CERT_PATH/TLS_KEY_PATH). Serve HTTPS directly or terminate TLS at a pinned reverse proxy (R3).",
    );
  }

  if (config.bindHost === "0.0.0.0" || config.bindHost === "::") {
    // R2: a wildcard bind is allowed only inside an isolated container whose
    // ports are published to loopback/mesh. Warn loudly on any host.
    app.log.warn(
      "Binding to a WILDCARD address. Ensure no public port is published; only the mesh/loopback must reach this service (R2).",
    );
  }

  try {
    await app.listen({ host: config.bindHost, port: config.port });
  } catch (err) {
    // Do not print the error object (could carry paths/config); log a coarse line.
    app.log.error({ code: (err as { code?: string }).code ?? "listen_failed" }, "failed to start");
    process.exit(1);
  }
}

void main();
