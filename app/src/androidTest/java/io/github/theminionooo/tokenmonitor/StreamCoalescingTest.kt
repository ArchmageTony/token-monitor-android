package io.github.theminionooo.tokenmonitor

import androidx.test.platform.app.InstrumentationRegistry
import io.github.theminionooo.tokenmonitor.data.HubRepository
import io.github.theminionooo.tokenmonitor.data.HubRepositoryPool
import io.github.theminionooo.tokenmonitor.data.storage.SecureConnectionStore
import io.github.theminionooo.tokenmonitor.data.storage.SnapshotCache
import io.github.theminionooo.tokenmonitor.domain.HubConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCoalescingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun rapidCompleteUpdatesApplyAtMostOneEveryFiveSecondsAndTheLatestArrives() = withHub { server ->
        val owner = Any()
        lateinit var hub: HubRepository
        instrumentation.runOnMainSync { hub = HubRepositoryPool.acquire(context) }
        try {
            instrumentation.runOnMainSync { hub.setDashboardVisible(owner, true) }
            await(10_000) { hub.state.value.streamActive && hub.state.value.snapshot?.today?.totalTokens == SNAPSHOT_TOKENS }
            val seen = linkedSetOf<Long>()
            val deadline = System.nanoTime() + 16_000_000_000L
            while (System.nanoTime() < deadline) {
                hub.state.value.snapshot?.today?.totalTokens?.let { seen += it }
                if (server.finished.get() == 1 && seen.last() == server.lastSent.get().toLong()) break
                Thread.sleep(10)
            }
            val updates = seen.filter { it > SNAPSHOT_TOKENS }
            assertEquals("The newest complete update must arrive after the burst", server.lastSent.get().toLong(), seen.last())
            // 40 complete events over eight seconds: the first applies at once, then at most one per five seconds.
            assertTrue("Applied ${updates.size} of ${STATS_EVENTS} complete updates: $updates", updates.size in 2..3)
            instrumentation.runOnMainSync { hub.setDashboardVisible(owner, false) }
            await(10_000) { server.streams.get() == 0 }
        } finally {
            instrumentation.runOnMainSync {
                hub.setDashboardVisible(owner, false)
                HubRepositoryPool.release(hub)
            }
        }
    }

    @Test fun pausingDropsAHeldUpdate() = withHub { server ->
        val owner = Any()
        lateinit var hub: HubRepository
        instrumentation.runOnMainSync { hub = HubRepositoryPool.acquire(context) }
        try {
            instrumentation.runOnMainSync { hub.setDashboardVisible(owner, true) }
            await(10_000) { (hub.state.value.snapshot?.today?.totalTokens ?: 0) > SNAPSHOT_TOKENS }
            // The first complete update applied at once; the following ones are held for the interval.
            Thread.sleep(1_000)
            val beforePause = hub.state.value.snapshot!!.today.totalTokens
            instrumentation.runOnMainSync { hub.setDashboardVisible(owner, false) }
            await(10_000) { server.streams.get() == 0 }
            Thread.sleep(6_000)
            assertEquals("A paused dashboard must not apply a held update", beforePause, hub.state.value.snapshot!!.today.totalTokens)
        } finally {
            instrumentation.runOnMainSync {
                hub.setDashboardVisible(owner, false)
                HubRepositoryPool.release(hub)
            }
        }
    }

    private fun withHub(test: (FixtureHub) -> Unit) {
        check(context.packageName.endsWith(".preview"))
        grantFixtureNetworkAccess()
        FixtureHub().use { server ->
            val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>().first { it.isSiteLocalAddress }.hostAddress
            SnapshotCache(context).clear()
            SecureConnectionStore(context).save(HubConnection("http://$address:${server.port}", "synthetic-test", true))
            try { test(server) } finally {
                SecureConnectionStore(context).clear()
                SnapshotCache(context).clear()
            }
        }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("Condition did not become true within $timeoutMs ms", condition())
    }

    /** Sends one snapshot, then a burst of complete stats events 200 ms apart, then keeps the stream open. */
    private class FixtureHub : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val streams = AtomicInteger()
        val lastSent = AtomicInteger(SNAPSHOT_TOKENS.toInt())
        val finished = AtomicInteger()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                thread(isDaemon = true) {
                    try { socket.use { respond(it) } } catch (_: Exception) {
                        // Closing the repository or fixture closes an active stream.
                    } finally { sockets.remove(socket) }
                }
            }
        }

        private fun stats(count: Long) = """{"updatedAt":"$count","periods":{"today":{"totalTokens":$count}}}"""

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader()
            val path = reader.readLine().split(' ')[1]
            while (!reader.readLine().isNullOrEmpty()) { /* Consume headers. */ }
            val output = socket.getOutputStream()
            if (path == "/api/stats/stream") {
                streams.incrementAndGet()
                try {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
                    output.write("event: snapshot\ndata: {\"stats\":${stats(SNAPSHOT_TOKENS)}}\n\n".toByteArray())
                    output.flush()
                    Thread.sleep(500)
                    for (index in 1..STATS_EVENTS) {
                        val tokens = SNAPSHOT_TOKENS + index
                        output.write("event: stats\ndata: {\"stats\":${stats(tokens)}}\n\n".toByteArray())
                        output.flush()
                        lastSent.set(tokens.toInt())
                        Thread.sleep(200)
                    }
                    finished.set(1)
                    while (!server.isClosed) {
                        output.write(": hb\n\n".toByteArray())
                        output.flush()
                        Thread.sleep(500)
                    }
                } finally { streams.decrementAndGet() }
            } else {
                val body = when (path) {
                    "/api/health" -> "{\"ok\":true,\"role\":\"hub\"}"
                    "/api/stats" -> stats(SNAPSHOT_TOKENS)
                    else -> "{}"
                }.toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(body)
                output.flush()
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            acceptor.join(1000)
        }
    }

    private companion object {
        const val SNAPSHOT_TOKENS = 1_000L
        const val STATS_EVENTS = 40
    }
}
