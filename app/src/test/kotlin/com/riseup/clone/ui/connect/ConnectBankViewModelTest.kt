package com.riseup.clone.ui.connect

import com.riseup.clone.data.ConnectionStore
import com.riseup.clone.data.InMemoryConnectionStore
import com.riseup.clone.data.security.CredentialStore
import com.riseup.clone.data.security.InMemoryCredentialStore
import com.riseup.clone.data.sync.BankConnector
import com.riseup.clone.data.sync.SyncErrorReason
import com.riseup.clone.data.sync.SyncOutcome
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for the connect-bank decision logic, driven with `runTest` and
 * the in-memory fakes ([InMemoryCredentialStore], [InMemoryConnectionStore], and a
 * fake [BankConnector]). No Compose, Room, or Keystore — the whole point of keeping
 * the logic in the ViewModel over interfaces. The Compose screen and the real
 * Keystore/Room/CSV wiring can't run headless on this build machine (see the
 * [org.junit.Ignore]d [com.riseup.clone.data.PersistedTransactionRepositoryTest]).
 *
 * An [UnconfinedTestDispatcher] is installed as Main so `viewModelScope` launches
 * run eagerly and their effects are observable synchronously in the assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectBankViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val institution = "Bank Leumi"

    /** A [BankConnector] that returns a canned outcome and records what it was asked. */
    private class FakeConnector(var outcome: SyncOutcome) : BankConnector {
        var registered: String? = null
        var syncCount = 0
        override fun registerProvider(institution: String) { registered = institution }
        override suspend fun runSync(institution: String): SyncOutcome {
            syncCount++
            registered = institution
            return outcome
        }
    }

    private fun synced() = SyncOutcome.Synced(newTransactions = 3, at = Instant.parse("2026-07-07T10:00:00Z"))

    private fun vm(
        credentials: CredentialStore = InMemoryCredentialStore(),
        connection: ConnectionStore = InMemoryConnectionStore(),
        connector: BankConnector = FakeConnector(synced()),
    ) = ConnectBankViewModel(credentials, connection, connector)

    // ---- Validation ------------------------------------------------------------

    @Test
    fun `submit with blank fields is rejected without saving or syncing`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connector = FakeConnector(synced())
        val viewModel = vm(credentials = credentials, connector = connector)

        viewModel.submit(institution = "", username = "", password = "")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertNotNull(form.validationError)
        assertEquals(0, connector.syncCount)
        assertNull(credentials.load(institution))
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `submit with blank password is rejected`() = runTest {
        val connector = FakeConnector(synced())
        val viewModel = vm(connector = connector)

        viewModel.submit(institution = institution, username = "user", password = "")

        assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(0, connector.syncCount)
    }

    // ---- Success path ----------------------------------------------------------

    @Test
    fun `successful connect saves credentials, marks connected, and routes to dashboard`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connection = InMemoryConnectionStore()
        val connector = FakeConnector(synced())
        val viewModel = vm(credentials, connection, connector)

        viewModel.submit(institution = institution, username = "user", password = "secret")

        // save-credentials-then-connected
        assertNotNull(credentials.load(institution))
        // sync-success -> connected + navigate
        assertEquals(institution, connection.connectedInstitution())
        assertEquals(institution, connector.registered)
        assertEquals(ConnectBankUiState.Connected, viewModel.uiState.value)
        assertEquals(true, viewModel.connected.value)
    }

    // ---- Error path ------------------------------------------------------------

    @Test
    fun `sync failure surfaces an error and does not mark connected`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connection = InMemoryConnectionStore()
        val connector = FakeConnector(SyncOutcome.Failed(SyncErrorReason.INVALID_CREDENTIALS, "rejected"))
        val viewModel = vm(credentials, connection, connector)

        viewModel.submit(institution = institution, username = "user", password = "wrong")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(SyncErrorReason.INVALID_CREDENTIALS, form.syncError)
        assertEquals("rejected", form.errorMessage)
        // Credentials were saved, but a failed first sync must not route past onboarding.
        assertNotNull(credentials.load(institution))
        assertNull(connection.connectedInstitution())
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `transient sync retry also surfaces an error state`() = runTest {
        val connector = FakeConnector(SyncOutcome.Retry(SyncErrorReason.NETWORK, "offline"))
        val viewModel = vm(connector = connector)

        viewModel.submit(institution = institution, username = "user", password = "secret")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(SyncErrorReason.NETWORK, form.syncError)
        assertFalse(viewModel.connected.value == true)
    }

    // ---- Launch routing --------------------------------------------------------

    @Test
    fun `already connected on launch routes to dashboard and re-registers the provider`() = runTest {
        val connection = InMemoryConnectionStore(initial = institution)
        val connector = FakeConnector(synced())

        val viewModel = vm(connection = connection, connector = connector)

        assertEquals(true, viewModel.connected.value)
        // Provider re-registered so background/manual syncs work after a cold start.
        assertEquals(institution, connector.registered)
    }

    @Test
    fun `not connected on launch shows the connect form`() = runTest {
        val connector = FakeConnector(synced())
        val viewModel = vm(connector = connector)

        assertEquals(false, viewModel.connected.value)
        assertNull(connector.registered)
        assertTrue(viewModel.uiState.value is ConnectBankUiState.Form)
    }
}
