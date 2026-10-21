package io.github.theminionooo.tokenmonitor

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Looper
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.platform.app.InstrumentationRegistry
import io.github.theminionooo.tokenmonitor.data.protocol.HubProtocolParser
import io.github.theminionooo.tokenmonitor.ui.InterfaceTheme
import io.github.theminionooo.tokenmonitor.widget.UsageWidgetProvider
import io.github.theminionooo.tokenmonitor.widget.WidgetChartMeasurements
import io.github.theminionooo.tokenmonitor.widget.WidgetLayout
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WidgetChartSizingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun chartBitmapsFitSlotsAcrossDensitiesFontsAndProviderCounts() {
        check(context.packageName.endsWith(".preview"))
        fun asset(name: String) = instrumentation.context.assets.open("showcase/$name.json").bufferedReader().use { it.readText() }
        val source = HubProtocolParser.decodeSnapshot(asset("health"), asset("stats"), asset("devices"), asset("history"), asset("subscriptions"), Instant.parse("2026-09-07T18:00:00Z").toEpochMilli(), false)
        val csv = StringBuilder("api,dpi,fontScale,layout,heightDp,providers,bitmapWidth,bitmapHeight,slotWidth,slotHeight\n")
        for (dpi in listOf(160, 240, 320, 420, 480, 560)) {
            for (fontScale in listOf(1f, 1.3f)) {
                val configuration = Configuration(context.resources.configuration).apply {
                    densityDpi = dpi
                    this.fontScale = fontScale
                }
                val sizedContext = context.createConfigurationContext(configuration)
                val density = sizedContext.resources.displayMetrics.density
                for (providers in 0..2) {
                    val snapshot = source.copy(stats = source.stats.copy(
                        limits = source.stats.limits.copy(providers = source.stats.limits.providers.take(providers)),
                    ))
                    WidgetChartMeasurements(sizedContext).use { measurements ->
                        val shapes = listOf(384, 399, 400, 401, 500).map { WidgetLayout.Large to it } +
                            listOf(301, 360).map { WidgetLayout.Overview to it }
                        for ((layout, heightDp) in shapes) {
                            val size = SizeF(360f, heightDp.toFloat())
                            // Exercise the production background-render path, including worker cleanup.
                            val views = UsageWidgetProvider.render(sizedContext, snapshot, InterfaceTheme.Default, layout,
                                size = size, chartMeasurements = measurements)
                            instrumentation.runOnMainSync {
                                val view = views.apply(sizedContext, FrameLayout(sizedContext))
                                val width = (360 * density).toInt()
                                val height = (heightDp * density).toInt()
                                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                                view.layout(0, 0, width, height)
                                val bars = view.findViewById<ImageView>(R.id.widget_chart_bars)
                                val bitmap = (bars.drawable as BitmapDrawable).bitmap
                                val case = "$layout $dpi dpi font=$fontScale height=$heightDp providers=$providers"
                                assertEquals("$case width", bars.width, bitmap.width)
                                assertEquals("$case height", bars.height, bitmap.height)
                                csv.append("${Build.VERSION.SDK_INT},$dpi,$fontScale,$layout,$heightDp,$providers,${bitmap.width},${bitmap.height},${bars.width},${bars.height}\n")
                                if (dpi in listOf(420, 480) && fontScale == 1f && layout == WidgetLayout.Large && heightDp == 400 && providers == 2) {
                                    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    drawWidget(view, image)
                                    context.openFileOutput("chart-sizing-fixed-$dpi.png", 0).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    image.recycle()
                                }
                            }
                        }
                    }
                }
            }
        }
        context.openFileOutput("chart-sizing-fixed.csv", 0).bufferedWriter().use { it.write(csv.toString()) }
    }

    @Test fun batchMeasurementsReuseSizesAndStopTheirWorkerEvenOnFailure() {
        fun workers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "widget-chart-measure" }.toSet()
        val before = workers()
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val size = SizeF(360f, 400f)
        WidgetChartMeasurements(context).use { measurements ->
            fun views() = RemoteViews(context.packageName, R.layout.usage_widget)
            val first = measurements.measure(views(), WidgetLayout.Large, size)
            assertSame("The same layout size is measured once per batch", first,
                measurements.measure(views(), WidgetLayout.Large, size))
            assertNotSame("A different height needs its own measurement", first,
                measurements.measure(views(), WidgetLayout.Large, SizeF(360f, 401f)))
        }
        assertEquals(before, workers())
        val failure = runCatching {
            WidgetChartMeasurements(context).use { measurements ->
                measurements.measure(RemoteViews(context.packageName, 0), WidgetLayout.Large, size)
            }
        }
        assertTrue("An invalid layout must fail without leaving its worker running", failure.isFailure)
        assertEquals(before, workers())
    }
}
