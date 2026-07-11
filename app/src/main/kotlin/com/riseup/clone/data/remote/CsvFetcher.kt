package com.riseup.clone.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A failure fetching statement CSV from a URL, carrying a [userMessage] the connect
 * screen can show verbatim. Every expected failure (bad URL, unreachable host,
 * timeout, non-2xx, read error) is mapped to one of these so the UI never has to
 * interpret a raw [IOException].
 */
class CsvFetchException(val userMessage: String, cause: Throwable? = null) :
    Exception(userMessage, cause)

/**
 * The network CSV *source* seam: fetch raw statement CSV text from any URL — the
 * scraper CLI's cloud upload (e.g. a Dropbox `…?dl=1` link, reachable from any
 * network) or its LAN `serve` endpoint (`GET http://<pc-lan-ip>:8788/statement.csv`).
 * This sits next to the file-picker source; both hand their text to the same
 * [com.riseup.clone.data.importer.StatementImporter].
 *
 * Kept behind an interface so the fetch → import decision logic in
 * [com.riseup.clone.ui.connect.ConnectBankViewModel] is unit-testable on the JVM with
 * a fake — the real [HttpUrlConnectionCsvFetcher] does a one-shot GET (it follows the
 * HTTPS redirects a Dropbox direct link uses) and is thin enough to leave to
 * device/manual testing.
 */
interface CsvFetcher {

    /**
     * GET [url] and return the response body as UTF-8 text. Throws
     * [CsvFetchException] (with a user-facing message) for any expected failure —
     * malformed URL, unreachable/timeout/IO, or a non-2xx status.
     */
    suspend fun fetchCsv(url: String): String
}

/**
 * Production [CsvFetcher] using the JDK's built-in [HttpURLConnection] — no OkHttp,
 * no new dependencies. A tiny one-shot GET on [Dispatchers.IO] with conservative
 * timeouts, mapping every failure onto a [CsvFetchException] the UI can show.
 */
class HttpUrlConnectionCsvFetcher(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : CsvFetcher {

    override suspend fun fetchCsv(url: String): String = withContext(Dispatchers.IO) {
        val parsed = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw CsvFetchException(
                "That doesn't look like a valid address. Paste the statement URL from the scraper " +
                    "(a cloud link, or e.g. http://192.168.1.23:8788/statement.csv).",
                e,
            )
        }

        val connection = try {
            parsed.openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw CsvFetchException(UNREACHABLE, e)
        }

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            val code = try {
                connection.responseCode
            } catch (e: SocketTimeoutException) {
                throw CsvFetchException(UNREACHABLE, e)
            } catch (e: IOException) {
                throw CsvFetchException(UNREACHABLE, e)
            }

            if (code !in 200..299) {
                throw CsvFetchException(
                    "The statement URL responded with HTTP $code. Is a statement available there yet?",
                )
            }

            try {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: SocketTimeoutException) {
                throw CsvFetchException(UNREACHABLE, e)
            } catch (e: IOException) {
                throw CsvFetchException("Reached the statement URL but couldn't read its response.", e)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val UNREACHABLE =
            "Couldn't reach the statement URL — check your connection, or that the scraper " +
                "has uploaded/served a statement."
    }
}
