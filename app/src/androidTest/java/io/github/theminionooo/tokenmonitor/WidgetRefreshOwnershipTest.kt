package io.github.theminionooo.tokenmonitor

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.theminionooo.tokenmonitor.widget.WidgetRuntime
import io.github.theminionooo.tokenmonitor.widget.WidgetSession
import io.github.theminionooo.tokenmonitor.widget.WidgetUpdateCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRefreshOwnershipTest {
    @Test fun cacheSavesSkipLiveRenderingAndLauncherFailuresAreContained() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName.endsWith(".preview"))
        var lookups = 0
        val failingLauncher = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.APPWIDGET_SERVICE) {
                    lookups++
                    throw IllegalStateException("Synthetic launcher failure")
                }
                return super.getSystemService(name)
            }
        }
        val original = WidgetRuntime.session
        try {
            WidgetRuntime.session = WidgetSession(enabled = true)
            WidgetUpdateCoordinator.refreshSaved(failingLauncher)
            WidgetRuntime.session = WidgetSession(refreshing = true)
            WidgetUpdateCoordinator.refreshSaved(failingLauncher)
            assertEquals(0, lookups)
            WidgetRuntime.session = WidgetSession()
            WidgetUpdateCoordinator.refreshSaved(failingLauncher)
            assertEquals(1, lookups)
            // Explicit redraws, including stop/disconnect, do not use the cache-save suppression.
            WidgetRuntime.session = WidgetSession(enabled = true)
            WidgetUpdateCoordinator.refresh(failingLauncher)
            assertEquals(2, lookups)
        } finally {
            WidgetRuntime.session = original
        }
    }
}
