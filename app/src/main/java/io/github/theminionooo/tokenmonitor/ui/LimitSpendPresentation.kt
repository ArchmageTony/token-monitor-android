package io.github.theminionooo.tokenmonitor.ui

import io.github.theminionooo.tokenmonitor.domain.LimitSpend

/** New API reports billing periods; MiMo's daily and weekly deltas are locally tracked. */
internal fun limitSpendRows(spend: LimitSpend): List<Pair<String, Double>> {
    val rows = if (spend.locallyTracked) listOf(
        "Month spend" to spend.month, "All-time spend" to spend.allTime,
        "Today spend (tracked)" to spend.today, "Week spend (tracked)" to spend.week,
    ) else listOf(
        "Today spend" to spend.today, "Month spend" to spend.month,
        "7-day spend" to spend.week, "All-time spend" to spend.allTime,
    )
    return rows.mapNotNull { (label, amount) -> amount?.let { label to it } }
}
