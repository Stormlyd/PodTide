package eu.darken.capod.main.ui.overview

import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.monitor.core.visibleAncModes
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting
import eu.darken.capod.pods.core.apple.ble.BlePodSnapshot
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select

/**
 * Keep passive readings at the overview's one-second cadence, but deliver actual ANC changes
 * and device removal immediately. One owner serializes upstream snapshots and the pending timer;
 * a control update replaces any older passive snapshot, never synthesizing a confirmed mode.
 */
internal fun Flow<List<PodDevice>>.overviewDeviceUpdates(): Flow<List<PodDevice>> = flow {
    coroutineScope {
        val updates = produce { this@overviewDeviceUpdates.collect { send(it) } }
        var timer: Deferred<Unit>? = null
        var pending: List<PodDevice>? = null
        var lastControls: List<OverviewControlState>? = null
        var upstreamDone = false
        try {
            while (!upstreamDone || pending != null) {
                select<Unit> {
                    if (!upstreamDone) updates.onReceiveCatching { result ->
                        result.exceptionOrNull()?.let { throw it }
                        val devices = result.getOrNull()
                        if (devices == null) {
                            upstreamDone = true
                        } else {
                            val controls = devices.map { it.overviewControlState() }
                            if (timer == null || controls != lastControls) {
                                emit(devices)
                                lastControls = controls
                                pending = null
                                if (timer == null) timer = async { delay(1000) }
                            } else {
                                pending = devices
                            }
                        }
                    }
                    timer?.onAwait {
                        timer = null
                        pending?.let { devices ->
                            pending = null
                            emit(devices)
                            if (!upstreamDone) timer = async { delay(1000) }
                        }
                    }
                }
            }
        } finally {
            timer?.cancel()
            updates.cancel()
        }
    }
}

/** Detached UI fields: telemetry timestamps, batteries and RSSI must not bypass coalescing. */
private data class OverviewControlState(
    val profileId: String?,
    val address: String?,
    val identifier: BlePodSnapshot.Id?,
    val connected: Boolean,
    val ready: Boolean,
    val hasAncControl: Boolean,
    val current: AapSetting.AncMode.Value?,
    val pending: AapSetting.AncMode.Value?,
    val supported: List<AapSetting.AncMode.Value>?,
    val visible: List<AapSetting.AncMode.Value>,
)

private fun PodDevice.overviewControlState() = OverviewControlState(
    profileId = profileId,
    address = address,
    identifier = identifier,
    connected = isAapConnected,
    ready = isAapReady,
    hasAncControl = hasAncControl,
    current = ancMode?.current,
    pending = pendingAncMode,
    supported = ancMode?.supported?.toList(),
    visible = visibleAncModes.toList(),
)
