package com.riseup.clone.data

/**
 * Persists *which* bank the household has connected — the single source of truth
 * for the "am I connected?" question the app's start destination hinges on.
 *
 * Split behind an interface for the same reason [com.riseup.clone.data.security.CredentialStore]
 * and [com.riseup.clone.data.sync.LastSyncedStore] are: the production impl
 * ([PreferencesConnectionStore]) touches Android (SharedPreferences), while
 * [InMemoryConnectionStore] lets the connect-bank decision logic be unit-tested on
 * the JVM without a device.
 *
 * Why a dedicated flag rather than "does [CredentialStore] have a secret?": the
 * credential store is keyed by institution and cannot *enumerate* institutions, so
 * on a cold start there'd be no way to learn which bank to re-register the scraper
 * for. Recording the connected institution name here answers both "connected?" and
 * "connected to what?" in one read. Only set once the *first sync succeeds*, so a
 * saved-but-never-synced attempt doesn't route the user past onboarding.
 */
interface ConnectionStore {

    /** The connected institution's name, or `null` if no bank is connected. */
    suspend fun connectedInstitution(): String?

    /** Mark [institution] as connected (survives restarts). */
    suspend fun setConnected(institution: String)

    /** Forget the connection — used by sign-out / reset. */
    suspend fun disconnect()
}
