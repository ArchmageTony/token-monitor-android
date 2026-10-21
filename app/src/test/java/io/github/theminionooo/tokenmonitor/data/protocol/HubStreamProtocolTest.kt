package io.github.theminionooo.tokenmonitor.data.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import io.github.theminionooo.tokenmonitor.data.network.WireHubSnapshot
import io.github.theminionooo.tokenmonitor.data.decodeStatsUpdate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class HubStreamProtocolTest {
    @Test fun `normalization and freshness preserve escaped and unknown fields`() {
        val stats = buildJsonObject {
            put("updatedAt", "old")
            put("future", buildJsonObject {
                put("text", "Quote \" slash \\ newline\n tab\t unicode \uD83D\uDE80")
                put("null", kotlinx.serialization.json.JsonNull)
                put("number", JsonPrimitive(9_007_199_254_740_991L))
            })
        }
        val envelope = Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("reason", "synthetic"); put("stats", stats) })
        val normalized = HubStreamProtocol.normalizeComplete(envelope)
        assertEquals(stats, Json.parseToJsonElement(normalized))
        assertEquals(stats, Json.parseToJsonElement(HubProtocolParser.normalizeStatsJson(envelope)))
        val patch = HubStreamProtocol.decodeFreshness("""{"updatedAt":"new"}""")
        val expected = JsonObject(stats + ("updatedAt" to JsonPrimitive("new")))
        assertEquals(expected, Json.parseToJsonElement(HubStreamProtocol.mergeFreshness(envelope, patch)))
        assertEquals(expected, Json.parseToJsonElement(HubStreamProtocol.mergeFreshness(envelope, """{"updatedAt":"new"}""")))
    }

    private val complete = """{"updatedAt":"old","staleAfterMs":60000,"periods":{"today":{"totalTokens":42}},"limits":{"updatedAt":"old","providers":[{"provider":"codex"}]},"devices":[{"deviceId":"a","hostname":"Studio","updatedAt":"old","stale":false,"ageMs":10,"periods":{"today":{"totalTokens":42}},"history":{"daily":[{"date":"2026-10-04","tokens":42}]}}]}"""
    private fun wire() = WireHubSnapshot("""{"ok":true,"role":"hub"}""", complete, null,
        """{"daily":[{"date":"2026-10-04","tokens":42}]}""", null, 1)

    @Test fun `small reducer matches full decoder and retains heavy data`() {
        var wire = wire()
        var previous = decodeStatsUpdate(wire, emptyList())
        val original = previous
        val patches = listOf(
            """{"stats":{"updatedAt":"new","staleAfterMs":90000,"limits":{"updatedAt":"new"},"devices":[{"deviceId":"a","ageMs":30,"stale":true}]}}""",
            """{"devices":[{"id":"a","receivedAt":"received"}]}""",
            """{"updatedAt":null,"staleAfterMs":null,"limits":{"updatedAt":null},"devices":[{"deviceId":"a","updatedAt":null,"ageMs":null,"stale":null}]}""",
            """{"devices":[{"deviceId":"a","stale":true},{"deviceId":"a","stale":false}]}""",
            """{"devices":null,"limits":null,"futureField":{"value":1}}""",
            """{}""",
        )
        var expectedWire = wire
        for ((index, raw) in patches.withIndex()) {
            val patch = HubStreamProtocol.decodeFreshness(raw)
            val time = index.toLong() + 2
            val reduced = requireNotNull(HubStreamProtocol.applyFreshness(previous, patch, time))
            expectedWire = expectedWire.copy(stats = HubStreamProtocol.mergeFreshness(expectedWire.stats, raw), capturedAt = time)
            val expected = decodeStatsUpdate(expectedWire, previous.stats.devices)
            assertEquals(expected, reduced)
            assertSame(original.stats.periods, reduced.stats.periods)
            assertSame(original.stats.limits.providers, reduced.stats.limits.providers)
            assertSame(original.stats.devices.single().history, reduced.stats.devices.single().history)
            assertSame(original.history, reduced.history)
            wire = wire.copy(capturedAt = time, freshness = HubStreamProtocol.mergeFreshness(wire.freshness ?: JsonObject(emptyMap()), patch))
            assertEquals(expected.stats, HubProtocolParser.decodeStats(wire.resolvedStats))
            previous = reduced
        }
    }

    @Test fun `new devices and expanded freshness use the compatibility decoder`() {
        val previous = decodeStatsUpdate(wire(), emptyList())
        listOf(
            """{"devices":[{"deviceId":"new","hostname":"New device"}]}""",
            """{"devices":[{"deviceId":"a","periods":{"today":{"totalTokens":99}}}]}""",
            """{"limits":{"providers":[]}}""",
        ).forEach { assertNull(HubStreamProtocol.applyFreshness(previous, HubStreamProtocol.decodeFreshness(it), 2)) }
    }

    @Test fun `freshness updates metadata without erasing complete stats`() {
        val complete = """{"updatedAt":"old","periods":{"today":{"totalTokens":42}},"limits":{"updatedAt":"old","providers":[{"provider":"codex"}]},"devices":[{"deviceId":"a","hostname":"Studio","updatedAt":"old","stale":false,"periods":{"today":{"totalTokens":42}}}]}"""
        val freshness = """{"stats":{"limits":{"updatedAt":"new"},"devices":[{"deviceId":"a","updatedAt":"new","receivedAt":"new","stale":true}]}}"""

        val merged = HubStreamProtocol.mergeFreshness(complete, freshness)
        val stats = HubProtocolParser.decodeStats(merged)

        assertEquals(42, stats.periods.getValue("today").totalTokens)
        assertEquals("new", stats.limits.updatedAt)
        assertEquals("codex", stats.limits.providers.single().provider)
        assertEquals("Studio", stats.devices.single().hostname)
        assertEquals("new", stats.devices.single().updatedAt)
        assertTrue(stats.devices.single().stale)
        assertEquals(42, stats.devices.single().periods.getValue("today").totalTokens)
    }
}
