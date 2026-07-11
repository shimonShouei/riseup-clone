package com.riseup.clone.ui.connect

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.riseup.clone.data.ConnectionStore
import com.riseup.clone.data.InMemoryScraperUrlStore
import com.riseup.clone.data.PreferencesConnectionStore
import com.riseup.clone.data.PreferencesScraperUrlStore
import com.riseup.clone.data.ScraperUrlStore
import com.riseup.clone.data.importer.ImportResult
import com.riseup.clone.data.importer.LedgerStatementImporter
import com.riseup.clone.data.importer.StatementImporter
import com.riseup.clone.data.local.LedgerDatabase
import com.riseup.clone.data.remote.CsvFetchException
import com.riseup.clone.data.remote.CsvFetcher
import com.riseup.clone.data.remote.HttpUrlConnectionCsvFetcher
import com.riseup.clone.data.sync.AppSync
import com.riseup.clone.data.sync.RoomSyncLedgerStore
import com.riseup.clone.data.sync.SyncState
import com.riseup.clone.domain.scraper.SampleStatementCsv
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Decision logic for the first-run "connect your data" screen. There is no live
 * bank connection, no networking, and no stored bank credentials anymore — the app
 * gets real transactions by importing a CSV statement (produced by the user's bank
 * export or the local `scraper-cli/`). This ViewModel drives two independent paths,
 * both of which land rows in the same Room ledger the dashboard reads:
 *
 * 1. **Import statement (CSV)** — the real path: raw text the user picked from a
 *    file, run through [StatementImporter].
 * 2. **Statement URL (cloud or LAN)** — the same real path, but the CSV text is pulled
 *    over HTTPS/HTTP from any URL ([CsvFetcher]) — the scraper's cloud upload (Dropbox)
 *    or its LAN `serve` endpoint — then fed through the *same* importer. The URL is
 *    remembered via [ScraperUrlStore] and entered ONCE; thereafter it is fetched
 *    automatically on every app open (see [init]/[refreshFromSavedUrl]).
 * 3. **Load sample data** — the demo path: a realistic multi-month sample statement
 *    ([SampleStatementCsv]) fed through the *same* importer.
 *
 * Auto-fetch on open: if a statement URL is remembered, the ViewModel fetches and
 * imports it in the background at launch, so the normal case needs no button press.
 * A first successful fetch persists the connection (via [ConnectionStore]) so later
 * restarts route straight to the dashboard and auto-refresh.
 *
 * The logic is expressed over interfaces ([ConnectionStore], [StatementImporter])
 * so every branch is unit-testable on the JVM with in-memory fakes — no Compose or
 * Room needed. The composable is a thin state-in / events-out shell.
 *
 * On a successful import (real or sample) we persist the connection via
 * [ConnectionStore], so a cold restart routes straight to the dashboard, and publish
 * an [AppSync] success so an already-open dashboard reloads.
 *
 * [connected] is the app-level routing signal (see MainActivity): `null` while the
 * launch check is in flight, `true` → dashboard, `false` → show this screen. It is
 * derived from [ConnectionStore] so it persists across restarts.
 */
class ConnectBankViewModel(
    private val connectionStore: ConnectionStore,
    private val statementImporter: StatementImporter,
    private val sampleCsv: () -> String = { SampleStatementCsv.forToday(LocalDate.now()) },
    private val clock: () -> Instant = Instant::now,
    private val csvFetcher: CsvFetcher = HttpUrlConnectionCsvFetcher(),
    private val scraperUrlStore: ScraperUrlStore = InMemoryScraperUrlStore(),
) : ViewModel() {

    /** null = still determining at launch, true = go to dashboard, false = show connect screen. */
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()

    /** Phase of the file-import action. */
    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** Phase of the fetch-from-scraper action (independent of the file import). */
    private val _fetchState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val fetchState: StateFlow<ImportUiState> = _fetchState.asStateFlow()

    /** Last-used scraper URL, prefilled into the fetch field (empty until first fetch). */
    private val _savedScraperUrl = MutableStateFlow("")
    val savedScraperUrl: StateFlow<String> = _savedScraperUrl.asStateFlow()

    /** Phase of the load-sample-data action (independent of the file import). */
    private val _sampleState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val sampleState: StateFlow<ImportUiState> = _sampleState.asStateFlow()

    init {
        viewModelScope.launch {
            val alreadyConnected = connectionStore.connectedInstitution() != null
            val saved = scraperUrlStore.savedUrl().orEmpty()
            _savedScraperUrl.value = saved
            when {
                // No remembered URL: route by the persisted connection, prompt on first run.
                saved.isBlank() -> _connected.value = alreadyConnected
                // Already connected: show the dashboard now and refresh it in the background.
                alreadyConnected -> {
                    _connected.value = true
                    fetchAndImport(saved, routeOnResult = false)
                }
                // Remembered URL but not yet connected: try it seamlessly. Stay on the
                // launch spinner (connected == null) until the auto-fetch resolves, then
                // route in on success or fall back to the connect screen on failure.
                else -> fetchAndImport(saved, routeOnResult = true)
            }
        }
    }

    /**
     * Import a bank statement CSV the user picked from a file. Runs the raw text
     * through the same scrape → map → store pipeline the sample path uses (see
     * [StatementImporter]); de-dupe by stable id means re-importing the same file
     * doesn't double-count. On success we persist the connection and publish an
     * [AppSync] success so an already-open dashboard reloads, then surface the count.
     */
    fun importStatement(csvText: String, institution: String = IMPORTED_INSTITUTION) {
        viewModelScope.launch {
            _importState.value = ImportUiState.Importing
            _importState.value = runImport(csvText, institution)
        }
    }

    /**
     * Fetch a statement CSV from [url] (a cloud upload URL or a LAN `serve` URL) and run
     * it through the *same* importer the file path uses. Manual entry from the connect
     * screen: on success we persist the connection but wait for the explicit "View my
     * dashboard" tap, exactly like the file import ([routeOnResult] = false).
     */
    fun fetchFromScraper(url: String) {
        fetchAndImport(url, routeOnResult = false)
    }

    /**
     * Re-fetch and import the remembered statement URL on demand (the manual "Refresh").
     * A no-op if no URL is remembered yet. Runs in the background and, on success,
     * publishes an [AppSync] refresh so an open dashboard reloads; it never changes the
     * screen the user is on.
     */
    fun refreshFromSavedUrl() {
        val saved = _savedScraperUrl.value
        if (saved.isBlank()) return
        fetchAndImport(saved, routeOnResult = false)
    }

    /**
     * Shared fetch → import pipeline for the URL source. The URL is remembered
     * ([ScraperUrlStore]) — even on failure — so it prefills next time and the user only
     * types it once. A fetch failure surfaces as an [ImportUiState.Error] and no import
     * is attempted.
     *
     * [routeOnResult] governs launch-time auto-fetch only: when true (remembered URL, not
     * yet connected), a successful import routes into the dashboard and a failure falls
     * back to the connect screen. When false (manual fetch/refresh, or a background
     * refresh of an already-connected app), the current screen is left untouched.
     */
    private fun fetchAndImport(url: String, routeOnResult: Boolean) {
        viewModelScope.launch {
            val trimmed = url.trim()
            _fetchState.value = ImportUiState.Importing
            // Remember the endpoint regardless of outcome so a retry is one tap away.
            _savedScraperUrl.value = trimmed
            scraperUrlStore.saveUrl(trimmed)
            val csvText = try {
                csvFetcher.fetchCsv(trimmed)
            } catch (e: CsvFetchException) {
                _fetchState.value = ImportUiState.Error(e.userMessage)
                if (routeOnResult) _connected.value = false
                return@launch
            }
            val outcome = runImport(csvText, SCRAPER_INSTITUTION)
            _fetchState.value = outcome
            if (routeOnResult) _connected.value = outcome is ImportUiState.Success
        }
    }

    /**
     * Load the bundled multi-month sample statement (the demo path). Feeds
     * [SampleStatementCsv] through the same importer, persists the connection, and
     * routes straight to the dashboard on success.
     */
    fun loadSample() {
        viewModelScope.launch {
            _sampleState.value = ImportUiState.Importing
            when (val outcome = runImport(sampleCsv(), SAMPLE_INSTITUTION)) {
                is ImportUiState.Success -> {
                    _sampleState.value = outcome
                    _connected.value = true
                }
                else -> _sampleState.value = outcome
            }
        }
    }

    /** Shared import pipeline: import → on success persist connection + publish refresh. */
    private suspend fun runImport(csvText: String, institution: String): ImportUiState =
        when (val result = statementImporter.import(csvText, institution)) {
            is ImportResult.Imported -> {
                connectionStore.setConnected(institution)
                // Shared refresh signal: a live dashboard reloads the ledger on Success.
                AppSync.publish(SyncState.Success(clock()))
                ImportUiState.Success(result.newTransactions)
            }
            is ImportResult.Failed -> ImportUiState.Error(result.message)
        }

    /** Surface a file that couldn't be read (unreadable Uri / decode failure) as an import error. */
    fun reportImportError(message: String) {
        _importState.value = ImportUiState.Error(message)
    }

    /** Route into the dashboard after a successful file import. */
    fun continueToDashboard() {
        _connected.value = true
    }

    companion object {
        /** Institution name recorded for a user-imported statement. */
        const val IMPORTED_INSTITUTION = "Imported statement"

        /** Institution name recorded for the bundled sample/demo statement. */
        const val SAMPLE_INSTITUTION = "Sample data"

        /** Institution name recorded for a statement fetched from a URL (cloud or LAN). */
        const val SCRAPER_INSTITUTION = "Scraper"

        /** Builds a ViewModel wired to the real on-device stores. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ConnectBankViewModel(
                        connectionStore = PreferencesConnectionStore(app),
                        // Imports land in the same Room ledger the dashboard reads.
                        statementImporter = LedgerStatementImporter(
                            RoomSyncLedgerStore(LedgerDatabase.build(app).ledgerDao()),
                        ),
                        csvFetcher = HttpUrlConnectionCsvFetcher(),
                        scraperUrlStore = PreferencesScraperUrlStore(app),
                    ) as T
            }
        }
    }
}
