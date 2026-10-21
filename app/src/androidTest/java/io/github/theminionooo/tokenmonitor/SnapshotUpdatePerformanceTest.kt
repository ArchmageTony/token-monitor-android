package io.github.theminionooo.tokenmonitor

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.theminionooo.tokenmonitor.data.DecodedBodies
import io.github.theminionooo.tokenmonitor.data.decodeStatsUpdate
import io.github.theminionooo.tokenmonitor.domain.HubSnapshot
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Generated payloads only; allocation and timing observations have no device-dependent thresholds. */
class SnapshotUpdatePerformanceTest {
    @Test fun completeUpdatesRetainDataWithoutTheJsonRoundTrip() {
        val results = mutableListOf<JsonObject>()
        for (size in listOf(512 * 1024, 1024 * 1024, 2 * 1024 * 1024 - 64 * 1024)) {
            val wire = SyntheticPerformanceSnapshot.create(size).wire
            val bodies = DecodedBodies()
            val previous = decodeStatsUpdate(wire, emptyList(), bodies)
            val input = wire.copy(capturedAt = wire.capturedAt + 1000)
            val legacy = {
                // Before the fix, complete events were parsed and rebuilt as text before decoding.
                val normalized = Json.parseToJsonElement(input.stats).toString()
                decodeStatsUpdate(input.copy(stats = normalized), previous.stats.devices, bodies)
            }
            val current = { decodeStatsUpdate(input, previous.stats.devices, bodies, previous) }
            repeat(2) { legacy(); current() }
            fun measure(name: String, operation: () -> HubSnapshot) {
                System.gc()
                SystemClock.sleep(100)
                val before = Debug.getRuntimeStat("art.gc.bytes-allocated")!!.toLong()
                val start = SystemClock.elapsedRealtimeNanos()
                var updated = previous
                repeat(3) { updated = operation() }
                val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / 3
                val allocated = (Debug.getRuntimeStat("art.gc.bytes-allocated")!!.toLong() - before) / 3
                assertEquals(previous.copy(capturedAt = input.capturedAt), updated)
                if (name == "current") {
                    assertSame(previous.stats.periods, updated.stats.periods)
                    assertSame(previous.today.sessions, updated.today.sessions)
                }
                results += buildJsonObject {
                    put("statsBytes", wire.stats.toByteArray().size)
                    put("path", name)
                    put("allocatedBytesPerUpdate", allocated)
                    put("meanMs", elapsed)
                }
            }
            measure("legacy", legacy)
            measure("current", current)
            measure("current", current)
            measure("legacy", legacy)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "performance").apply { mkdirs() }
        File(directory, "snapshot-updates.json").writeText(Json.encodeToString(JsonArray.serializer(), JsonArray(results)))
    }
}
