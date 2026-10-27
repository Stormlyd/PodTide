package eu.darken.capod.reaction.core.autoconnect

import eu.darken.capod.common.SystemTimeSource
import eu.darken.capod.common.TimeSource
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.bluetooth.NudgeAvailability
import eu.darken.capod.common.bluetooth.NudgeCapabilityStore
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.flow.setupCommonEventHandlers
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.monitor.core.primaryDevice
import eu.darken.capod.pods.core.apple.ble.devices.ApplePods
import eu.darken.capod.pods.core.apple.ble.devices.DualApplePods
import eu.darken.capod.pods.core.apple.ble.devices.airpods.HasStateDetectionAirPods
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoConnect @Inject constructor(
    private val bluetoothManager: BluetoothManager2,
    private val deviceMonitor: DeviceMonitor,
    private val nudgeCapabilityStore: NudgeCapabilityStore,
    private val timeSource: TimeSource = SystemTimeSource,
) {
    private val attempts = ReconnectAttemptLimiter()
    private val headsetAttempts = ReconnectAttemptLimiter()
    private fun reconnectTicker() = flow { while (true) { emit(Unit); delay(1_000) } }

    fun monitor(): Flow<Unit> = flow {
        coroutineScope {
            // Keep collecting while profile acquisition suspends. Guards read current
            // settings, BLE identity/state and selected target, rather than queued frames.
            val latestDevice = deviceMonitor.primaryDevice().stateIn(this, SharingStarted.Eagerly, null)
            val latestAudio = bluetoothManager.audioConnectedAddresses.stateIn(this, SharingStarted.Eagerly, null)
            val latestHeadset = bluetoothManager.headsetConnectedAddresses.stateIn(this, SharingStarted.Eagerly, null)
            combine(latestDevice, latestAudio, latestHeadset, reconnectTicker()) { _, _, _, _ -> Unit }
                .conflate()
                .collect {
                    val device = latestDevice.value ?: return@collect
                    // address prefers BLE metadata; profileAddress reflects the latest selected
                    // paired device, including changes to a stale BLE profile's address.
                    val address = device.profileAddress ?: return@collect
                    val profileId = device.profileId ?: return@collect
                    if (latestAudio.value?.contains(address) == true) {
                        attempts.reset(address)
                    }
                    if (latestHeadset.value?.contains(address) == true) {
                        headsetAttempts.reset(address)
                    }
                    val canConnect = {
                        val current = latestDevice.value
                        current != null && current.profileId == profileId &&
                            current.profileAddress == address &&
                            isEligible(current, address)
                    }
                    val canRequestAudio = { canConnect() && latestAudio.value?.contains(address) == false }
                    val canRequestHeadset = {
                        canConnect() && latestHeadset.value?.contains(address) == false &&
                            nudgeCapabilityStore.availability.value != NudgeAvailability.BROKEN
                    }
                    if (!canRequestAudio() && !canRequestHeadset()) return@collect
                    val paired = try {
                        bluetoothManager.bondedDevices().first().firstOrNull { it.address == address }
                    } catch (e: SecurityException) {
                        log(TAG) { "Bonded devices unavailable while Bluetooth permission is pending: $e" }
                        null
                    } ?: return@collect
                    val accepted = if (canRequestAudio() && attempts.tryAcquire(address, timeSource.elapsedRealtime())) {
                        bluetoothManager.requestAudioConnection(paired, canRequestAudio)
                    } else null
                    val headset = if (canRequestHeadset() && headsetAttempts.tryAcquire(address, timeSource.elapsedRealtime())) {
                        bluetoothManager.nudgeConnection(paired, canRequestHeadset).also { nudgeCapabilityStore.record(it) }
                    } else null
                    // Request acceptance does not establish music-profile success.
                    log(TAG) { "Audio request accepted=$accepted, headset=$headset; awaiting observed A2DP state" }
                    emit(Unit)
                }
        }
    }.setupCommonEventHandlers(TAG) { "monitor" }

    private fun isEligible(device: PodDevice, address: String): Boolean {
        if (!device.reactions.autoConnect) return false
        val broadcast = device.ble as? ApplePods ?: return false
        if (!broadcast.meta.isIRKMatch || broadcast.meta.profile?.id != device.profileId ||
            broadcast.meta.profile?.address != address) return false
        val remoteUse = when ((broadcast as? HasStateDetectionAirPods)?.state) {
            HasStateDetectionAirPods.ConnectionState.DISCONNECTED -> RemoteUse.DISCONNECTED
            HasStateDetectionAirPods.ConnectionState.IDLE -> RemoteUse.IDLE
            HasStateDetectionAirPods.ConnectionState.MUSIC -> RemoteUse.MUSIC
            HasStateDetectionAirPods.ConnectionState.CALL -> RemoteUse.CALL
            HasStateDetectionAirPods.ConnectionState.RINGING -> RemoteUse.RINGING
            HasStateDetectionAirPods.ConnectionState.HANGING_UP -> RemoteUse.HANGING_UP
            else -> RemoteUse.UNKNOWN
        }
        val age = broadcast.scanResult.observationAgeMillis(timeSource) ?: return false
        if (!IdleReconnectPolicy.allows(remoteUse, age, connected = false)) return false
        val reactions = device.reactions
        return evaluateAutoConnect(
            mainDeviceAddr = address,
            hasBondedDevice = true,
            isAlreadyConnected = false,
            condition = reactions.autoConnectCondition,
            lidState = device.caseLidState,
            isBeingWorn = device.isBeingWorn ?: false,
            isEitherPodInEar = device.isEitherPodInEar ?: false,
            onePodMode = reactions.onePodMode,
            supportsEarDetection = device.hasEarDetection,
        ).shouldConnect
    }

    internal fun evaluateAutoConnect(
        mainDeviceAddr: String?,
        hasBondedDevice: Boolean,
        isAlreadyConnected: Boolean,
        condition: AutoConnectCondition,
        lidState: DualApplePods.LidState?,
        isBeingWorn: Boolean,
        isEitherPodInEar: Boolean,
        onePodMode: Boolean,
        supportsEarDetection: Boolean,
    ): AutoConnectDecision {
        if (mainDeviceAddr.isNullOrEmpty()) {
            return AutoConnectDecision(false, "No main device address")
        }
        if (!hasBondedDevice) {
            return AutoConnectDecision(false, "No bonded device")
        }
        if (isAlreadyConnected) {
            return AutoConnectDecision(false, "Already connected")
        }
        return when (condition) {
            AutoConnectCondition.WHEN_SEEN -> AutoConnectDecision(true, "WHEN_SEEN: device visible")
            AutoConnectCondition.CASE_OPEN -> {
                if (lidState == null) {
                    AutoConnectDecision(true, "CASE_OPEN: unsupported device, permissive fallback")
                } else when (lidState) {
                    DualApplePods.LidState.OPEN -> AutoConnectDecision(true, "CASE_OPEN: lid is open")
                    else -> AutoConnectDecision(false, "CASE_OPEN: lid is $lidState")
                }
            }
            AutoConnectCondition.IN_EAR -> {
                if (!supportsEarDetection) {
                    return AutoConnectDecision(true, "IN_EAR: unsupported device, permissive fallback")
                }
                val inEar = if (onePodMode) isEitherPodInEar else isBeingWorn
                AutoConnectDecision(inEar, if (inEar) "IN_EAR: pod in ear" else "IN_EAR: not in ear")
            }
        }
    }

    data class AutoConnectDecision(
        val shouldConnect: Boolean,
        val reason: String,
    )

    companion object {
        private val TAG = logTag("Reaction", "AutoConnect")
    }
}
