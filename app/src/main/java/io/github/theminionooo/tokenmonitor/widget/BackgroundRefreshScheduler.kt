package io.github.theminionooo.tokenmonitor.widget

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshInterval
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshPreferences
import io.github.theminionooo.tokenmonitor.data.storage.SecureConnectionStore
import java.util.concurrent.Executors

internal enum class BackgroundRefreshAction { Cancel, Skip, Run }

/**
 * Whether a periodic widget refresh should exist at all. It needs the user's consent, a placed widget,
 * a saved pairing and a secret the Hub has not rejected.
 */
internal fun shouldScheduleBackgroundRefresh(
    enabled: Boolean,
    widgetsPlaced: Boolean,
    hasConnection: Boolean,
    secretRejected: Boolean,
): Boolean = enabled && widgetsPlaced && hasConnection && !secretRejected

/** What one scheduled run does. Live or a widget Refresh already keeps the widgets current. */
internal fun backgroundRefreshDecision(
    enabled: Boolean,
    widgetsPlaced: Boolean,
    hasConnection: Boolean,
    secretRejected: Boolean,
    liveOrRefreshing: Boolean,
): BackgroundRefreshAction = when {
    !shouldScheduleBackgroundRefresh(enabled, widgetsPlaced, hasConnection, secretRejected) -> BackgroundRefreshAction.Cancel
    liveOrRefreshing -> BackgroundRefreshAction.Skip
    else -> BackgroundRefreshAction.Run
}

internal data class BackgroundScheduleSpec(val intervalMs: Long, val flexMs: Long)

/** A third of the interval as flex lets Android batch the run with other work while the phone sleeps. */
internal fun backgroundScheduleSpec(intervalMinutes: Int): BackgroundScheduleSpec {
    val intervalMs = BackgroundRefreshInterval.fromMinutes(intervalMinutes).minutes * 60_000L
    return BackgroundScheduleSpec(intervalMs = intervalMs, flexMs = intervalMs / 3)
}

/** Keeps the single periodic JobScheduler job in step with the widgets, the pairing and the user's choice. */
internal object BackgroundRefreshScheduler {
    const val JOB_ID = 0x746d01

    /** One Hub snapshot without the optional history is far smaller; this is a generous upper estimate. */
    private const val ESTIMATED_DOWNLOAD_BYTES = 512L * 1024L
    private const val ESTIMATED_UPLOAD_BYTES = 8L * 1024L

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "token-monitor-background-schedule").apply { isDaemon = true }
    }

    /** Safe from any thread, including a broadcast receiver; the work happens off the main thread. */
    fun reconcileAsync(context: Context) {
        val appContext = context.applicationContext
        worker.execute { runCatching { reconcile(appContext) } }
    }

    @Synchronized fun reconcile(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val preferences = BackgroundRefreshPreferences(context)
        val settings = preferences.settings
        val wanted = shouldScheduleBackgroundRefresh(
            enabled = settings.enabled,
            widgetsPlaced = WidgetUpdateCoordinator.hasPlacedWidgets(context),
            hasConnection = SecureConnectionStore(context).exists(),
            secretRejected = preferences.record.secretRejected,
        )
        if (!wanted) {
            scheduler.cancel(JOB_ID)
            return
        }
        val spec = backgroundScheduleSpec(settings.interval.minutes)
        val pending = scheduler.getPendingJob(JOB_ID)
        // Rescheduling an identical job would push its next run back, so leave a matching one alone.
        if (pending != null && pending.isPeriodic && pending.intervalMillis == spec.intervalMs && pending.isPersisted) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackgroundRefreshJobService::class.java))
            .setPeriodic(spec.intervalMs, spec.flexMs)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setEstimatedNetworkBytes(ESTIMATED_DOWNLOAD_BYTES, ESTIMATED_UPLOAD_BYTES) }
            .build()
        scheduler.schedule(job)
    }
}
