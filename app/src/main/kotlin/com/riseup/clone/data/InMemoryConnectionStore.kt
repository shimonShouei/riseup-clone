package com.riseup.clone.data

import java.util.concurrent.atomic.AtomicReference

/**
 * Non-persistent [ConnectionStore] holding the connected source only in process
 * memory. The JVM-testable fake and a safe stand-in for previews; never the
 * production store, since it forgets the value when the process dies.
 */
class InMemoryConnectionStore(initial: String? = null) : ConnectionStore {

    private val value = AtomicReference(initial)

    override suspend fun connectedInstitution(): String? = value.get()

    override suspend fun setConnected(institution: String) {
        value.set(institution)
    }

    override suspend fun disconnect() {
        value.set(null)
    }
}
