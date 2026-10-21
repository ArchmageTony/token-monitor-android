package io.github.theminionooo.tokenmonitor.data

import io.github.theminionooo.tokenmonitor.domain.DeviceUsage
import io.github.theminionooo.tokenmonitor.domain.HubSnapshot
import io.github.theminionooo.tokenmonitor.domain.UsagePeriod

/** Compare decoded data on IO before publishing it; Compose can then reuse unchanged inputs. */
internal fun reuseSnapshotData(previous: HubSnapshot, updated: HubSnapshot): HubSnapshot {
    val devicesById = previous.stats.devices.associateBy { it.id }
    val stats = updated.stats.copy(
        periods = updated.stats.periods.reusePeriods(previous.stats.periods),
        devices = updated.stats.devices.map { device ->
            devicesById[device.id]?.let { device.reuseData(it) } ?: device
        }.reuseEqual(previous.stats.devices),
        limits = updated.stats.limits.copy(
            providers = updated.stats.limits.providers.reuseEqual(previous.stats.limits.providers),
        ).reuseEqual(previous.stats.limits),
        historyPreview = updated.stats.historyPreview.reuseEqual(previous.stats.historyPreview),
    ).reuseEqual(previous.stats)
    return updated.copy(
        health = updated.health.reuseEqual(previous.health),
        stats = stats,
        history = updated.history.reuseEqual(previous.history),
        subscriptions = updated.subscriptions.reuseEqual(previous.subscriptions),
    )
}

private fun Map<String, UsagePeriod>.reusePeriods(previous: Map<String, UsagePeriod>): Map<String, UsagePeriod> =
    mapValues { (key, period) ->
        previous[key]?.let { old ->
            period.copy(
                sessions = period.sessions.reuseEqual(old.sessions),
                projects = period.projects.reuseEqual(old.projects),
            ).reuseEqual(old)
        } ?: period
    }.reuseEqual(previous)

private fun DeviceUsage.reuseData(previous: DeviceUsage): DeviceUsage = copy(
    periods = periods.reusePeriods(previous.periods),
    history = history.reuseEqual(previous.history),
    trackedClients = trackedClients.reuseEqual(previous.trackedClients),
).reuseEqual(previous)

private fun <T> T.reuseEqual(previous: T): T = if (this == previous) previous else this
