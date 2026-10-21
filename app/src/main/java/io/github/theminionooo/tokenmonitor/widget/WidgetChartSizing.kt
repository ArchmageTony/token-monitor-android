package io.github.theminionooo.tokenmonitor.widget

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import io.github.theminionooo.tokenmonitor.R
import java.util.concurrent.FutureTask

/** One refresh's snapshot and configuration; cache dimensions, never Views or bitmaps. */
internal class WidgetChartMeasurements(private val context: Context) : AutoCloseable {
    private data class Key(val layout: WidgetLayout, val size: SizeF)

    private val sizes = mutableMapOf<Key, Size>()
    private var worker: HandlerThread? = null
    private var closed = false

    fun measure(views: RemoteViews, layout: WidgetLayout, size: SizeF): Size {
        check(!closed)
        return sizes.getOrPut(Key(layout, size)) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                measureLayout(views, size)
            } else {
                // A private Looper supports framework Views on older Android without waiting for
                // Main while the widget coordinator's lock is held. No hierarchy is attached.
                val thread = worker ?: HandlerThread("widget-chart-measure").also { it.start(); worker = it }
                val task = FutureTask { measureLayout(views, size) }
                check(Handler(thread.looper).post(task))
                task.get()
            }
        }
    }

    private fun measureLayout(views: RemoteViews, size: SizeF): Size {
        val density = context.resources.displayMetrics.density
        val width = (size.width * density).toInt().coerceAtLeast(1)
        val height = (size.height * density).toInt().coerceAtLeast(1)
        val view = views.apply(context, FrameLayout(context))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bars = requireNotNull(view.findViewById<View>(R.id.widget_chart_bars))
        return Size(bars.width, bars.height)
    }

    override fun close() {
        closed = true
        sizes.clear()
        worker?.let { it.quitSafely(); it.join() }
        worker = null
    }
}
