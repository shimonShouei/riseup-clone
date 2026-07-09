package com.riseup.clone.ui.connect

import com.riseup.clone.data.BackendConfigStore
import com.riseup.clone.data.ConnectionStore
import com.riseup.clone.data.InMemoryBackendConfigStore
import com.riseup.clone.data.InMemoryConnectionStore
import com.riseup.clone.data.scraper.RemoteBankScraper
import com.riseup.clone.data.security.CredentialStore
import com.riseup.clone.data.security.InMemoryCredentialStore
import com.riseup.clone.data.sync.BankConnector
import com.riseup.clone.data.sync.RemoteBankConnector
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
 * the in-memory fakes ([InMemoryCredentialStore], [InMemoryConnectionStore],
 * [InMemoryBackendConfigStore], and a fake [BankConnector]). No Compose, Room, or
 * Keystore — the whole point of keeping the logic in the ViewModel over interfaces.
 * The Compose screen and the real Keystore/Room/CSV/remote-HTTP wiring can't run
 * headless on this build machine (see the [org.junit.Ignore]d
 * [com.riseup.clone.data.PersistedTransactionRepositoryTest]).
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
    private val discount = RemoteBankScraper.DISCOUNT_INSTITUTION

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

    /** Records which institution the ViewModel asked a connector for, and hands back a fake. */
    private class RecordingSelector(private val connector: BankConnector) : (String) -> BankConnector {
        val requested = mutableListOf<String>()
        override fun invoke(institution: String): BankConnector {
            requested += institution
            return connector
        }
    }

    private fun synced() = SyncOutcome.Synced(newTransactions = 3, at = Instant.parse("2026-07-07T10:00:00Z"))

    private fun vm(
        credentials: CredentialStore = InMemoryCredentialStore(),
        connection: ConnectionStore = InMemoryConnectionStore(),
        backendConfig: BackendConfigStore = InMemoryBackendConfigStore(),
        connectorFor: (String) -> BankConnector = { FakeConnector(synced()) },
    ) = ConnectBankViewModel(credentials, connection, backendConfig, connectorFor)

    // ---- Validation (CSV path) -------------------------------------------------

    @Test
    fun `submit with blank fields is rejected without saving or syncing`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connector = FakeConnector(synced())
        val viewModel = vm(credentials = credentials, connectorFor = { connector })

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
        val viewModel = vm(connectorFor = { connector })

        viewModel.submit(institution = institution, username = "user", password = "")

        assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(0, connector.syncCount)
    }

    // ---- Success path (CSV) ----------------------------------------------------

    @Test
    fun `successful connect saves credentials, marks connected, and routes to dashboard`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connection = InMemoryConnectionStore()
        val connector = FakeConnector(synced())
        val viewModel = vm(credentials, connection, connectorFor = { connector })

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
        val viewModel = vm(credentials, connection, connectorFor = { connector })

        viewModel.submit(institution = institution, username = "user", password = "wrong")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(SyncErrorReason.INVALID_CREDENTIALS, form.syncError)
        assertEquals("rejected", form.errorMessage)
        assertEquals(ConnectError.INVALID_CREDENTIALS, form.connectError)
        // Credentials were saved, but a failed first sync must not route past onboarding.
        assertNotNull(credentials.load(institution))
        assertNull(connection.connectedInstitution())
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `transient sync retry also surfaces an error state`() = runTest {
        val connector = FakeConnector(SyncOutcome.Retry(SyncErrorReason.NETWORK, "offline"))
        val viewModel = vm(connectorFor = { connector })

        viewModel.submit(institution = institution, username = "user", password = "secret")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(SyncErrorReason.NETWORK, form.syncError)
        assertEquals(ConnectError.NETWORK, form.connectError)
        assertFalse(viewModel.connected.value == true)
    }

    // ---- Discount (remote) path ------------------------------------------------

    @Test
    fun `discount submit with any blank field is rejected before saving or syncing`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connector = FakeConnector(synced())
        val viewModel = vm(credentials = credentials, connectorFor = { connector })

        // Blank num.
        viewModel.submitDiscount(id = "123", password = "pw", num = "", backendUrl = "https://x", backendToken = "tok")
        assertNotNull(assertIs<ConnectBankUiState.Form>(viewModel.uiState.value).validationError)

        // Blank backend token.
        viewModel.submitDiscount(id = "123", password = "pw", num = "77", backendUrl = "https://x", backendToken = "")
        assertNotNull(assertIs<ConnectBankUiState.Form>(viewModel.uiState.value).validationError)

        assertEquals(0, connector.syncCount)
        assertNull(credentials.load(discount))
        assertNull(credentials.load(RemoteBankConnector.BACKEND_TOKEN_KEY))
    }

    @Test
    fun `discount submit with a non-https backend url is rejected before saving or syncing`() = runTest {
        // R3: reject a cleartext (http) backend URL up front — over http there is no
        // TLS, so cert pinning never applies and credentials would go in the clear.
        val credentials = InMemoryCredentialStore()
        val connector = FakeConnector(synced())
        val viewModel = vm(credentials = credentials, connectorFor = { connector })

        viewModel.submitDiscount(
            id = "123",
            password = "pw",
            num = "77",
            backendUrl = "http://backend:8443",
            backendToken = "tok",
        )

        assertNotNull(assertIs<ConnectBankUiState.Form>(viewModel.uiState.value).validationError)
        assertEquals(0, connector.syncCount)
        assertNull(credentials.load(discount))
        assertNull(credentials.load(RemoteBankConnector.BACKEND_TOKEN_KEY))
    }

    @Test
    fun `successful discount connect saves three fields, backend url and token, and routes`() = runTest {
        val credentials = InMemoryCredentialStore()
        val connection = InMemoryConnectionStore()
        val backendConfig = InMemoryBackendConfigStore()
        val selector = RecordingSelector(FakeConnector(synced()))
        val viewModel = vm(credentials, connection, backendConfig, selector)

        viewModel.submitDiscount(
            id = "  305111111 ",
            password = "s3cret",
            num = " 42 ",
            backendUrl = " https://host.ts.net:8443 ",
            backendToken = "bearer-xyz",
        )

        // Credentials stored as id -> username, num -> extra, trimmed where appropriate.
        val creds = assertNotNull(credentials.load(discount))
        assertEquals("305111111", creds.username)
        assertEquals("s3cret", creds.password)
        assertEquals("42", creds.extra[RemoteBankScraper.NUM_KEY])

        // Backend token in the Keystore-backed store under the reserved key.
        assertEquals("bearer-xyz", credentials.load(RemoteBankConnector.BACKEND_TOKEN_KEY)?.password)
        // Backend URL in the non-secret config store (trimmed).
        assertEquals("https://host.ts.net:8443", backendConfig.baseUrl())

        // Routed through the connector selected for Discount, and connected.
        assertEquals(discount, selector.requested.last())
        assertEquals(discount, connection.connectedInstitution())
        assertEquals(ConnectBankUiState.Connected, viewModel.uiState.value)
        assertEquals(true, viewModel.connected.value)
    }

    @Test
    fun `discount network failure surfaces the network connect error`() = runTest {
        val connector = FakeConnector(SyncOutcome.Retry(SyncErrorReason.NETWORK, "Could not reach backend"))
        val viewModel = vm(connectorFor = { connector })

        viewModel.submitDiscount(id = "1", password = "pw", num = "2", backendUrl = "https://x", backendToken = "tok")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(ConnectError.NETWORK, form.connectError)
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `discount otp_required surfaces the OTP state and does not retry-loop`() = runTest {
        // M2-5 maps the backend 409 to Failure(OTP_REQUIRED, "otp_required"), a
        // permanent failure, so it reaches the ViewModel as a Failed (not a Retry).
        val connector = FakeConnector(
            SyncOutcome.Failed(SyncErrorReason.OTP_REQUIRED, RemoteBankScraper.OTP_REQUIRED_MESSAGE),
        )
        val viewModel = vm(connectorFor = { connector })

        viewModel.submitDiscount(id = "1", password = "pw", num = "2", backendUrl = "https://x", backendToken = "tok")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(ConnectError.OTP_REQUIRED, form.connectError)
        // Exactly one sync attempt — no retry-loop on OTP.
        assertEquals(1, connector.syncCount)
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `discount invalid credentials surfaces the invalid-credentials connect error`() = runTest {
        val connector = FakeConnector(SyncOutcome.Failed(SyncErrorReason.INVALID_CREDENTIALS, "invalid_credentials"))
        val viewModel = vm(connectorFor = { connector })

        viewModel.submitDiscount(id = "1", password = "pw", num = "2", backendUrl = "https://x", backendToken = "tok")

        val form = assertIs<ConnectBankUiState.Form>(viewModel.uiState.value)
        assertEquals(ConnectError.INVALID_CREDENTIALS, form.connectError)
        assertFalse(viewModel.connected.value == true)
    }

    // ---- Connector selection ---------------------------------------------------

    @Test
    fun `csv institution is routed through the connector selected for it`() = runTest {
        val selector = RecordingSelector(FakeConnector(synced()))
        val viewModel = vm(connectorFor = selector)

        viewModel.submit(institution = institution, username = "user", password = "secret")

        assertEquals(institution, selector.requested.last())
    }

    // ---- Launch routing --------------------------------------------------------

    @Test
    fun `already connected on launch routes to dashboard and re-registers the provider`() = runTest {
        val connection = InMemoryConnectionStore(initial = institution)
        val connector = FakeConnector(synced())

        val viewModel = vm(connection = connection, connectorFor = { connector })

        assertEquals(true, viewModel.connected.value)
        // Provider re-registered so background/manual syncs work after a cold start.
        assertEquals(institution, connector.registered)
    }

    @Test
    fun `not connected on launch shows the connect form`() = runTest {
        val connector = FakeConnector(synced())
        val viewModel = vm(connectorFor = { connector })

        assertEquals(false, viewModel.connected.value)
        assertNull(connector.registered)
        assertTrue(viewModel.uiState.value is ConnectBankUiState.Form)
    }
}
