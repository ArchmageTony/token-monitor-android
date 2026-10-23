package io.github.theminionooo.tokenmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFoundationTest {
    @Test
    fun applicationIdRemainsStable() {
        assertEquals(
            "io.github.at.tokenmonitor",
            BuildConfig.APPLICATION_ID.removeSuffix(".preview"),
        )
    }

    @Test
    fun upstreamUpdaterIsDisabledInThisFork() {
        assertEquals(false, BuildConfig.UPDATES_ENABLED)
    }
}
