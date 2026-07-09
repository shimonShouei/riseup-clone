package com.riseup.clone.data.sync

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-persistent [LastSyncedStore] holding the timestamp only in process memory.
 * The JVM-testable fake (mirrors [com.riseup.clone.data.security.InMemoryCredentialStore])
 * and a safe stand-in for previews; never the production store, since it forgets
 * the value when the process dies.
 */
class InMemoryLastSyncedStore(initial: Instant? = null) : LastSyncedStore {

    private val value = AtomicReference(initial)

    override suspend fun read(): Instant? = value.get()

    override suspend fun write(at: Instant) {
        value.set(at)
    }
}
