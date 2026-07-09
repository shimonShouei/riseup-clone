package com.riseup.clone.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.riseup.clone.domain.scraper.DateRange
import java.time.LocalDate

/**
 * The Android shell around [LedgerSyncer]: a [CoroutineWorker] that builds a
 * syncer, runs one pass, and translates the [SyncOutcome] into a `WorkManager`
 * result. Kept intentionally thin — all the real logic lives in the framework-free
 * orchestrator, which is why almost nothing here needs a device to be trusted (the
 * on-device round-trip test is [Ignore]d on this build machine).
 *
 * ### No background scheduling (M2-8; `backend/SECURITY.md` §3 refinement 2)
 * This worker is **no longer scheduled to run in the background.** The credential
 * key is now bound to a *recent device unlock* ([com.riseup.clone.data.security.KeystoreCredentialStore]),
 * so a headless worker cannot decrypt the stored bank credentials / bearer token —
 * it would only fail (or, worse, prompt) unattended. Sync is therefore
 * **foreground-only**: it runs on app open and via the manual "Sync now" / resync
 * action, both of which call [BankConnector.runSync] in-process (in the foreground,
 * right after the user unlocked the device to open the app) rather than through
 * WorkManager. [cancelPeriodicSync] exists only to tear down any periodic work that
 * an older build of the app may have left enqueued.
 *
 * The worker class itself is retained (the connect flow still assembles a
 * [LedgerSyncer] via [SyncGraph]) and its one-pass logic stays correct, so it can be
 * re-enabled if the auth model ever changes; nothing currently enqueues it.
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
        /** Unique name a previous build used for the periodic work we now cancel. */
        const val UNIQUE_PERIODIC = "ledger-sync-periodic"

        /** How far back one pass fetches. The dedupe makes the overlap harmless. */
        private const val SYNC_WINDOW_DAYS = 90L

        /**
         * Cancel any periodic background sync a prior app version enqueued. Called
         * once at launch (see MainActivity): background sync is disabled for security
         * (M2-8 — the credential key requires a recent device unlock, which a headless
         * worker can't provide), so stale periodic work must be torn down. Safe and
         * idempotent when there is nothing scheduled.
         */
        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}
