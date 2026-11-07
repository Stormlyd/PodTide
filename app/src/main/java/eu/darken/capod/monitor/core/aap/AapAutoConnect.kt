package eu.darken.capod.monitor.core.aap

import eu.darken.capod.common.bluetooth.BluetoothDevice2
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.debug.logging.Logging.Priority.INFO
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.flow.setupCommonEventHandlers
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.AapDisconnectEvent
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the AAP control channel of *already system-connected* Apple pods alive.
 *
 * The recovery loop is a single per-address task driven by the current state of
 * profiles + system connections + bonded devices + AAP state, so:
 *
 * - The initial connect after a service start and the reconnect after an AAP drop share one
 *   scheduler; two entry points can never race to build a socket to the same address.
 * - Recovery keeps going for as long as the target still qualifies (configured, bonded and
 *   system-connected). It does not stop after a fixed number of attempts, and it does not
 *   require the target to be visible over BLE: once the system is connected the pods may stop
 *   advertising entirely, which used to abort every recovery attempt.
 * - Success is defined by the AAP state reaching [AapPodState.ConnectionState.READY], not by
 *   [AapConnectionManager.connect] returning (that only means the socket/handshake was started).
 * - Cancelling the collector (user stops monitoring, service stops, Bluetooth turns off) cancels
 *   every recovery task; losing one of the preconditions cancels just that address.
 */
@Singleton
class AapAutoConnect @Inject constructor(
    private val aapManager: AapConnectionManager,
    private val profilesRepo: DeviceProfilesRepo,
    private val bluetoothManager: BluetoothManager2,
) {
    private val processedModelCorrections = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Per-address count of consecutive attempts that never reached READY (failed socket connects and
     * handshake-timeout disconnects). Drives the escalating recovery cooldown so a device that keeps
     * stalling its handshake in a crowded RF environment doesn't churn a tight reconnect loop.
     * Reset to 0 once a session works (a was-ready disconnect), the system re-connects the device, or
     * the device leaves.
     */
    private val preReadyFailures = ConcurrentHashMap<String, Int>()

    /** Cooldown (ms) to wait before the next attempt, given how many consecutive pre-READY failures we've seen. */
    private fun cooldownFor(address: String): Long {
        val failures = preReadyFailures[address] ?: 0
        if (failures <= 0) return 0L
        return HANDSHAKE_BACKOFF[minOf(failures - 1, HANDSHAKE_BACKOFF.lastIndex)]
    }

    fun monitor(): Flow<Unit> = merge(
        recoveryDriver(),
        penaltyTracker(),
        correctModelOnDeviceInfo(),
    )

    private data class RecoveryInput(
        val profiles: List<DeviceProfile>,
        val connectedDevices: List<BluetoothDevice2>,
        val bondedDevices: Set<BluetoothDevice2>,
        val states: Map<String, AapPodState>,
    )

    /**
     * Declarative driver: on every state change it reconciles the set of recovery tasks with the set
     * of addresses that currently qualify, then lets each task run until the address stops qualifying
     * or reaches READY.
     */
    private fun recoveryDriver(): Flow<Unit> = channelFlow<Unit> {
        val jobs = mutableMapOf<String, Job>()
        var lastConnected: Set<String> = emptySet()
        try {
            combine(
                profilesRepo.profiles,
                bluetoothManager.connectedDevices,
                bluetoothManager.bondedDevices(),
                aapManager.allStates,
            ) { profiles, connectedDevices, bondedDevices, states ->
                RecoveryInput(profiles, connectedDevices, bondedDevices, states)
            }.collect { input ->
                val connected = input.connectedDevices.map { it.address }.toSet()

                // A fresh system connection (the user just put the pods in / opened the lid) is a new
                // session: drop the penalty so recovery reacts immediately instead of waiting out an
                // escalated cooldown earned by an earlier, unrelated outage.
                (connected - lastConnected).forEach { address ->
                    preReadyFailures.remove(address)
                }
                lastConnected = connected

                val bonded = input.bondedDevices.associateBy { it.address }
                val targets = input.profiles
                    .filter { profile ->
                        val address = profile.address
                        !address.isNullOrBlank() &&
                            address in connected &&
                            address in bonded &&
                            input.states[address]?.connectionState != AapPodState.ConnectionState.READY
                    }
                    .associateBy { it.address!! }

                jobs.keys.filter { it !in targets.keys }.forEach { address ->
                    jobs.remove(address)?.cancel()
                }
                targets.forEach { (address, profile) ->
                    if (jobs[address]?.isActive != true) {
                        val device = bonded[address] ?: return@forEach
                        jobs[address] = launch { recover(address, profile, device) }
                    }
                }
            }
        } finally {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }.setupCommonEventHandlers(TAG) { "recovery" }

    /**
     * Maintains the pre-READY penalty counters only; it never drives connections. A disconnect that
     * had a working session resets the penalty (prompt recovery), one that never reached READY
     * escalates it (a stalling handshake must not spin).
     */
    private fun penaltyTracker(): Flow<Unit> = channelFlow<Unit> {
        aapManager.disconnectEvents.collect { event ->
            if (event.wasEverReady) {
                preReadyFailures.remove(event.address)
            } else {
                preReadyFailures.merge(event.address, 1, Int::plus)
            }
        }
    }.setupCommonEventHandlers(TAG) { "penalties" }

    private suspend fun recover(
        address: String,
        profile: DeviceProfile,
        bonded: BluetoothDevice2,
    ) {
        val bluetoothDevice = bonded.internal ?: return
        var attempt = 0

        while (true) {
            val cooldown = cooldownFor(address)
            if (cooldown > 0) {
                log(TAG) { "AAP recovery cooldown ${cooldown}ms for $address (preReadyFailures=${preReadyFailures[address]})" }
                delay(cooldown)
            }

            if (aapManager.allStates.value[address]?.connectionState == AapPodState.ConnectionState.READY) {
                log(TAG, VERBOSE) { "AAP recovery already satisfied for $address" }
                preReadyFailures.remove(address)
                return
            }

            attempt++
            try {
                log(TAG) { "AAP recovery attempt $attempt for $address (${profile.label})" }
                aapManager.connect(address, bluetoothDevice, profile.model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "AAP recovery attempt $attempt failed for $address: ${e.message}" }
                preReadyFailures.merge(address, 1, Int::plus)
                continue
            }

            if (awaitReady(address)) {
                log(TAG) { "AAP recovery reached READY for $address after $attempt attempt(s)" }
                preReadyFailures.remove(address)
                return
            }

            // The socket was accepted but the handshake never completed (or dropped before READY).
            // Escalate and try again: the target still qualifies, so recovery must not stop here.
            log(TAG, WARN) { "AAP recovery attempt $attempt did not reach READY for $address" }
            preReadyFailures.merge(address, 1, Int::plus)
        }
    }

    /**
     * Waits for the AAP state to settle. [AapConnectionManager.connect] returning only means the
     * connection was started, so READY is the only evidence of a usable control channel.
     */
    private suspend fun awaitReady(address: String): Boolean {
        val states = aapManager.allStates
            .map { it[address]?.connectionState }
            .distinctUntilChanged()

        val started = withTimeoutOrNull(START_TIMEOUT_MS) {
            states.first { it != null && it != AapPodState.ConnectionState.DISCONNECTED }
        } ?: return false
        if (started == AapPodState.ConnectionState.READY) return true

        val settled = withTimeoutOrNull(READY_TIMEOUT_MS) {
            states.first {
                it == AapPodState.ConnectionState.READY || it == AapPodState.ConnectionState.DISCONNECTED
            }
        }
        return settled == AapPodState.ConnectionState.READY
    }

    private fun correctModelOnDeviceInfo(): Flow<Unit> = aapManager.allStates
        .map { states -> states.mapValues { (_, state) -> state.deviceInfo?.modelNumber } }
        .distinctUntilChanged()
        .map { modelNumbers ->
            // Clean up tracking for addresses that disconnected
            processedModelCorrections.retainAll(modelNumbers.keys)

            for ((address, modelNumber) in modelNumbers) {
                if (modelNumber == null) continue
                if (address in processedModelCorrections) continue
                processedModelCorrections.add(address)

                try {
                    val detectedModel = PodModel.fromModelNumber(modelNumber)
                    if (detectedModel == null) {
                        // Unmapped hardware number (e.g. a new case revision). Log it so a bug report
                        // carries the real identifier — never guess a model from an unknown number.
                        log(TAG, INFO) { "AAP reported unmapped model number '$modelNumber' for $address" }
                        continue
                    }

                    val profile = profilesRepo.profiles.first()
                        .filterIsInstance<AppleDeviceProfile>()
                        .firstOrNull { it.address == address }
                        ?: continue

                    if (profile.model == detectedModel) {
                        log(TAG, VERBOSE) { "AAP model confirmed for $address: $detectedModel" }
                        continue
                    }

                    // Same physical product, different model entry (wired vs wireless charging case).
                    // Record the more accurate label without tearing down a working control channel.
                    if (profile.model.isInterchangeableWith(detectedModel)) {
                        log(TAG) { "AAP model refined within family for $address: ${profile.model} -> $detectedModel" }
                        profilesRepo.updateProfile(profile.copy(model = detectedModel))
                        continue
                    }

                    log(TAG) { "AAP model mismatch for $address: profile=${profile.model}, detected=$detectedModel" }

                    // Allow key exchange (AapKeyPersister) to complete before disconnecting
                    delay(2.seconds)

                    aapManager.disconnect(address)
                    profilesRepo.updateProfile(profile.copy(model = detectedModel))
                    log(TAG) { "AAP model corrected for $address: ${profile.model} -> $detectedModel" }

                    // Reconnect explicitly
                    val bonded = bluetoothManager.bondedDevices().first()
                        .firstOrNull { it.address == address }
                    if (bonded != null) {
                        aapManager.connect(address, bonded.internal!!, detectedModel)
                        log(TAG) { "AAP reconnected $address with corrected model $detectedModel" }
                    }
                } catch (e: Exception) {
                    log(TAG, WARN) { "AAP model correction failed for $address: ${e.message}" }
                }
            }
        }
        .setupCommonEventHandlers(TAG) { "correctModel" }

    companion object {
        private val TAG = logTag("Monitor", "AapAutoConnect")

        /** How long a started connection may stay non-DISCONNECTED before we call it a failed attempt. */
        internal const val START_TIMEOUT_MS = 3_000L

        /** How long a started handshake may take to reach READY before we treat it as a failed attempt. */
        internal const val READY_TIMEOUT_MS = 10_000L

        /**
         * Gentle escalating cooldown (ms) applied before a recovery attempt, indexed by consecutive
         * pre-READY failures minus one. The ladder is 2 s → 5 s and then cycles: a stalled handshake
         * should retry within seconds (a user who just put the pods in expects controls back quickly),
         * while a single attempt already costs up to [READY_TIMEOUT_MS], so this cannot spin.
         */
        internal val HANDSHAKE_BACKOFF = longArrayOf(2_000, 5_000)
    }
}