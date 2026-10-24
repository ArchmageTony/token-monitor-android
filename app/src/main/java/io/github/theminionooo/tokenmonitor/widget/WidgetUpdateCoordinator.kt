package io.github.theminionooo.tokenmonitor.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

internal object WidgetUpdateCoordinator {
    /** Live/Refresh already renders repository emissions; saving those emissions is not a second update. */
    fun refreshSaved(context: Context) {
        if (shouldRefreshSavedWidget(WidgetRuntime.session)) refresh(context)
    }

    /** Called from the disk queue, Live and plain threads; a launcher that refuses an update must not crash any of them. */
    @Synchronized fun refresh(context: Context) {
        runCatching {
            val manager = AppWidgetManager.getInstance(context)
            val responsive = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
            val pages = manager.getAppWidgetIds(ComponentName(context, SwipeWidgetProvider::class.java))
            if (responsive.isEmpty() && pages.isEmpty()) return
            val source = loadWidgetSnapshot(context)
            runCatching { if (responsive.isNotEmpty()) UsageWidgetProvider.refresh(context, source) }
            runCatching { if (pages.isNotEmpty()) SwipeWidgetProvider.refresh(context, source) }
        }
    }

    fun stopLiveIfUnused(context: Context) {
        if (!hasPlacedWidgets(context)) WidgetLiveService.stop(context)
    }

    /** Whether any Token Monitor widget is on a home screen. A launcher that will not answer counts as none. */
    fun hasPlacedWidgets(context: Context): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(context, SwipeWidgetProvider::class.java)).isNotEmpty()
    }.getOrDefault(false)
}

internal fun shouldRefreshSavedWidget(session: WidgetSession): Boolean = !session.enabled && !session.refreshing
