package com.riseup.clone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riseup.clone.data.sync.LedgerSyncWorker
import com.riseup.clone.ui.connect.ConnectBankScreen
import com.riseup.clone.ui.connect.ConnectBankViewModel
import com.riseup.clone.ui.dashboard.DashboardScreen
import com.riseup.clone.ui.dashboard.DashboardViewModel
import com.riseup.clone.ui.theme.RiseUpTheme

/**
 * Single-activity host. Start destination is a minimal state hoist (no nav library):
 * [ConnectBankViewModel.connected] decides between the connect-bank onboarding and
 * the dashboard, and persists across restarts (backed by
 * [com.riseup.clone.data.ConnectionStore]).
 *
 * Demo/seeded mode ([demoMode]) short-circuits onboarding and shows the dashboard
 * off the in-memory seed — kept reachable (debug builds only) so the seeded hero
 * visual can be previewed without connecting a bank.
 */
class MainActivity : ComponentActivity() {

    private val demoMode: Boolean get() = BuildConfig.DEBUG && DEMO_ENABLED

    private val connectViewModel: ConnectBankViewModel by viewModels {
        ConnectBankViewModel.factory(applicationContext)
    }
    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.factory(applicationContext, demo = demoMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Security (M2-8; backend/SECURITY.md §3 refinement 2): background sync is
        // DISABLED. The credential key is bound to a recent device unlock, so a
        // headless worker can no longer decrypt the bank credentials / bearer token —
        // it would only fail or auth-prompt unattended. Sync is foreground-only: it
        // runs on app open (DashboardViewModel triggers a foreground sync in its init,
        // right after the user unlocked to open the app) and via the manual "Sync now"
        // / resync action. Here we only cancel any periodic work an older build left
        // enqueued.
        LedgerSyncWorker.cancelPeriodicSync(applicationContext)

        setContent {
            RiseUpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (demoMode) {
                        DashboardScreen(dashboardViewModel)
                    } else {
                        val connected by connectViewModel.connected.collectAsStateWithLifecycle()
                        when (connected) {
                            null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            true -> DashboardScreen(dashboardViewModel)
                            false -> ConnectBankScreen(connectViewModel)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Flip to true (debug builds only) to preview the seeded dashboard. */
        const val DEMO_ENABLED = false
    }
}
