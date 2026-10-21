package io.github.theminionooo.tokenmonitor

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.theminionooo.tokenmonitor.data.HubRepository
import io.github.theminionooo.tokenmonitor.data.network.WireHubSnapshot
import io.github.theminionooo.tokenmonitor.data.protocol.HubProtocolParser
import io.github.theminionooo.tokenmonitor.data.protocol.HubStreamProtocol
import io.github.theminionooo.tokenmonitor.data.storage.SecureConnectionStore
import io.github.theminionooo.tokenmonitor.data.storage.SnapshotCache
import io.github.theminionooo.tokenmonitor.domain.HubConnection
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CacheHydrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun isolatedContext(): Context {
        val base = instrumentation.targetContext
        check(base.packageName.endsWith(".preview"))
        val prefix = "cache_hydration_${UUID.randomUUID()}"
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("${prefix}_$name", mode)
        }
    }

    private val wire = WireHubSnapshot(
        """{"ok":true,"role":"hub"}""", """{"updatedAt":"old","periods":{"today":{"totalTokens":42}}}""",
        null, null, null, 1234,
    )

    @Test fun completeEnvelopeIsNormalizedOnlyWhenSavedAndStillAcceptsFreshness() {
        val context = isolatedContext()
        val cache = SnapshotCache(context)
        val envelope = wire.copy(stats = """{"reason":"synthetic","stats":${wire.stats}}""")
        try {
            cache.save(envelope)
            val saved = requireNotNull(cache.read())
            assertEquals(HubProtocolParser.decodeStats(wire.stats), HubProtocolParser.decodeStats(saved.stats))
            assertFalse(saved.stats.contains("\"reason\""))
            val patch = HubStreamProtocol.decodeFreshness("""{"updatedAt":"new"}""")
            cache.save(envelope.copy(freshness = patch))
            val refreshed = requireNotNull(cache.read())
            assertEquals("new", HubProtocolParser.decodeStats(refreshed.stats).updatedAt)
            assertEquals(42L, HubProtocolParser.decodeStats(refreshed.stats).periods.getValue("today").totalTokens)
            assertNull(refreshed.freshness)
            cache.save(envelope.copy(stats = "not JSON"))
            assertEquals(refreshed, cache.read())
        } finally {
            cache.clear()
            context.filesDir.delete()
        }
    }

    @Test fun cacheHydratesOffMainAndPersistsAccumulatedFreshness() {
        val context = isolatedContext()
        val cache = SnapshotCache(context)
        val store = SecureConnectionStore(context)
        val patch = HubStreamProtocol.decodeFreshness("""{"updatedAt":"new"}""")
        cache.save(wire.copy(freshness = patch))
        store.save(HubConnection("http://100.64.0.1:17321", "synthetic-test", false))
        lateinit var repository: HubRepository
        try {
            instrumentation.runOnMainSync {
                repository = HubRepository(context) {
                    assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                    it.read()
                }
            }
            runBlocking { withTimeout(10_000) { requireNotNull(repository.cacheLoadJob).join() } }
            val snapshot = requireNotNull(repository.state.value.snapshot)
            assertEquals(42L, snapshot.today.totalTokens)
            assertEquals("new", snapshot.stats.updatedAt)
            assertTrue(snapshot.fromCache)
            assertTrue(snapshot.stale)
            assertEquals(HubProtocolParser.decodeStats(wire.copy(freshness = patch).resolvedStats), snapshot.stats)
        } finally {
            instrumentation.runOnMainSync { repository.close() }
            store.clear()
            cache.clear()
            context.filesDir.delete()
        }
    }

    @Test fun slowCacheDoesNotBlockMainOrRestoreDisconnectedData() {
        val context = isolatedContext()
        val store = SecureConnectionStore(context)
        store.save(HubConnection("http://100.64.0.1:17321", "synthetic-test", false))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        lateinit var repository: HubRepository
        try {
            instrumentation.runOnMainSync {
                repository = HubRepository(context) {
                    entered.countDown()
                    try { check(release.await(10, TimeUnit.SECONDS)); wire } finally { finished.countDown() }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("Constructor must return while disk loading is blocked", 1L, release.count)
            assertEquals(1L, finished.count)
            assertTrue(repository.state.value.hasConnection)
            assertNull(repository.state.value.snapshot)
            instrumentation.runOnMainSync { repository.disconnect() }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(10_000) { requireNotNull(repository.cacheLoadJob).join() } }
            assertFalse(repository.state.value.hasConnection)
            assertNull(repository.state.value.snapshot)
            assertNull(store.read())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { repository.close() }
            store.clear()
            SnapshotCache(context).clear()
            context.filesDir.delete()
        }
    }
}
