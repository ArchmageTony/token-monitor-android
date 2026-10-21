package io.github.theminionooo.tokenmonitor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.theminionooo.tokenmonitor.data.protocol.HubProtocolParser
import io.github.theminionooo.tokenmonitor.data.storage.DisplayOptions
import io.github.theminionooo.tokenmonitor.ui.*
import org.junit.Rule
import org.junit.Test

class DesktopV077InteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerSpendUpdatesWithoutShowingTrackedLabelsOrInventingMissingPeriods() {
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("protocol/v0.77.0/billing.json").bufferedReader().use { it.readText() }
        var account by mutableStateOf(HubProtocolParser.decodeStats(source).limits.providers.single { it.provider == "thirdparty" })
        val palette = Palette.from(InterfaceTheme.Default)
        compose.setContent {
            MaterialTheme(colorScheme = tokenMonitorColors(palette), typography = tokenMonitorTypography(1)) {
                CompositionLocalProvider(LocalPalette provides palette, LocalInteractionMotion provides false) {
                    Column(Modifier.padding(16.dp)) { LimitAccountRow(account, DisplayOptions()) }
                }
            }
        }
        fun amount(label: String, value: Double) = "$label ${formatWindowAmount(value, "USD")}"
        compose.onNodeWithText(amount("Today spend", 0.5)).assertIsDisplayed()
        compose.onNodeWithText(amount("Month spend", 2.0)).assertIsDisplayed()
        compose.onNodeWithText(amount("7-day spend", 0.75)).assertIsDisplayed()
        compose.onNodeWithText(amount("All-time spend", 5.0)).assertIsDisplayed()
        compose.onNodeWithText("tracked", substring = true).assertDoesNotExist()
        compose.runOnIdle { account = account.copy(spend = account.spend!!.copy(today = null, week = null, month = null, allTime = 6.0)) }
        compose.onNodeWithText("Today spend", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Month spend", substring = true).assertDoesNotExist()
        compose.onNodeWithText(amount("All-time spend", 6.0)).assertIsDisplayed()
    }
}
