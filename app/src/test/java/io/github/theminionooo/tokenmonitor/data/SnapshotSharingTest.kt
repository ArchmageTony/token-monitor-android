package io.github.theminionooo.tokenmonitor.data

import io.github.theminionooo.tokenmonitor.data.network.WireHubSnapshot
import io.github.theminionooo.tokenmonitor.data.protocol.HubProtocolParser
import org.junit.Assert.*
import org.junit.Test

class SnapshotSharingTest {
    private fun wire(tokens: Long = 42, sessionTokens: Long = 10, devices: String = """[{"deviceId":"a","periods":{"today":{"totalTokens":42}}}]""") = WireHubSnapshot(
        """{"ok":true,"role":"hub"}""",
        """{"stats":{"periods":{"today":{"totalTokens":$tokens,"sessions":{"s":{"client":"codex","title":"Task","totalTokens":$sessionTokens}}},"month":{"totalTokens":100}},"devices":$devices,"limits":{"providers":[{"provider":"codex","windows":[{"remainingPercent":50}]}]},"historyPreview":{"daily":[{"date":"2026-10-21","tokens":42}]}}}""",
        null, """{"daily":[{"date":"2026-10-20","tokens":20}]}""", null, 1,
    )

    @Test fun `equal complete decodes reuse heavy data and retain new capture metadata`() {
        val bodies = DecodedBodies()
        val first = decodeStatsUpdate(wire(), emptyList(), bodies)
        val input = wire().copy(capturedAt = 2)
        val expected = decodeStatsUpdate(input, first.stats.devices, bodies)
        val shared = decodeStatsUpdate(input, first.stats.devices, bodies, first)
        assertEquals(expected, shared)
        assertEquals(2L, shared.capturedAt)
        assertSame(first.stats.periods, shared.stats.periods)
        assertSame(first.today.sessions, shared.today.sessions)
        assertSame(first.stats.devices, shared.stats.devices)
        assertSame(first.stats.limits.providers, shared.stats.limits.providers)
        assertSame(first.history, shared.history)
    }

    @Test fun `counter changes keep unchanged sessions and unrelated periods shared`() {
        val first = decodeStatsUpdate(wire(), emptyList())
        val shared = decodeStatsUpdate(wire(tokens = 99), first.stats.devices, previousSnapshot = first)
        assertEquals(99L, shared.today.totalTokens)
        assertSame(first.today.sessions, shared.today.sessions)
        assertSame(first.month, shared.month)
        assertEquals(42L, first.today.totalTokens)
        val changedSession = decodeStatsUpdate(wire(tokens = 99, sessionTokens = 88), shared.stats.devices, previousSnapshot = shared)
        assertEquals(88L, changedSession.today.sessions.single().totalTokens)
        assertNotSame(shared.today.sessions, changedSession.today.sessions)
        assertEquals(10L, shared.today.sessions.single().totalTokens)
    }

    @Test fun `sharing preserves device metadata and history corrections and removals`() {
        val first = decodeStatsUpdate(wire(), emptyList())
        val devices = """[{"deviceId":"a","stale":true,"ageMs":90000,"periods":{"today":{"totalTokens":99}},"history":{"daily":[{"date":"2026-10-21","tokens":70}]}},{"deviceId":"b"}]"""
        val input = wire(devices = devices)
        val expected = decodeStatsUpdate(input, first.stats.devices)
        val shared = decodeStatsUpdate(input, first.stats.devices, previousSnapshot = first)
        assertEquals(expected, shared)
        assertTrue(shared.stats.devices.first().stale)
        assertEquals(70L, shared.stats.devices.first().history.daily.single().tokens)
        val removed = decodeStatsUpdate(wire(devices = "[]"), shared.stats.devices, previousSnapshot = shared)
        assertTrue(removed.stats.devices.isEmpty())
        val cleared = decodeStatsUpdate(wire().copy(stats = """{"periods":{},"limits":{"providers":[]},"devices":[]}"""), shared.stats.devices, previousSnapshot = shared)
        assertTrue(cleared.stats.periods.isEmpty())
        assertTrue(cleared.stats.limits.providers.isEmpty())
    }

    @Test fun `equal sharing never drops changed attribution quotas or transport state`() {
        val first = decodeStatsUpdate(wire(), emptyList())
        val updated = first.copy(
            health = first.health.copy(hubBuild = "next"),
            stats = first.stats.copy(
                updatedAt = "new", staleAfterMs = 90000,
                periods = first.stats.periods + ("today" to first.today.copy(modelCosts = mapOf("new" to 3.0), unpricedTokens = 7)),
                limits = first.stats.limits.copy(providers = emptyList()),
            ),
            history = first.history.copy(daily = emptyList()),
            fromCache = true, stale = true,
        )
        assertEquals(updated, reuseSnapshotData(first, updated))
    }
}
