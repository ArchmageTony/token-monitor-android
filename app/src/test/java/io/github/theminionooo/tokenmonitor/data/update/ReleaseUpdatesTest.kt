package io.github.theminionooo.tokenmonitor.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatesTest {
    private val certificate = "c".repeat(64)
    private val identity = requireNotNull(UpdateIdentity.of("example/token-monitor-android", "io.example.tokenmonitor", certificate))

    @Test fun debugBuildsCheckNothing() {
        assertEquals(false, ReleaseUpdates.enabled)
        assertNull(UpdateIdentity.current)
        assertNull(androidReleasesUrl)
        val error = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { ReleaseUpdates.check("v0.0.0", 1) }
        }
        assertEquals(ReleaseUpdates.disabledMessage, error.message)
    }

    @Test fun identityRejectsMissingMalformedAndUpstreamValues() {
        assertNotNull(identity)
        assertNull(UpdateIdentity.of("", "io.example.tokenmonitor", certificate))
        assertNull(UpdateIdentity.of("no-slash", "io.example.tokenmonitor", certificate))
        assertNull(UpdateIdentity.of("a/b/c", "io.example.tokenmonitor", certificate))
        assertNull(UpdateIdentity.of("The-Minion-oOo/token-monitor-android", "io.example.tokenmonitor", certificate))
        assertNull(UpdateIdentity.of("the-minion-ooo/Token-Monitor-Android", "io.example.tokenmonitor", certificate))
        assertNull(UpdateIdentity.of("example/token-monitor-android", "", certificate))
        assertNull(UpdateIdentity.of("example/token-monitor-android", "io.example.tokenmonitor", ""))
        assertNull(UpdateIdentity.of("example/token-monitor-android", "io.example.tokenmonitor", "c".repeat(63)))
        assertNull(UpdateIdentity.of("example/token-monitor-android", "io.example.tokenmonitor", "C".repeat(64)))
        assertNull(UpdateIdentity.of("example/token-monitor-android", "io.example.tokenmonitor", "g".repeat(64)))
    }

    @Test fun comparesDesktopVersionsAndAndroidRevisions() {
        assertEquals(1, ReleaseUpdates.compareTags("android-v0.61.0-r1", "android-v0.60.0-r7"))
        assertEquals(1, ReleaseUpdates.compareTags("android-v0.62.0-r1", "android-v0.61.0-r2"))
        assertEquals(1, ReleaseUpdates.compareTags("android-v0.61.0-r2", "android-v0.61.0-r1"))
        assertEquals(0, ReleaseUpdates.compareTags("android-v0.61.0-r1", "android-v0.61.0-r1"))
        assertEquals(-1, ReleaseUpdates.compareTags("android-v0.60.0-r7", "android-v0.61.0-r1"))
    }

    @Test fun forkRevisionsOrderAboveUpstreamAndBelowTheNextDesktopVersion() {
        assertEquals(1, ReleaseUpdates.compareTags("android-v0.77.0-r501", "android-v0.77.0-r2"))
        assertEquals(1, ReleaseUpdates.compareTags("android-v0.77.0-r502", "android-v0.77.0-r501"))
        assertEquals(-1, ReleaseUpdates.compareTags("android-v0.77.0-r501", "android-v0.78.0-r501"))
    }

    @Test fun acceptsOnlyMatchingPublishedReleaseAssets() {
        val candidate = ReleaseUpdates.parseManifest(identity, release(), manifest(), 770501)
        assertEquals("v0.77.0", candidate.versionName)
        assertEquals(770502, candidate.versionCode)
        assertEquals("token-monitor-android-v0.77.0-r502.apk", candidate.apkName)
        assertEquals("https://github.com/example/token-monitor-android/releases/download/android-v0.77.0-r502/token-monitor-android-v0.77.0-r502.apk", candidate.apkUrl)
    }

    @Test fun rejectsWrongPackageUpstreamPackageAndRollback() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseUpdates.parseManifest(identity, release(), manifest().with("packageName", JsonPrimitive("other.app")), 770501)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseUpdates.parseManifest(identity, release(), manifest().with("packageName", JsonPrimitive("io.github.theminionooo.tokenmonitor")), 770501)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseUpdates.parseManifest(identity, release(), manifest(), 770502)
        }
    }

    @Test fun rejectsAssetsAndPagesFromAnotherRepository() {
        val other = "https://github.com/someone-else/token-monitor-android/releases"
        val foreignAsset = release().with("assets", Json.parseToJsonElement(
            """[{"name":"token-monitor-android-v0.77.0-r502.apk","browser_download_url":"$other/download/android-v0.77.0-r502/token-monitor-android-v0.77.0-r502.apk"}]""",
        ))
        assertThrows(IllegalStateException::class.java) { ReleaseUpdates.parseManifest(identity, foreignAsset, manifest(), 770501) }
        val foreignPage = release().with("html_url", JsonPrimitive("$other/tag/android-v0.77.0-r502"))
        assertThrows(IllegalArgumentException::class.java) { ReleaseUpdates.parseManifest(identity, foreignPage, manifest(), 770501) }
        val otherFork = requireNotNull(UpdateIdentity.of("example/other-fork", "io.example.tokenmonitor", certificate))
        assertThrows(IllegalStateException::class.java) { ReleaseUpdates.parseManifest(otherFork, release(), manifest(), 770501) }
    }

    @Test fun rejectsUnpublishedReleaseAndWrongDownloadLocation() {
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseUpdates.parseManifest(identity, release().with("draft", JsonPrimitive(true)), manifest(), 770501)
        }
        assertThrows(IllegalStateException::class.java) {
            ReleaseUpdates.parseManifest(identity, release().with("assets", JsonArray(emptyList())), manifest(), 770501)
        }
    }

    @Test fun signerMustMatchTheReleaseKeyAndTheInstalledApp() {
        val other = "d".repeat(64)
        assertTrue(ReleaseUpdates.signerAccepted(identity, listOf(certificate), listOf(certificate)))
        // A debug-signed install cannot take the release APK in place.
        assertFalse(ReleaseUpdates.signerAccepted(identity, listOf(certificate), listOf(other)))
        assertFalse(ReleaseUpdates.signerAccepted(identity, listOf(other), listOf(other)))
        assertFalse(ReleaseUpdates.signerAccepted(identity, listOf(certificate, other), listOf(certificate)))
        assertFalse(ReleaseUpdates.signerAccepted(identity, emptyList(), listOf(certificate)))
        assertFalse(ReleaseUpdates.signerAccepted(identity, listOf(certificate), emptyList()))
    }

    private fun release(): JsonObject = Json.parseToJsonElement(
        """{
          "tag_name":"android-v0.77.0-r502",
          "draft":false,
          "prerelease":false,
          "name":"Android v0.77.0 r502 (Remote)",
          "body":"## What changed\\n- Update checking",
          "html_url":"https://github.com/example/token-monitor-android/releases/tag/android-v0.77.0-r502",
          "assets":[{
            "name":"token-monitor-android-v0.77.0-r502.apk",
            "browser_download_url":"https://github.com/example/token-monitor-android/releases/download/android-v0.77.0-r502/token-monitor-android-v0.77.0-r502.apk"
          }]
        }""",
    ).jsonObject

    private fun manifest(): JsonObject = Json.parseToJsonElement(
        """{
          "schemaVersion":1,
          "packageName":"io.example.tokenmonitor",
          "tag":"android-v0.77.0-r502",
          "versionName":"v0.77.0",
          "versionCode":770502,
          "apk":"token-monitor-android-v0.77.0-r502.apk",
          "sizeBytes":10000000,
          "sha256":"${"a".repeat(64)}"
        }""",
    ).jsonObject

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
}
