package com.riseup.clone.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.riseup.clone.domain.scraper.DateRange
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The Android shell around [LedgerSyncer]: a [CoroutineWorker] that builds a
 * syncer, runs one pass, and translates the [SyncOutcome] into a `WorkManager`
 * result. Kept intentionally thin — all the real logic lives in the framework-free
 * orchestrator, which is why almost nothing here needs a device to be trusted (the
 * on-device round-trip test is [Ignore]d on this build machine).
 *
 * The provider wiring (which [com.riseup.clone.domain.scraper.BankScraper] to run)
 * is filled in when the connect-bank flow lands (M1-6); until a bank is connected
 * [SyncGraph.buildSyncer] returns `null` and a run is a successful no-op.
 */
class LedgerSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // No bank connected yet → nothing to sync; a clean success (not a retry).
        val syncer = SyncGraph.buildSyncer(applicationContext) ?: return Result.success()

        val today = LocalDate.now()
        val range = DateRange(from = today.minusDays(SYNC_WINDOW_DAYS), to = today)

        return when (syncer.sync(range)) {
            is SyncOutcome.Synced -> Result.success()
            is SyncOutcome.Retry -> Result.retry()
            is SyncOutcome.Failed -> Result.failure()
        }
    }

    companion object {
        /** Unique names keep repeated schedules from stacking duplicate work. */
        const val UNIQUE_PERIODIC = "ledger-sync-periodic"
        const val UNIQUE_ONE_SHOT = "ledger-sync-now"

        /** How far back each pass fetches. The dedupe makes the overlap harmless. */
        private const val SYNC_WINDOW_DAYS = 90L

        /** Backoff floor for transient (`Result.retry()`) failures. */
        private const val BACKOFF_SECONDS = 30L

        /** Only run once there's connectivity — a scrape is useless offline. */
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Schedule the recurring background sync. Uses [ExistingPeriodicWorkPolicy.KEEP]
         * so calling this every launch doesn't reset the cadence or pile up requests.
         */
        fun schedulePeriodicSync(context: Context, interval: Duration = Duration.ofHours(6)) {
            val request = PeriodicWorkRequestBuilder<LedgerSyncWorker>(interval)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Enqueue an immediate one-shot sync (e.g. a pull-to-refresh). Unique so a
         * burst of taps coalesces into a single run rather than N parallel scrapes.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<LedgerSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_SHOT,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
