import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val upstreamVersionCode = providers.gradleProperty("tokenMonitorVersionCode").get().toInt()
// Fork versions: upstream code rounded down to the thousand + 500 + fork revision (fork.properties),
// so a fork build is always newer than the upstream revision it is based on and never shares a tag with it.
val forkRevision = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("fork.properties")).asText.get().reader())
}.getProperty("tokenMonitorForkRevision").orEmpty().trim().toIntOrNull()
    ?: error("fork.properties must define a numeric tokenMonitorForkRevision")
check(upstreamVersionCode % 1000 < 500) { "Upstream revision ${upstreamVersionCode % 1000} reached the fork range (500-999)" }
check(forkRevision in 1..499) { "tokenMonitorForkRevision must be between 1 and 499" }
val appVersionCode = upstreamVersionCode / 1000 * 1000 + 500 + forkRevision
val appVersionName = providers.gradleProperty("tokenMonitorVersionName").get()
val upstreamVersion = providers.gradleProperty("tokenMonitorUpstreamVersion").get()
val upstreamTag = providers.gradleProperty("tokenMonitorUpstreamTag").get()
val upstreamCommit = providers.gradleProperty("tokenMonitorUpstreamCommit").get()
// In-app updates follow this fork's own GitHub Releases. Both identities are injected at build time:
// the repository from -PtokenMonitorUpdateRepository or GITHUB_REPOSITORY (set by GitHub Actions), and the
// release certificate from -PtokenMonitorReleaseCertSha256 or TOKEN_MONITOR_RELEASE_CERT_SHA256. The
// certificate enables the updater; without it a release build simply has updates off.
val upstreamRepository = "The-Minion-oOo/token-monitor-android"
val explicitUpdateRepository = providers.gradleProperty("tokenMonitorUpdateRepository").orNull?.trim()?.takeIf { it.isNotEmpty() }
val updateCertificate = (providers.gradleProperty("tokenMonitorReleaseCertSha256").orNull
    ?: providers.environmentVariable("TOKEN_MONITOR_RELEASE_CERT_SHA256").orNull)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
val updateRepository = explicitUpdateRepository
    ?: updateCertificate?.let { providers.environmentVariable("GITHUB_REPOSITORY").orNull?.trim()?.takeIf(String::isNotEmpty) }
if (explicitUpdateRepository != null || updateCertificate != null) {
    check(updateCertificate != null && Regex("[a-f0-9]{64}").matches(updateCertificate)) {
        "The release certificate must be a 64-character SHA-256 hex digest"
    }
    check(updateRepository != null && Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(updateRepository)) {
        "The update repository must be owner/name; set -PtokenMonitorUpdateRepository or GITHUB_REPOSITORY"
    }
    check(!updateRepository.equals(upstreamRepository, ignoreCase = true)) { "The fork must not update from the upstream repository" }
}
val releaseUpdatesEnabled = updateRepository != null && updateCertificate != null
val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
// `-PtokenMonitorPreview=true` installs a side-by-side debug copy for fixture screenshots
// without touching the real pairing stored by the normally installed app.
val previewInstall = providers.gradleProperty("tokenMonitorPreview").orNull == "true"

android {
    namespace = "io.github.theminionooo.tokenmonitor"
    compileSdk = 37

    defaultConfig {
        // Fork identity: installs beside the upstream app. The Kotlin namespace above is unchanged to keep upstream merges small.
        applicationId = "io.github.at.tokenmonitor"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "UPSTREAM_VERSION", "\"$upstreamVersion\"")
        buildConfigField("String", "UPSTREAM_TAG", "\"$upstreamTag\"")
        buildConfigField("String", "UPSTREAM_COMMIT", "\"$upstreamCommit\"")
        // Updates are off unless a release build gets a repository and certificate (see above).
        buildConfigField("String", "UPDATE_REPOSITORY", "\"\"")
        buildConfigField("String", "UPDATE_CERT_SHA256", "\"\"")
        buildConfigField("boolean", "UPDATES_ENABLED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (previewInstall) applicationIdSuffix = ".preview"
        }
        release {
            if (releaseUpdatesEnabled) {
                buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
                buildConfigField("String", "UPDATE_CERT_SHA256", "\"$updateCertificate\"")
                buildConfigField("boolean", "UPDATES_ENABLED", "true")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("androidTest").assets.directories.add("src/test/resources")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Android 17 removed InputManager.getInstance; Espresso 3.7 uses getSystemService.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.withType<Test>().configureEach {
    val contractDirectory = providers.environmentVariable("TOKEN_MONITOR_CONTRACT_DIR")
    inputs.property("releasedHubContract", contractDirectory.orElse(""))
    if (contractDirectory.isPresent) {
        environment("TOKEN_MONITOR_CONTRACT_DIR", contractDirectory.get())
        inputs.dir(contractDirectory.get())
    }
}
