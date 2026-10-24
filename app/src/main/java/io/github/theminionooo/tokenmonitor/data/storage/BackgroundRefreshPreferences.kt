package io.github.theminionooo.tokenmonitor.data.storage

import android.content.Context
import androidx.core.content.edit

/** How often the home-screen widgets ask the Hub for a fresh snapshot while the app is closed. */
internal enum class BackgroundRefreshInterval(val minutes: Int) {
    Fifteen(15),
    Thirty(30),
    Sixty(60);

    companion object {
        val Default = Thirty

        /** Anything other than a supported choice falls back to the default. */
        fun fromMinutes(minutes: Int): BackgroundRefreshInterval = entries.firstOrNull { it.minutes == minutes } ?: Default
    }
}

internal data class BackgroundRefreshSettings(
    val enabled: Boolean = true,
    val interval: BackgroundRefreshInterval = BackgroundRefreshInterval.Default,
)

/** What the last background runs did. Holds no secret and no Hub response content. */
internal data class BackgroundRefreshRecord(
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastFailureReason: String? = null,
    val consecutiveFailures: Int = 0,
    val secretRejected: Boolean = false,
)

internal class BackgroundRefreshPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    val settings: BackgroundRefreshSettings get() = BackgroundRefreshSettings(
        enabled = preferences.getBoolean(enabledKey, true),
        interval = BackgroundRefreshInterval.fromMinutes(preferences.getInt(intervalKey, BackgroundRefreshInterval.Default.minutes)),
    )

    val record: BackgroundRefreshRecord get() = BackgroundRefreshRecord(
        lastSuccessAt = preferences.getLong(lastSuccessKey, 0L),
        lastFailureAt = preferences.getLong(lastFailureKey, 0L),
        lastFailureReason = preferences.getString(failureReasonKey, null),
        consecutiveFailures = preferences.getInt(failuresKey, 0),
        secretRejected = preferences.getBoolean(rejectedKey, false),
    )

    fun setEnabled(enabled: Boolean) = preferences.edit { putBoolean(enabledKey, enabled) }

    fun setInterval(interval: BackgroundRefreshInterval) = preferences.edit { putInt(intervalKey, interval.minutes) }

    fun recordSuccess(at: Long) = preferences.edit(commit = true) {
        putLong(lastSuccessKey, at)
        putInt(failuresKey, 0)
        putBoolean(rejectedKey, false)
    }

    fun recordFailure(at: Long, reason: String, secretRejected: Boolean) = preferences.edit(commit = true) {
        putLong(lastFailureKey, at)
        putString(failureReasonKey, reason.take(maxReasonLength))
        putInt(failuresKey, preferences.getInt(failuresKey, 0) + 1)
        if (secretRejected) putBoolean(rejectedKey, true)
    }

    /** A new pairing or a successful foreground check means the Hub accepts the secret again. */
    fun clearRejection(): Boolean {
        if (!preferences.getBoolean(rejectedKey, false)) return false
        preferences.edit { putBoolean(rejectedKey, false) }
        return true
    }

    /** Pairing removed: forget the old Hub's history but keep the user's choices. */
    fun clearRecord() = preferences.edit {
        remove(lastSuccessKey)
        remove(lastFailureKey)
        remove(failureReasonKey)
        remove(failuresKey)
        remove(rejectedKey)
    }

    private companion object {
        const val fileName = "background_refresh"
        const val enabledKey = "enabled"
        const val intervalKey = "interval_minutes"
        const val lastSuccessKey = "last_success_at"
        const val lastFailureKey = "last_failure_at"
        const val failureReasonKey = "last_failure_reason"
        const val failuresKey = "consecutive_failures"
        const val rejectedKey = "secret_rejected"
        const val maxReasonLength = 160
    }
}
