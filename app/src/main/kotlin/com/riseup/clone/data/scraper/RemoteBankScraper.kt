package com.riseup.clone.data.scraper

import com.riseup.clone.domain.scraper.BankScraper
import com.riseup.clone.domain.scraper.DateRange
import com.riseup.clone.domain.scraper.FailureReason
import com.riseup.clone.domain.scraper.ScrapeResult
import com.riseup.clone.domain.scraper.ScrapedAccount
import com.riseup.clone.domain.scraper.ScrapedTransaction
import com.riseup.clone.domain.scraper.ScraperCredentials
import java.io.IOException
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A [BankScraper] that fetches data from the self-hosted backend (M2-4) over a
 * **pinned HTTPS** connection instead of driving the bank directly. It is the
 * live counterpart to [com.riseup.clone.domain.scraper.CsvBankScraper]: it returns
 * the exact same raw DTOs, so everything downstream — `ScrapeMapper`, dedupe,
 * Room, sync — is reused unchanged. The *only* new concern here is networking.
 *
 * ### Security posture (SECURITY.md)
 * - **TLS + SPKI pinning, fail-closed (R13).** The production client is built by
 *   [buildPinnedClient] with an OkHttp `CertificatePinner` carrying a primary +
 *   backup pin from [RemoteScraperConfig]. A pin mismatch throws
 *   `SSLPeerUnverifiedException` (an `SSLException`) → mapped to
 *   [FailureReason.NETWORK]; there is **no** fallback to system trust.
 * - **Bearer token from the Keystore (R14).** The token is supplied lazily by
 *   [tokenProvider] (never hardcoded, never in [RemoteScraperConfig]) and attached
 *   as `Authorization: Bearer <token>` on every request.
 * - **No credential/token/body logging (R8/R15).** This class logs nothing. Creds
 *   live only in the request body for the duration of the call; the redacted
 *   [ScraperCredentials.toString] keeps them out of any accidental interpolation.
 *
 * ### Blocking on purpose
 * [scrape] is synchronous — the interface contract — and is already invoked inside
 * a `suspend` on `Dispatchers.IO` (see `LedgerSyncer`), so a blocking OkHttp
 * `execute()` is correct and avoids a needless callback hop.
 *
 * Construct via [forDiscount] in production; the primary constructor is `internal`
 * so JVM unit tests can point it at an OkHttp `MockWebServer` with a plain client.
 */
class RemoteBankScraper internal constructor(
    override val institution: String,
    private val endpoint: HttpUrl,
    private val client: OkHttpClient,
    private val tokenProvider: () -> String,
    private val companyId: String,
) : BankScraper {

    override fun scrape(credentials: ScraperCredentials, range: DateRange): ScrapeResult {
        // Build the request body. username -> id, password -> password,
        // extra["num"] -> num; startDate is the range's lower bound (YYYY-MM-DD).
        val requestJson = try {
            JSON.encodeToString(
                ScrapeRequestDto(
                    companyId = companyId,
                    credentials = ScrapeCredentialsDto(
                        id = credentials.username,
                        password = credentials.password,
                        num = credentials.extra[NUM_KEY].orEmpty(),
                    ),
                    startDate = range.from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                ),
            )
        } catch (e: Exception) {
            // Purely a programmer/serialization error building our own body.
            return ScrapeResult.Failure(FailureReason.UNKNOWN, "Could not build scrape request", e)
        }

        val token = try {
            tokenProvider()
        } catch (e: Exception) {
            // Token unavailable (e.g. Keystore read failed). Coarse, no secret leaked.
            return ScrapeResult.Failure(FailureReason.UNKNOWN, "Backend token unavailable", e)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response -> handleResponse(response) }
        } catch (e: SSLException) {
            // Includes SSLPeerUnverifiedException from a pin mismatch — fail closed (R13).
            ScrapeResult.Failure(FailureReason.NETWORK, "TLS error contacting backend", e)
        } catch (e: IOException) {
            // Connect/read timeouts, connection refused, DNS, resets, etc.
            ScrapeResult.Failure(FailureReason.NETWORK, "Could not reach backend", e)
        }
    }

    /** Map the HTTP response to a [ScrapeResult]. Bodies are coarse and non-leaky. */
    private fun handleResponse(response: Response): ScrapeResult {
        val bodyText = response.body?.string().orEmpty()
        return when (response.code) {
            200 -> parseSuccess(bodyText)
            // Wrong login / forced password change / bank-locked account.
            401, 403 -> ScrapeResult.Failure(
                FailureReason.INVALID_CREDENTIALS,
                errorCode(bodyText) ?: "invalid_credentials",
            )
            // OTP/2FA the backend can't satisfy unattended. Mapped to the dedicated,
            // permanent FailureReason.OTP_REQUIRED so both the connect UI and the
            // background worker treat it as fail-closed and MUST NOT retry-loop (R16).
            409 -> ScrapeResult.Failure(FailureReason.OTP_REQUIRED, OTP_REQUIRED_MESSAGE)
            // Reached the backend but the request/response was structurally wrong.
            400 -> ScrapeResult.Failure(FailureReason.PARSE_ERROR, errorCode(bodyText) ?: "bad_request")
            // Bank/network failure or scrape timeout on the backend side.
            502, 504 -> ScrapeResult.Failure(FailureReason.NETWORK, errorCode(bodyText) ?: "network")
            // 500 internal_error and anything unexpected.
            else -> ScrapeResult.Failure(
                FailureReason.UNKNOWN,
                errorCode(bodyText) ?: "unexpected_http_${response.code}",
            )
        }
    }

    /** Deserialize a 200 body straight into the raw DTOs; malformed JSON -> PARSE_ERROR. */
    private fun parseSuccess(bodyText: String): ScrapeResult {
        val dto = try {
            JSON.decodeFromString<ScrapeResponseDto>(bodyText)
        } catch (e: SerializationException) {
            return ScrapeResult.Failure(FailureReason.PARSE_ERROR, "Malformed scrape response", e)
        } catch (e: IllegalArgumentException) {
            return ScrapeResult.Failure(FailureReason.PARSE_ERROR, "Malformed scrape response", e)
        }
        return ScrapeResult.Success(
            accounts = dto.accounts.map {
                ScrapedAccount(
                    externalId = it.externalId,
                    label = it.label,
                    institution = it.institution,
                    rawType = it.rawType,
                )
            },
            transactions = dto.transactions.map {
                ScrapedTransaction(
                    accountExternalId = it.accountExternalId,
                    rawDate = it.rawDate,
                    rawMerchant = it.rawMerchant,
                    rawAmount = it.rawAmount,
                    rawDirection = it.rawDirection,
                    rawCategory = it.rawCategory,
                    reference = it.reference,
                )
            },
        )
    }

    /**
     * Extract the backend's coarse `{"error":"<code>"}` code for use as a failure
     * message. The code (e.g. `invalid_credentials`, `network`) is intentionally
     * non-sensitive (SECURITY.md T2/R8); returns null if the body isn't that shape.
     */
    private fun errorCode(bodyText: String): String? = try {
        JSON.decodeFromString<ErrorDto>(bodyText).error
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Institution label emitted for Discount (matches the backend's mapper). */
        const val DISCOUNT_INSTITUTION = "Discount Bank"

        /** Allow-listed backend companyId for Bank Discount. */
        const val DISCOUNT_COMPANY_ID = "discount"

        /** Stable failure message for an OTP-required response (see the 409 branch). */
        const val OTP_REQUIRED_MESSAGE = "otp_required"

        /** Key under [ScraperCredentials.extra] carrying Discount's `num` field. */
        const val NUM_KEY = "num"

        private const val SCRAPE_PATH = "scrape"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Build the production Discount scraper: a pinned, timeout-bounded OkHttp
         * client from [config], with the bearer [tokenProvider] (Keystore-backed).
         */
        fun forDiscount(config: RemoteScraperConfig, tokenProvider: () -> String): RemoteBankScraper {
            val endpoint = config.baseUrl.toHttpUrl().newBuilder().addPathSegment(SCRAPE_PATH).build()
            return RemoteBankScraper(
                institution = DISCOUNT_INSTITUTION,
                endpoint = endpoint,
                client = buildPinnedClient(config),
                tokenProvider = tokenProvider,
                companyId = DISCOUNT_COMPANY_ID,
            )
        }

        /**
         * OkHttp client with SPKI certificate pinning (primary + backup) that fails
         * closed on mismatch (R13), plus connect/read/write timeouts. OkHttp also
         * performs standard hostname verification against the cert SAN.
         */
        fun buildPinnedClient(config: RemoteScraperConfig): OkHttpClient {
            val host = config.baseUrl.toHttpUrl().host
            val pinner = CertificatePinner.Builder()
                .add(host, config.primaryPin, config.backupPin)
                .build()
            return OkHttpClient.Builder()
                .certificatePinner(pinner)
                .connectTimeout(config.connectTimeout)
                .readTimeout(config.readTimeout)
                .writeTimeout(config.connectTimeout)
                .build()
        }
    }
}

// --- Wire DTOs -------------------------------------------------------------
// Kept app-local (not on the domain DTOs) so the domain module stays free of the
// kotlinx-serialization plugin/annotations. The mapping to the domain
// ScrapedAccount/ScrapedTransaction is a trivial 1:1 copy (the backend already
// emits the field names field-for-field). Chosen over org.json because org.json
// is only a stub in Android JVM unit tests ("not mocked"), whereas
// kotlinx-serialization runs headlessly and is type-safe with no reflection.

@Serializable
private data class ScrapeRequestDto(
    val companyId: String,
    val credentials: ScrapeCredentialsDto,
    val startDate: String,
)

@Serializable
private data class ScrapeCredentialsDto(
    val id: String,
    val password: String,
    val num: String,
)

@Serializable
private data class ScrapeResponseDto(
    val accounts: List<AccountDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
)

@Serializable
private data class AccountDto(
    val externalId: String,
    val label: String,
    val institution: String,
    val rawType: String? = null,
)

@Serializable
private data class TransactionDto(
    val accountExternalId: String,
    val rawDate: String,
    val rawMerchant: String,
    val rawAmount: String,
    val rawDirection: String? = null,
    val rawCategory: String? = null,
    val reference: String? = null,
)

@Serializable
private data class ErrorDto(val error: String? = null)
