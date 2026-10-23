package io.github.theminionooo.tokenmonitor.widget

import android.app.job.JobParameters
import android.app.job.JobService
import io.github.theminionooo.tokenmonitor.data.BackgroundRefreshOutcome
import io.github.theminionooo.tokenmonitor.data.HubRepositoryPool
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshPreferences
import io.github.theminionooo.tokenmonitor.data.storage.SecureConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One quiet snapshot for the home-screen widgets while the app is closed. No notification, no wake lock:
 * JobScheduler decides when it runs and holds the device awake only for the job itself.
 */
class BackgroundRefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        running = scope.launch {
            var stopped = false
            try {
                run()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                stopped = true
                throw cancelled
            } finally {
                if (!stopped) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        running = null
        // The job is periodic, so the next window comes anyway; no retry with backoff is needed.
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        val context = applicationContext
        val preferences = BackgroundRefreshPreferences(context)
        val inputs = withContext(Dispatchers.IO) {
            Inputs(preferences.settings.enabled, preferences.record.secretRejected, WidgetUpdateCoordinator.hasPlacedWidgets(context), SecureConnectionStore(context).exists())
        }
        val session = WidgetRuntime.session
        // The open Dashboard is checked by the repository itself, which knows whether it is streaming.
        val action = backgroundRefreshDecision(
            enabled = inputs.enabled,
            widgetsPlaced = inputs.widgetsPlaced,
            hasConnection = inputs.hasConnection,
            secretRejected = inputs.secretRejected,
            liveOrRefreshing = session.enabled || session.refreshing,
        )
        when (action) {
            BackgroundRefreshAction.Cancel -> { withContext(Dispatchers.IO) { BackgroundRefreshScheduler.reconcile(context) }; return }
            BackgroundRefreshAction.Skip -> return
            BackgroundRefreshAction.Run -> Unit
        }
        val repository = HubRepositoryPool.acquire(context)
        val outcome = try {
            repository.refreshOnce()
        } finally {
            HubRepositoryPool.release(repository)
        }
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            when (outcome) {
                BackgroundRefreshOutcome.Refreshed -> preferences.recordSuccess(now)
                BackgroundRefreshOutcome.Skipped -> return@withContext
                is BackgroundRefreshOutcome.Failed -> {
                    preferences.recordFailure(now, outcome.reason, outcome.secretRejected)
                    // Keep the saved numbers on screen, marked as not refreshed.
                    WidgetUpdateCoordinator.refresh(context)
                    if (outcome.secretRejected) BackgroundRefreshScheduler.reconcile(context)
                }
            }
        }
    }

    private data class Inputs(val enabled: Boolean, val secretRejected: Boolean, val widgetsPlaced: Boolean, val hasConnection: Boolean)
}
