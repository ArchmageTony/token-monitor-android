package io.github.theminionooo.tokenmonitor.data

import io.github.theminionooo.tokenmonitor.data.network.HubStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUpdateCoalescerTest {
    private fun stats(id: Int) = HubStreamEvent("stats", "stats-$id")
    private fun freshness(id: Int) = HubStreamEvent("freshness", "freshness-$id")
    private fun List<HubStreamEvent>.data() = map { it.data }

    @Test fun `first complete update applies at once and later ones wait for the interval`() {
        val gate = StreamUpdateCoalescer(5_000)
        assertEquals(listOf("stats-1"), gate.offer(stats(1), 0).data())
        assertEquals(emptyList<String>(), gate.offer(stats(2), 2_300).data())
        assertEquals(2_700L, gate.flushDelay(2_300))
        assertEquals(emptyList<String>(), gate.offer(stats(3), 4_600).data())
        assertEquals(listOf("stats-3"), gate.drain(5_000).data())
        assertNull(gate.flushDelay(5_000))
    }

    @Test fun `a steady 2_3 second stream applies about one update every 5 seconds`() {
        val gate = StreamUpdateCoalescer(5_000)
        var applied = 0
        var flushAt: Long? = null
        for (time in (0L..300_000L step 100)) {
            if (flushAt != null && time >= flushAt) { applied += gate.drain(time).size; flushAt = null }
            if (time % 2_300 == 0L) {
                applied += gate.offer(stats(time.toInt()), time).size
                if (flushAt == null) gate.flushDelay(time)?.let { flushAt = time + it }
            }
        }
        // 131 complete events over five minutes; at most one per five seconds is decoded.
        assertTrue("applied $applied", applied in 55..61)
    }

    @Test fun `freshness passes straight through unless a complete update is waiting`() {
        val gate = StreamUpdateCoalescer(5_000)
        gate.offer(stats(1), 0)
        assertEquals(listOf("freshness-1"), gate.offer(freshness(1), 1_000).data())
        gate.offer(stats(2), 2_000)
        assertEquals(emptyList<String>(), gate.offer(freshness(2), 3_000).data())
        assertEquals(emptyList<String>(), gate.offer(freshness(3), 3_500).data())
        assertEquals(listOf("stats-2", "freshness-2", "freshness-3"), gate.drain(5_000).data())
    }

    @Test fun `a newer complete update drops the older one and freshness received before it`() {
        val gate = StreamUpdateCoalescer(5_000)
        gate.offer(stats(1), 0)
        gate.offer(stats(2), 1_000)
        gate.offer(freshness(1), 1_500)
        gate.offer(stats(3), 2_000)
        gate.offer(freshness(2), 2_500)
        assertEquals(listOf("stats-3", "freshness-2"), gate.drain(5_000).data())
    }

    @Test fun `snapshot always applies and supersedes a waiting update`() {
        val gate = StreamUpdateCoalescer(5_000)
        gate.offer(stats(1), 0)
        gate.offer(stats(2), 1_000)
        val snapshot = HubStreamEvent("snapshot", "snapshot")
        assertEquals(listOf("snapshot"), gate.offer(snapshot, 1_500).data())
        assertNull(gate.flushDelay(1_500))
        assertEquals(emptyList<String>(), gate.drain(2_000).data())
        assertEquals(emptyList<String>(), gate.offer(stats(3), 3_000).data())
    }

    @Test fun `clear drops a waiting update and a quiet stream applies the next one at once`() {
        val gate = StreamUpdateCoalescer(5_000)
        gate.offer(stats(1), 0)
        gate.offer(stats(2), 1_000)
        gate.clear()
        assertNull(gate.flushDelay(1_000))
        assertEquals(listOf("stats-3"), gate.offer(stats(3), 6_000).data())
        assertEquals(listOf("message"), gate.offer(HubStreamEvent("message", "message"), 12_000).data())
        assertEquals(listOf("comment"), gate.offer(HubStreamEvent("other", "comment"), 12_100).data())
    }
}
