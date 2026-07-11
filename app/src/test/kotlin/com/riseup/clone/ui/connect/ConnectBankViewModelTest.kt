package com.riseup.clone.ui.connect

import com.riseup.clone.data.ConnectionStore
import com.riseup.clone.data.InMemoryConnectionStore
import com.riseup.clone.data.InMemoryScraperUrlStore
import com.riseup.clone.data.ScraperUrlStore
import com.riseup.clone.data.importer.ImportResult
import com.riseup.clone.data.importer.StatementImporter
import com.riseup.clone.data.remote.CsvFetchException
import com.riseup.clone.data.remote.CsvFetcher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
 * Pure-JVM tests for the first-run "set up your cash flow" decision logic, driven
 * with `runTest` and in-memory fakes ([InMemoryConnectionStore], a fake
 * [StatementImporter]). No Compose or Room — the whole point of keeping the logic in
 * the ViewModel over interfaces. There is no live bank connection anymore: both the
 * real path (file import) and the demo path (sample) go through the same importer.
 *
 * An [UnconfinedTestDispatcher] is installed as Main so `viewModelScope` launches
 * run eagerly and their effects are observable synchronously in the assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectBankViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A [StatementImporter] that returns a canned outcome and records what it was handed. */
    private class FakeImporter(var result: ImportResult) : StatementImporter {
        var lastCsv: String? = null
        var lastInstitution: String? = null
        var importCount = 0
        override suspend fun import(csvText: String, institution: String, accountLabel: String): ImportResult {
            importCount++
            lastCsv = csvText
            lastInstitution = institution
            return result
        }
    }

    /** A [CsvFetcher] that returns a canned body or throws, and records the URL it got. */
    private class FakeFetcher(
        private val body: String? = "FETCHED-CSV",
        private val error: CsvFetchException? = null,
    ) : CsvFetcher {
        var lastUrl: String? = null
        var callCount = 0
        override suspend fun fetchCsv(url: String): String {
            callCount++
            lastUrl = url
            error?.let { throw it }
            return body!!
        }
    }

    private fun vm(
        connection: ConnectionStore = InMemoryConnectionStore(),
        importer: StatementImporter = FakeImporter(ImportResult.Imported(0)),
        sampleCsv: () -> String = { "SAMPLE-CSV" },
        fetcher: CsvFetcher = FakeFetcher(),
        urlStore: ScraperUrlStore = InMemoryScraperUrlStore(),
    ) = ConnectBankViewModel(
        connection,
        importer,
        sampleCsv,
        csvFetcher = fetcher,
        scraperUrlStore = urlStore,
    )

    // ---- File import (real path) ----------------------------------------------

    @Test
    fun `importStatement surfaces the count, persists the connection, and hands the csv over`() = runTest {
        val connection = InMemoryConnectionStore()
        val importer = FakeImporter(ImportResult.Imported(7))
        val viewModel = vm(connection = connection, importer = importer)

        viewModel.importStatement("Date,Description,Debit,Credit,Balance,Reference\n…")

        val success = assertIs<ImportUiState.Success>(viewModel.importState.value)
        assertEquals(7, success.count)
        assertEquals(ConnectBankViewModel.IMPORTED_INSTITUTION, importer.lastInstitution)
        assertEquals(ConnectBankViewModel.IMPORTED_INSTITUTION, connection.connectedInstitution())
        // A successful import persists the connection but does not route until the
        // user taps through explicitly.
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `importStatement failure surfaces an error and neither persists nor routes`() = runTest {
        val connection = InMemoryConnectionStore()
        val viewModel = vm(connection = connection, importer = FakeImporter(ImportResult.Failed("Malformed CSV")))

        viewModel.importStatement("garbage")

        val error = assertIs<ImportUiState.Error>(viewModel.importState.value)
        assertEquals("Malformed CSV", error.message)
        assertNull(connection.connectedInstitution())
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `reportImportError surfaces an unreadable-file error`() = runTest {
        val viewModel = vm()

        viewModel.reportImportError("Couldn't read the selected file.")

        assertIs<ImportUiState.Error>(viewModel.importState.value)
    }

    @Test
    fun `continueToDashboard routes into the dashboard`() = runTest {
        val viewModel = vm(importer = FakeImporter(ImportResult.Imported(3)))

        viewModel.importStatement("csv")
        assertFalse(viewModel.connected.value == true)

        viewModel.continueToDashboard()

        assertEquals(true, viewModel.connected.value)
    }

    // ---- Fetch from scraper (real path, LAN source) ---------------------------

    @Test
    fun `fetchFromScraper feeds the fetched csv to the importer and surfaces the count`() = runTest {
        val connection = InMemoryConnectionStore()
        val importer = FakeImporter(ImportResult.Imported(5))
        val fetcher = FakeFetcher(body = "FETCHED-CSV")
        val viewModel = vm(connection = connection, importer = importer, fetcher = fetcher)

        viewModel.fetchFromScraper("http://192.168.1.23:8788/statement.csv")

        val success = assertIs<ImportUiState.Success>(viewModel.fetchState.value)
        assertEquals(5, success.count)
        assertEquals("http://192.168.1.23:8788/statement.csv", fetcher.lastUrl)
        assertEquals("FETCHED-CSV", importer.lastCsv)
        assertEquals(ConnectBankViewModel.SCRAPER_INSTITUTION, importer.lastInstitution)
        assertEquals(ConnectBankViewModel.SCRAPER_INSTITUTION, connection.connectedInstitution())
        // Like the file import, a successful fetch persists but waits for the tap-through.
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `fetchFromScraper error surfaces an error and never attempts an import`() = runTest {
        val connection = InMemoryConnectionStore()
        val importer = FakeImporter(ImportResult.Imported(9))
        val fetcher = FakeFetcher(error = CsvFetchException("Couldn't reach the scraper."))
        val viewModel = vm(connection = connection, importer = importer, fetcher = fetcher)

        viewModel.fetchFromScraper("http://10.0.0.5:8788/statement.csv")

        val error = assertIs<ImportUiState.Error>(viewModel.fetchState.value)
        assertEquals("Couldn't reach the scraper.", error.message)
        assertEquals(0, importer.importCount)
        assertNull(connection.connectedInstitution())
        assertFalse(viewModel.connected.value == true)
    }

    @Test
    fun `fetchFromScraper trims and persists the url so it prefills next time`() = runTest {
        val urlStore = InMemoryScraperUrlStore()
        val viewModel = vm(urlStore = urlStore)

        viewModel.fetchFromScraper("  http://192.168.1.9:8788/statement.csv  ")

        assertEquals("http://192.168.1.9:8788/statement.csv", urlStore.savedUrl())
        assertEquals("http://192.168.1.9:8788/statement.csv", viewModel.savedScraperUrl.value)
    }

    @Test
    fun `saved scraper url is prefilled on launch`() = runTest {
        val urlStore = InMemoryScraperUrlStore(initial = "http://192.168.1.50:8788/statement.csv")

        val viewModel = vm(urlStore = urlStore)

        assertEquals("http://192.168.1.50:8788/statement.csv", viewModel.savedScraperUrl.value)
    }

    // ---- Auto-fetch on open (the seamless normal case) -------------------------

    @Test
    fun `auto-fetch on open with a saved url imports and routes to the dashboard`() = runTest {
        // Not yet connected, but a URL is remembered from a previous run.
        val connection = InMemoryConnectionStore()
        val importer = FakeImporter(ImportResult.Imported(11))
        val fetcher = FakeFetcher(body = "CLOUD-CSV")
        val urlStore = InMemoryScraperUrlStore(initial = "https://dropbox/f.csv?dl=1")

        val viewModel = vm(
            connection = connection,
            importer = importer,
            fetcher = fetcher,
            urlStore = urlStore,
        )

        // No button press: the saved URL was fetched and imported at launch.
        assertEquals("https://dropbox/f.csv?dl=1", fetcher.lastUrl)
        assertEquals("CLOUD-CSV", importer.lastCsv)
        assertEquals(ConnectBankViewModel.SCRAPER_INSTITUTION, connection.connectedInstitution())
        // A successful auto-fetch routes straight into the dashboard.
        assertEquals(true, viewModel.connected.value)
    }

    @Test
    fun `auto-fetch failure on open surfaces an error and shows the connect screen`() = runTest {
        val connection = InMemoryConnectionStore()
        val importer = FakeImporter(ImportResult.Imported(1))
        val fetcher = FakeFetcher(error = CsvFetchException("Couldn't reach the statement URL."))
        val urlStore = InMemoryScraperUrlStore(initial = "https://dropbox/f.csv?dl=1")

        val viewModel = vm(
            connection = connection,
            importer = importer,
            fetcher = fetcher,
            urlStore = urlStore,
        )

        val error = assertIs<ImportUiState.Error>(viewModel.fetchState.value)
        assertEquals("Couldn't reach the statement URL.", error.message)
        assertEquals(0, importer.importCount)
        assertNull(connection.connectedInstitution())
        // Auto-fetch failed → fall back to the connect screen with the URL prefilled for retry.
        assertEquals(false, viewModel.connected.value)
        assertEquals("https://dropbox/f.csv?dl=1", viewModel.savedScraperUrl.value)
    }

    @Test
    fun `already connected with a saved url refreshes in the background and stays on the dashboard`() = runTest {
        val connection = InMemoryConnectionStore(initial = "Scraper")
        val importer = FakeImporter(ImportResult.Imported(3))
        val fetcher = FakeFetcher(body = "FRESH-CSV")
        val urlStore = InMemoryScraperUrlStore(initial = "https://dropbox/f.csv?dl=1")

        val viewModel = vm(
            connection = connection,
            importer = importer,
            fetcher = fetcher,
            urlStore = urlStore,
        )

        // The dashboard is shown immediately, and a background refresh still runs.
        assertEquals(true, viewModel.connected.value)
        assertEquals(1, fetcher.callCount)
        assertEquals("FRESH-CSV", importer.lastCsv)
    }

    @Test
    fun `refreshFromSavedUrl re-fetches and imports without changing the screen`() = runTest {
        val connection = InMemoryConnectionStore(initial = "Scraper")
        val importer = FakeImporter(ImportResult.Imported(2))
        val fetcher = FakeFetcher(body = "REFRESH-CSV")
        val urlStore = InMemoryScraperUrlStore(initial = "https://dropbox/f.csv?dl=1")

        val viewModel = vm(
            connection = connection,
            importer = importer,
            fetcher = fetcher,
            urlStore = urlStore,
        )
        // init already auto-fetched once (already connected → background refresh).
        val beforeCalls = fetcher.callCount

        viewModel.refreshFromSavedUrl()

        assertEquals(beforeCalls + 1, fetcher.callCount)
        assertEquals("REFRESH-CSV", importer.lastCsv)
        // A manual refresh never bounces the user off the dashboard.
        assertEquals(true, viewModel.connected.value)
    }

    @Test
    fun `refreshFromSavedUrl is a no-op when no url is remembered`() = runTest {
        val fetcher = FakeFetcher()
        val viewModel = vm(fetcher = fetcher)

        viewModel.refreshFromSavedUrl()

        assertEquals(0, fetcher.callCount)
    }

    // ---- Sample data (demo path) ----------------------------------------------

    @Test
    fun `loadSample imports the sample csv, persists, and routes straight to the dashboard`() = runTest {
        val connection = InMemoryConnectionStore()
        val importer = FakeImporter(ImportResult.Imported(42))
        val viewModel = vm(connection = connection, importer = importer, sampleCsv = { "THE-SAMPLE" })

        viewModel.loadSample()

        assertEquals("THE-SAMPLE", importer.lastCsv)
        assertEquals(ConnectBankViewModel.SAMPLE_INSTITUTION, importer.lastInstitution)
        assertEquals(ConnectBankViewModel.SAMPLE_INSTITUTION, connection.connectedInstitution())
        // The demo path routes immediately on success.
        assertEquals(true, viewModel.connected.value)
    }

    @Test
    fun `loadSample failure surfaces a sample error and does not route`() = runTest {
        val connection = InMemoryConnectionStore()
        val viewModel = vm(connection = connection, importer = FakeImporter(ImportResult.Failed("bad sample")))

        viewModel.loadSample()

        val error = assertIs<ImportUiState.Error>(viewModel.sampleState.value)
        assertEquals("bad sample", error.message)
        assertNull(connection.connectedInstitution())
        assertFalse(viewModel.connected.value == true)
    }

    // ---- Launch routing --------------------------------------------------------

    @Test
    fun `already connected on launch routes to dashboard`() = runTest {
        val connection = InMemoryConnectionStore(initial = "Imported statement")

        val viewModel = vm(connection = connection)

        assertEquals(true, viewModel.connected.value)
    }

    @Test
    fun `not connected on launch shows the connect screen`() = runTest {
        val viewModel = vm()

        assertEquals(false, viewModel.connected.value)
        assertTrue(viewModel.importState.value is ImportUiState.Idle)
    }
}
