package io.github.theminionooo.tokenmonitor.data.protocol

import io.github.theminionooo.tokenmonitor.ui.limitSpendRows
import org.junit.Assert.*
import org.junit.Test

class DesktopV077Test {
    private fun response(name: String) = javaClass.getResource("/protocol/v0.77.0/$name")!!.readText()
    private fun billing() = HubProtocolParser.decodeStats(response("billing.json"))

    @Test fun `released endpoints and stream preserve usage`() {
        val snapshot = HubProtocolParser.decodeSnapshot(response("health.json"), response("stats.json"), response("devices.json"),
            response("history.json"), response("subscriptions.json"), 0)
        assertEquals(1234L, snapshot.today.totalTokens)
        assertEquals(1234L, snapshot.stats.devices.single().periods.getValue("today").totalTokens)
        assertEquals(1234L, snapshot.history.daily.single().tokens)
        fun frame(name: String) = response(name).lineSequence().first { it.startsWith("data:") }.removePrefix("data:").trim()
        val full = HubStreamProtocol.normalizeComplete(frame("stats-stream.sse"))
        val fresh = HubStreamProtocol.mergeFreshness(full, frame("stats-freshness.sse"))
        assertEquals(snapshot.stats.periods, HubProtocolParser.decodeStats(fresh).periods)
        val limits = HubProtocolParser.decodeStats(fresh).limits
        assertEquals(snapshot.stats.limits.providers, limits.providers)
        assertEquals(HubProtocolParser.decodeStats(frame("stats-freshness.sse")).limits.updatedAt, limits.updatedAt)
    }

    @Test fun `New API spend keeps provider periods and native currency`() {
        val spend = requireNotNull(billing().limits.providers.single { it.provider == "thirdparty" }.spend)
        assertEquals("USD", spend.currency)
        assertFalse(spend.locallyTracked)
        assertEquals(listOf("Today spend" to 0.5, "Month spend" to 2.0, "7-day spend" to 0.75, "All-time spend" to 5.0), limitSpendRows(spend))
    }

    @Test fun `missing and invalid spend do not become zero`() {
        fun spend(balance: String) = HubProtocolParser.decodeStats("""{"limits":{"providers":[{"provider":"thirdparty","adapterId":"newapi-account","balance":$balance}]}}""")
            .limits.providers.single().spend
        val parsed = requireNotNull(spend("""{"currency":"cny","todaySpend":0,"weekSpend":-1,"monthSpend":"invalid","allTimeSpend":5}"""))
        assertEquals("CNY", parsed.currency)
        assertEquals(listOf("Today spend" to 0.0, "All-time spend" to 5.0), limitSpendRows(parsed))
        assertTrue(limitSpendRows(requireNotNull(spend("""{"currency":"USD"}"""))).isEmpty())
        assertNull(spend("""{"currency":"invalid","todaySpend":1}"""))
    }

    @Test fun `MiMo tracking and other adapters retain their behavior`() {
        fun account(provider: String, adapter: String) = HubProtocolParser.decodeStats("""{"limits":{"providers":[{"provider":"$provider","adapterId":"$adapter","balance":{"currency":"USD","todaySpend":1,"weekSpend":2,"trackingSince":"2026-10-01"}}]}}""")
            .limits.providers.single()
        assertNull(account("thirdparty", "newapi-token").spend)
        assertNull(account("other", "newapi-account").spend)
        val mimo = requireNotNull(account("mimo", "").spend)
        assertTrue(mimo.locallyTracked)
        assertEquals(listOf("Today spend (tracked)" to 1.0, "Week spend (tracked)" to 2.0), limitSpendRows(mimo))
        assertEquals("2026-10-01", mimo.trackingSince)
    }

    @Test fun `same-email Cline accounts stay separate and unknown Cursor has no invented quota`() {
        val providers = billing().limits.providers
        val cline = providers.filter { it.provider == "cline" }
        assertEquals(2, cline.size)
        assertEquals(1, cline.map { it.accountEmail }.distinct().size)
        assertEquals(2, cline.map { it.accountKey }.distinct().size)
        assertEquals(listOf(10.0, 20.0), cline.map { it.windows.single().remaining!! }.sorted())
        val cursor = providers.single { it.provider == "cursor" }
        assertEquals("unavailable", cursor.status)
        assertTrue(cursor.windows.isEmpty())
    }
}
