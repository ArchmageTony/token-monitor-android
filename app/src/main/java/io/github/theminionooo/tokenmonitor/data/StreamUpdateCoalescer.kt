package io.github.theminionooo.tokenmonitor.data

import io.github.theminionooo.tokenmonitor.data.network.HubStreamEvent

/** While a coding session is active the Hub sends a complete stats event every few seconds. */
internal const val COMPLETE_UPDATE_INTERVAL_MS = 5_000L

/**
 * Applies at most one complete stream update per interval. The first update after a quiet period is
 * applied at once; later ones wait, and each newer complete update replaces the one waiting. Freshness
 * frames that arrive while an update waits are kept and applied after it, in arrival order.
 */
internal class StreamUpdateCoalescer(private val intervalMs: Long = COMPLETE_UPDATE_INTERVAL_MS) {
    private var lastCompleteAt: Long? = null
    private var pending: HubStreamEvent? = null
    private val pendingFreshness = mutableListOf<HubStreamEvent>()

    /** Returns the events to apply now, in order. */
    fun offer(event: HubStreamEvent, now: Long): List<HubStreamEvent> = when (event.type) {
        "freshness" -> if (pending == null) listOf(event) else {
            pendingFreshness += event
            emptyList()
        }
        // A snapshot restores the complete state, so it supersedes anything waiting.
        "snapshot" -> {
            clear()
            lastCompleteAt = now
            listOf(event)
        }
        "stats", "message" -> {
            val last = lastCompleteAt
            if (pending == null && (last == null || now - last >= intervalMs)) {
                lastCompleteAt = now
                listOf(event)
            } else {
                pending = event
                pendingFreshness.clear()
                emptyList()
            }
        }
        else -> listOf(event)
    }

    /** Milliseconds until the waiting update may be applied, or null when nothing waits. */
    fun flushDelay(now: Long): Long? = pending?.let { ((lastCompleteAt ?: now) + intervalMs - now).coerceAtLeast(0) }

    fun drain(now: Long): List<HubStreamEvent> {
        val complete = pending ?: return emptyList()
        val events = listOf(complete) + pendingFreshness
        pending = null
        pendingFreshness.clear()
        lastCompleteAt = now
        return events
    }

    fun clear() {
        pending = null
        pendingFreshness.clear()
    }
}
