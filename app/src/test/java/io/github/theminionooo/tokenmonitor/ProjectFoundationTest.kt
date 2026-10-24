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
    fun debugBuildsNeverEnableTheUpdater() {
        assertEquals(false, BuildConfig.UPDATES_ENABLED)
        assertEquals("", BuildConfig.UPDATE_REPOSITORY)
        assertEquals("", BuildConfig.UPDATE_CERT_SHA256)
    }

    @Test
    fun forkVersionCodeSitsInTheForkRevisionRange() {
        val revision = BuildConfig.VERSION_CODE % 1000
        assertEquals(true, revision in 501..999)
    }
}
