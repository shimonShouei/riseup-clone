package com.riseup.clone.data

/**
 * Persists the **non-secret** connection settings for the self-hosted scrape
 * backend that the remote (Bank Discount) connector needs — today just the base
 * URL (`https://<host-tailnet-name>:8443`).
 *
 * Deliberately separate from the secret material: the backend *bearer token* is a
 * secret and lives encrypted in
 * [com.riseup.clone.data.security.KeystoreCredentialStore]; the URL is public
 * metadata (a private-mesh host name, not a credential), so — exactly like
 * [ConnectionStore] — a plain prefs entry is the smallest thing that survives a
 * restart. Keeping it behind an interface lets the connect-bank decision logic be
 * unit-tested on the JVM with [InMemoryBackendConfigStore], and lets the
 * connector-selection lambda read the URL synchronously without a device.
 *
 * Reads/writes are synchronous by design: the connector-selection factory needs
 * the URL from a non-suspend context, and a [android.content.SharedPreferences]
 * string read is an in-memory cache hit after first load.
 */
interface BackendConfigStore {

    /** The stored backend base URL, or `null` if the user hasn't set one yet. */
    fun baseUrl(): String?

    /** Persist [url] as the backend base URL (survives restarts). Non-secret. */
    fun setBaseUrl(url: String)
}
