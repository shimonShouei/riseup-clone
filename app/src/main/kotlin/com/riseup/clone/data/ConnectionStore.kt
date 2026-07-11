package com.riseup.clone.data

/**
 * Persists *which* source the household has connected — the single source of truth
 * for the "am I set up?" question the app's start destination hinges on.
 *
 * Split behind an interface so the production impl ([PreferencesConnectionStore])
 * can touch Android (SharedPreferences) while [InMemoryConnectionStore] lets the
 * first-run decision logic be unit-tested on the JVM without a device.
 *
 * Recording the connected source name answers both "set up?" and "set up as what?"
 * in one read. Only set once a statement import *succeeds*, so an abandoned/failed
 * import doesn't route the user past onboarding.
 */
interface ConnectionStore {

    /** The connected source's name, or `null` if nothing is connected. */
    suspend fun connectedInstitution(): String?

    /** Mark [institution] as connected (survives restarts). */
    suspend fun setConnected(institution: String)

    /** Forget the connection — used by sign-out / reset. */
    suspend fun disconnect()
}
