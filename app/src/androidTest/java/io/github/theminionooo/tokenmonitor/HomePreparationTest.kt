package io.github.theminionooo.tokenmonitor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import io.github.theminionooo.tokenmonitor.domain.HistoryPoint
import io.github.theminionooo.tokenmonitor.domain.HubSnapshot
import io.github.theminionooo.tokenmonitor.domain.HubStats
import io.github.theminionooo.tokenmonitor.domain.SessionUsage
import io.github.theminionooo.tokenmonitor.domain.UsagePeriod
import io.github.theminionooo.tokenmonitor.ui.HomeSessionsModule
import io.github.theminionooo.tokenmonitor.ui.HomeSessionInputs
import io.github.theminionooo.tokenmonitor.ui.LocalNow
import io.github.theminionooo.tokenmonitor.ui.rememberUsageHistory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class HomePreparationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preparationDoesNotCompareSessionListsOnTheUiThread() {
        val now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        fun session(title: String) = SessionUsage(title, title, "codex", "Sample", 10, 0.0, emptyList(), 1,
            Instant.ofEpochMilli(now).toString(), Instant.ofEpochMilli(now).toString(), turnEnded = true)
        class Inputs(private val rows: List<SessionUsage>) : AbstractList<SessionUsage>() {
            override val size get() = rows.size
            override fun get(index: Int) = rows[index]
            override fun equals(other: Any?): Boolean = error("Preparation must key session inputs by identity")
            override fun hashCode(): Int = error("Preparation must not hash session contents")
        }
        var month by mutableStateOf<List<SessionUsage>>(Inputs(listOf(session("First task"))), referentialEqualityPolicy())
        var clock by mutableStateOf(now)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalNow provides clock) {
            Column { HomeSessionsModule(HomeSessionInputs(month, emptyList()), count = 1) {} }
        } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("First task").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { clock += 30_000 }
        compose.onNodeWithText("First task").assertIsDisplayed()
        compose.runOnIdle { month = Inputs(listOf(session("Replacement task"))) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Replacement task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("First task").assertDoesNotExist()
    }

    @Test fun sessionRowsUpdateAndRunningOverflowExpiresWithoutRegrouping() {
        val start = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
        fun session(id: String, title: String, time: Long, ended: Boolean) = SessionUsage(
            id, title, "codex", "Sample", 10, 0.0, emptyList(), 1,
            Instant.ofEpochMilli(time).toString(), Instant.ofEpochMilli(time).toString(), turnEnded = ended,
        )
        var clock by mutableStateOf(start)
        var snapshot by mutableStateOf(HubSnapshot(stats = HubStats(periods = mapOf("month" to UsagePeriod(sessions = listOf(
            session("newest", "Newest task", start, true), session("older", "Older live task", start - 60_000, false),
        ))))))
        compose.setContent {
            MaterialTheme { CompositionLocalProvider(LocalNow provides clock) {
                Column { HomeSessionsModule(HomeSessionInputs(snapshot.month.sessions, snapshot.today.sessions), count = 1) {} }
            } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Older live task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Newest task").assertIsDisplayed()
        compose.runOnIdle { clock += 11 * 60_000 }
        compose.onNodeWithText("Older live task").assertDoesNotExist()
        compose.runOnIdle {
            val updated = snapshot.month.copy(sessions = listOf(session("replacement", "Replacement task", clock, true)))
            snapshot = snapshot.copy(stats = snapshot.stats.copy(periods = mapOf("month" to updated)))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Replacement task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Newest task").assertDoesNotExist()
    }

    @Test fun freshnessRetainsPreparedHistoryButChangedUsageReplacesIt() {
        val date = LocalDate.of(2026, 10, 8).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        var snapshot by mutableStateOf(HubSnapshot(stats = HubStats(updatedAt = date.toString(),
            periods = mapOf("today" to UsagePeriod(totalTokens = 10)))))
        val observed = AtomicReference<List<HistoryPoint>?>()
        compose.setContent {
            val history = rememberUsageHistory(snapshot)
            SideEffect { observed.set(history) }
        }
        compose.waitUntil(5_000) { observed.get()?.singleOrNull()?.tokens == 10L }
        val original = observed.get()
        compose.runOnIdle { snapshot = snapshot.copy(stats = snapshot.stats.copy(updatedAt = date.plusSeconds(30).toString())) }
        compose.waitForIdle()
        assertSame(original, observed.get())
        compose.runOnIdle { snapshot = snapshot.copy(stats = snapshot.stats.copy(periods = mapOf("today" to UsagePeriod(totalTokens = 20)))) }
        compose.waitUntil(5_000) { observed.get()?.singleOrNull()?.tokens == 20L }
    }
}
