package io.github.theminionooo.tokenmonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.github.theminionooo.tokenmonitor.domain.HistoryPoint
import io.github.theminionooo.tokenmonitor.domain.HubSnapshot
import io.github.theminionooo.tokenmonitor.domain.usageHistory
import io.github.theminionooo.tokenmonitor.domain.usageHistoryInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun currentPresentationDate(): LocalDate =
    Instant.ofEpochMilli(LocalNow.current).atZone(ZoneId.systemDefault()).toLocalDate()

/** Keep the last prepared history while changed usage is merged off the UI thread. */
@Composable
internal fun rememberUsageHistory(snapshot: HubSnapshot): List<HistoryPoint>? {
    val input = usageHistoryInput(snapshot)
    val history by produceState<List<HistoryPoint>?>(null, input) {
        value = withContext(Dispatchers.Default) { usageHistory(input) }
    }
    return history
}
