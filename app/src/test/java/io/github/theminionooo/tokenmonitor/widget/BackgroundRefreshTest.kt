package io.github.theminionooo.tokenmonitor.widget

import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshInterval
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshRecord
import io.github.theminionooo.tokenmonitor.data.storage.BackgroundRefreshSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class BackgroundRefreshTest {
    @Test fun onByDefaultEveryThirtyMinutes() {
        val settings = BackgroundRefreshSettings()
        assertTrue(settings.enabled)
        assertEquals(30, settings.interval.minutes)
    }

    @Test fun onlySupportedIntervalsAreAccepted() {
        assertEquals(BackgroundRefreshInterval.Fifteen, BackgroundRefreshInterval.fromMinutes(15))
        assertEquals(BackgroundRefreshInterval.Sixty, BackgroundRefreshInterval.fromMinutes(60))
        listOf(0, -1, 5, 14, 45, 61, Int.MAX_VALUE).forEach {
            assertEquals(BackgroundRefreshInterval.Thirty, BackgroundRefreshInterval.fromMinutes(it))
        }
    }

    @Test fun scheduleUsesAThirdOfTheIntervalAsFlex() {
        assertEquals(BackgroundScheduleSpec(15 * 60_000L, 5 * 60_000L), backgroundScheduleSpec(15))
        assertEquals(BackgroundScheduleSpec(30 * 60_000L, 10 * 60_000L), backgroundScheduleSpec(30))
        assertEquals(BackgroundScheduleSpec(60 * 60_000L, 20 * 60_000L), backgroundScheduleSpec(60))
        assertEquals(backgroundScheduleSpec(30), backgroundScheduleSpec(7))
    }

    @Test fun schedulesOnlyWithConsentAWidgetAPairingAndAnAcceptedSecret() {
        assertTrue(shouldScheduleBackgroundRefresh(enabled = true, widgetsPlaced = true, hasConnection = true, secretRejected = false))
        assertFalse(shouldScheduleBackgroundRefresh(enabled = false, widgetsPlaced = true, hasConnection = true, secretRejected = false))
        assertFalse(shouldScheduleBackgroundRefresh(enabled = true, widgetsPlaced = false, hasConnection = true, secretRejected = false))
        assertFalse(shouldScheduleBackgroundRefresh(enabled = true, widgetsPlaced = true, hasConnection = false, secretRejected = false))
        assertFalse(shouldScheduleBackgroundRefresh(enabled = true, widgetsPlaced = true, hasConnection = true, secretRejected = true))
    }

    @Test fun liveOrAManualRefreshSkipsTheRunWithoutCancellingTheSchedule() {
        fun decide(enabled: Boolean = true, placed: Boolean = true, paired: Boolean = true, rejected: Boolean = false, live: Boolean = false) =
            backgroundRefreshDecision(enabled, placed, paired, rejected, live)
        assertEquals(BackgroundRefreshAction.Run, decide())
        assertEquals(BackgroundRefreshAction.Skip, decide(live = true))
        assertEquals(BackgroundRefreshAction.Cancel, decide(enabled = false, live = true))
        assertEquals(BackgroundRefreshAction.Cancel, decide(placed = false))
        assertEquals(BackgroundRefreshAction.Cancel, decide(paired = false))
        assertEquals(BackgroundRefreshAction.Cancel, decide(rejected = true))
    }

    @Test fun widgetNoteReportsOnlyFailuresNewerThanTheSavedSnapshot() {
        val capturedAt = 1_700_000_000_000L
        fun note(record: BackgroundRefreshRecord, enabled: Boolean = true, snapshotAt: Long? = capturedAt) =
            widgetBackgroundNote(enabled, record, snapshotAt, ZoneOffset.UTC, Locale.US)
        assertNull(note(BackgroundRefreshRecord()))
        assertNull(note(BackgroundRefreshRecord(lastFailureAt = capturedAt - 1)))
        assertEquals("Auto refresh failed 22:23", note(BackgroundRefreshRecord(lastFailureAt = capturedAt + 10 * 60_000L)))
        assertEquals("Auto refresh failed 21:53", note(BackgroundRefreshRecord(lastFailureAt = capturedAt - 20 * 60_000L), snapshotAt = null))
        assertNull(note(BackgroundRefreshRecord(lastFailureAt = capturedAt + 1), enabled = false))
        assertEquals("Hub rejected the secret. Open Token Monitor", note(BackgroundRefreshRecord(secretRejected = true)))
    }

    @Test fun deckMarksABackgroundFailureAsStale() {
        val session = WidgetSession(backgroundNote = "Auto refresh failed 10:00")
        val data = prepareWidgetDeck(null, session, 0L, ZoneOffset.UTC, Locale.US)
        assertEquals("NO DATA", data.status)
    }
}
