package io.github.theminionooo.tokenmonitor.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdateCoordinatorTest {
    @Test fun `cache save refreshes only when no live collector owns rendering`() {
        assertTrue(shouldRefreshSavedWidget(WidgetSession()))
        assertTrue(shouldRefreshSavedWidget(WidgetSession(connected = true)))
        assertFalse(shouldRefreshSavedWidget(WidgetSession(enabled = true)))
        assertFalse(shouldRefreshSavedWidget(WidgetSession(refreshing = true)))
        assertFalse(shouldRefreshSavedWidget(WidgetSession(enabled = true, refreshing = true)))
        // Stopping Live restores cache-driven updates even if a note remains visible.
        assertTrue(shouldRefreshSavedWidget(WidgetSession(note = "Live stopped")))
    }
}
