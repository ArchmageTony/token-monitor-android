package io.github.theminionooo.tokenmonitor.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.util.SizeF
import io.github.theminionooo.tokenmonitor.data.protocol.HubProtocolParser
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshPreferences
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshRecord
import io.github.theminionooo.tokenmonitor.data.storage.DisplayPreferences
import io.github.theminionooo.tokenmonitor.data.storage.SnapshotCache
import io.github.theminionooo.tokenmonitor.domain.HubSnapshot
import io.github.theminionooo.tokenmonitor.ui.InterfaceTheme
import io.github.theminionooo.tokenmonitor.ui.resolveInterfaceTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class WidgetDeckRenderState(
    val data: WidgetDeckData,
    val theme: InterfaceTheme,
    val size: SizeF,
)

internal data class WidgetSnapshot(val session: WidgetSession, val snapshot: HubSnapshot?)

/** Read and decode at most once for a batch of launcher updates. */
internal fun loadWidgetSnapshot(context: Context): WidgetSnapshot {
    val session = WidgetRuntime.session
    // During a Live or refresh session the in-memory snapshot is the newest one; skip decoding the disk cache on every frame.
    val cached = if ((session.enabled || session.refreshing) && session.snapshot != null) null else SnapshotCache(context).read()?.let { wire ->
        runCatching {
            HubProtocolParser.decodeSnapshot(
                wire.health,
                wire.stats,
                wire.devices,
                wire.history,
                wire.subscriptions,
                wire.capturedAt,
                fromCache = true,
            )
        }.getOrNull()
    }
    val snapshot: HubSnapshot? = listOfNotNull(session.snapshot, cached).maxByOrNull { it.capturedAt }
    val note = if (session.enabled || session.refreshing || session.note != null) null else runCatching {
        val preferences = BackgroundRefreshPreferences(context)
        widgetBackgroundNote(preferences.settings.enabled, preferences.record, snapshot?.capturedAt)
    }.getOrNull()
    return WidgetSnapshot(if (note == null) session else session.copy(backgroundNote = note), snapshot)
}

/**
 * A short line for a widget whose saved numbers the background refresh could not replace. A failure older
 * than the saved snapshot is history, not news, and a switched-off refresh never reports anything.
 */
internal fun widgetBackgroundNote(
    enabled: Boolean,
    record: BackgroundRefreshRecord,
    snapshotCapturedAt: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String? = when {
    !enabled -> null
    record.secretRejected -> "Hub rejected the secret. Open Token Monitor"
    record.lastFailureAt > 0 && record.lastFailureAt > (snapshotCapturedAt ?: 0L) ->
        "Auto refresh failed ${DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zoneId).format(Instant.ofEpochMilli(record.lastFailureAt))}"
    else -> null
}

internal fun loadWidgetDeck(context: Context, widgetId: Int): WidgetDeckRenderState =
    loadWidgetDeck(context, widgetId, loadWidgetSnapshot(context))

internal fun loadWidgetDeck(context: Context, widgetId: Int, source: WidgetSnapshot): WidgetDeckRenderState {
    val display = DisplayPreferences(context).options.value
    val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val theme = resolveInterfaceTheme(display.themeCode, display.followSystemTheme, systemDark)
    return WidgetDeckRenderState(
        data = prepareWidgetDeck(source.snapshot, source.session),
        theme = theme,
        size = widgetDeckSize(context, widgetId),
    )
}

/** A launcher allocation, never smaller than the widget's declared minimum. */
internal fun deckAllocation(size: SizeF): SizeF = SizeF(size.width.coerceAtLeast(250f), size.height.coerceAtLeast(110f))

internal fun widgetDeckSize(context: Context, widgetId: Int): SizeF {
    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return SizeF(320f, 180f)
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
    val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    val width = options.getInt(
        if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
        320,
    ).coerceAtLeast(250)
    val height = options.getInt(
        if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
        180,
    ).coerceAtLeast(110)
    return SizeF(width.toFloat(), height.toFloat())
}

internal object WidgetDeckPageState {
    private const val STORE = "widget_deck_pages"

    fun read(context: Context, widgetId: Int): WidgetDeckPage {
        val index = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getInt(widgetId.toString(), 0)
        return WidgetDeckPage.entries.getOrElse(index) { WidgetDeckPage.Overview }
    }

    fun move(context: Context, widgetId: Int, delta: Int): WidgetDeckPage {
        val current = read(context, widgetId).ordinal
        val next = Math.floorMod(current + delta, WidgetDeckPage.entries.size)
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putInt(widgetId.toString(), next).apply()
        return WidgetDeckPage.entries[next]
    }

    fun remove(context: Context, widgetIds: IntArray) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().apply {
            widgetIds.forEach { remove(it.toString()) }
        }.apply()
    }
}
