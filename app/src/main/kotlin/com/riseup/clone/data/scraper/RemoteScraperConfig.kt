package com.riseup.clone.data.scraper

import java.time.Duration

/**
 * Non-secret connection settings for the self-hosted scrape backend
 * (SECURITY.md §3.3 / NETWORK.md §2.4). Everything here is safe to compile into
 * the app: the base URL is a private mesh host and the pins are public-key
 * *hashes*, not secrets. The only secret — the bearer token — is NOT here; it is
 * held in the Android Keystore and supplied separately via a `tokenProvider`
 * (see [RemoteBankScraper]).
 *
 * ### Where the real values are set (M2-6 wires the connect UI to this)
 * - [baseUrl] MUST be `https://<host-tailnet-name>:8443` — the exact host string
 *   the backend's TLS cert has a SAN for (NETWORK.md §5). OkHttp does standard
 *   hostname verification against that SAN *in addition* to SPKI pinning, so a
 *   mismatch fails the handshake before pinning even helps.
 * - [primaryPin] / [backupPin] are `sha256/<base64>` SPKI pins. Derive them from
 *   the served cert (NETWORK.md §4.D):
 *   ```
 *   openssl s_client -connect <host>:8443 </dev/null 2>/dev/null \
 *     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | openssl enc -base64
 *   ```
 *   Ship a current + backup pin so a key rotation doesn't brick the app
 *   (SECURITY.md §3.3). Both are passed to OkHttp's `CertificatePinner`, which
 *   **fails closed** on mismatch — no fallback to system trust (R13).
 *
 * The [PLACEHOLDER] below is a compile-safe stand-in with obviously-fake values;
 * M2-6 replaces it (or injects a real config) once the tailnet host + cert exist.
 */
data class RemoteScraperConfig(
    /** `https://<host-tailnet-name>:8443` — must match the cert SAN (R3). */
    val baseUrl: String,
    /** Primary SPKI pin, `sha256/<base64>` (R13). */
    val primaryPin: String,
    /** Backup SPKI pin so cert rotation doesn't brick the app (SECURITY.md §3.3). */
    val backupPin: String,
    /** TCP connect timeout. */
    val connectTimeout: Duration = Duration.ofSeconds(30),
    /**
     * Response read timeout. Generous because a real scrape drives headless Chrome
     * on the backend (up to ~180s; see the backend's `DEFAULT_SCRAPE_TIMEOUT_MS`)
     * before the JSON comes back.
     */
    val readTimeout: Duration = Duration.ofSeconds(210),
) {
    companion object {
        /**
         * Obviously-fake placeholder so the app compiles and is testable before the
         * real tailnet host + cert exist. **Not usable against a real backend** — the
         * host won't resolve and the pins won't match. M2-6 supplies the real config.
         */
        val PLACEHOLDER = RemoteScraperConfig(
            baseUrl = "https://CHANGE-ME.example-tailnet.ts.net:8443",
            primaryPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            backupPin = "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
        )
    }
}
