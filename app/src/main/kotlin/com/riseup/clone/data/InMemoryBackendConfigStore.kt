package com.riseup.clone.data

import java.util.concurrent.atomic.AtomicReference

/**
 * Non-persistent [BackendConfigStore] holding the backend base URL only in
 * process memory. The JVM-testable fake (mirrors [InMemoryConnectionStore]) and a
 * safe stand-in for previews; never the production store, since it forgets the
 * value when the process dies.
 */
class InMemoryBackendConfigStore(initial: String? = null) : BackendConfigStore {

    private val value = AtomicReference(initial)

    override fun baseUrl(): String? = value.get()

    override fun setBaseUrl(url: String) {
        value.set(url)
    }
}
