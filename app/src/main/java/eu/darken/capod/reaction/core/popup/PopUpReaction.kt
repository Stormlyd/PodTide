package eu.darken.capod.reaction.core.popup

import eu.darken.capod.common.TimeSource
import eu.darken.capod.common.bluetooth.BluetoothAddress
import eu.darken.capod.common.bluetooth.BluetoothDevice2
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.coroutine.AppScope
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.flow.setupCommonEventHandlers
import eu.darken.capod.common.flow.withPrevious
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.ble.devices.DualApplePods
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PopUpReaction @Inject constructor(
    private val deviceMonitor: DeviceMonitor,
    private val bluetoothManager: BluetoothManager2,
    private val timeSource: TimeSource,
    private val popUpStateStore: PopUpStateStore,
    @AppScope private val scope: CoroutineScope,
) {

    private val caseCoolDowns = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /**
     * Open popup rounds keyed like [caseCoolDowns], valued with the [TimeSource.elapsedRealtime]
     * at which the round started. A round opens with a case-open popup and closes when the lid
     * closes or the device disconnects. A connection that completes inside the round belongs to
     * the same user interaction, so it updates the visible popup instead of showing another one.
     */
    private val caseRounds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun monitorCase(): Flow<Event> = deviceMonitor.primaryDeviceByTier
        .distinctUntilChangedBy {
            // Re-emit on profile changes (eligibility), raw BLE changes (content), AND the derived
            // lid state. The latter is essential: caseLidState is recovered from history, so it can
            // flip OPEN<->CLOSED while the *selected* frame's raw bytes stay identical (e.g. a steady
            // out-of-case frame while a sibling in-case frame updates). Keying on rawDataHex alone
            // would swallow that transition and the popup would miss its show/hide.
            // A new measurement must reach the freshness transition below even when its bytes and
            // lid state match a stale frame received just before it.
            listOf(it?.profileId, it?.reactions?.showStatusPopUp, it?.rawDataHex,
                it?.caseLidState,
                if (it?.reactions?.showStatusPopUp == true) it.ble?.scanResult?.generatedAtNanos else null)
        }
        .withPrevious()
        .setupCommonEventHandlers(TAG) { "popUpCase" }
        .mapNotNull { (previous, current) ->
            val wasEligible = previous?.reactions?.showStatusPopUp == true
            val isEligible = current?.reactions?.showStatusPopUp == true

            // Eligibility transition: just lost it (toggled off, or switched to a profile
            // with the toggle off). Dismiss any visible overlay immediately.
            if (wasEligible && !isEligible) {
                log(TAG) { "Popup eligibility lost, emitting Hide" }
                return@mapNotNull Event.PopupHide(timeSource.now())
            }

            if (!isEligible) return@mapNotNull null
            if (current.caseLidState == null) return@mapNotNull null
            // An already-connected device gets its popup from the connection event. Ignore
            // changing/stale lid broadcasts until it disconnects to avoid surprise redisplays.
            if (current.isSystemConnected && current.caseLidState == DualApplePods.LidState.OPEN) return@mapNotNull null

            log(TAG, VERBOSE) {
                "previous=${previous?.caseLidState}, current=${current.caseLidState}"
            }
            val isSameDeviceOrProfile = previous?.profileId != null && previous.profileId == current.profileId
            val isSameDeviceWithCaseNowOpen = isSameDeviceOrProfile && previous?.caseLidState != current.caseLidState
            val isNewDeviceWithJustOpenedCase = !isSameDeviceOrProfile
                && previous != null
                && previous.caseLidState != null
                && previous.caseLidState != current.caseLidState

            val isFirstFreshOpen = previous == null && isCaseOpenBroadcastFresh(current)
            val isFreshOpenAfterStale = isSameDeviceOrProfile &&
                current.caseLidState == DualApplePods.LidState.OPEN &&
                isCaseOpenBroadcastFresh(current) && !isCaseOpenBroadcastFresh(previous) &&
                current.profileId?.let { !caseCoolDowns.containsKey(it) } == true
            if (!isSameDeviceWithCaseNowOpen && !isNewDeviceWithJustOpenedCase &&
                !isFirstFreshOpen && !isFreshOpenAfterStale) {
                return@mapNotNull null
            }
            log(TAG) { "Case lid status changed for monitored device." }

            throttleCasePopUps(current)
        }

    internal fun throttleCasePopUps(current: PodDevice): Event? {
        if (current.caseLidState == DualApplePods.LidState.OPEN && !isCaseOpenBroadcastFresh(current)) {
            log(TAG, VERBOSE) {
                "Case popup suppressed: lid OPEN but broadcast not fresh " +
                    "(age=${current.ble?.scanResult?.observationAgeMillis(timeSource)})"
            }
            return null
        }
        val cooldownKey = current.profileId ?: current.identifier?.toString() ?: return null
        val now = timeSource.now()
        val lastShown = caseCoolDowns[cooldownKey]

        val decision = evaluateCasePopUp(
            currentLidState = current.caseLidState,
            lastShownTime = lastShown,
            now = now,
        )

        log(TAG) { "Case popup decision: ${decision.reason}" }

        if (decision.shouldResetCooldown) {
            caseCoolDowns.remove(cooldownKey)
        }

        return when {
            decision.shouldShow -> {
                caseCoolDowns[cooldownKey] = now
                openRound(cooldownKey)
                // One greppable line per popup: enough to tell a genuine lid-open from a phantom
                // frame or a recovered stale state when a user reports a surprising popup.
                log(TAG) {
                    "Case popup show: profile=${current.profileId} lid=${current.caseLidState} " +
                        "sealed=${current.isCaseSealed} bothInCase=${(current.ble as? DualApplePods)?.areBothPodsInCase} " +
                        "ageMs=${current.ble?.scanResult?.observationAgeMillis(timeSource)} " +
                        "connected=${current.isSystemConnected} reason=${decision.reason}"
                }
                Event.PopupShow(eventAt = now, device = current)
            }

            decision.shouldHide -> {
                // Don't stamp the cooldown on a non-CLOSED hide (UNKNOWN/NOT_IN_CASE). Refreshing it
                // here would let a transient UNKNOWN (e.g. a brief out-of-case frame) suppress a
                // genuine OPEN for the whole cooldown window. CLOSED still resets it above.
                if (decision.shouldResetCooldown) closeRound(cooldownKey)
                Event.PopupHide(now)
            }

            else -> null
        }
    }

    private fun openRound(key: String) {
        val startedAt = timeSource.elapsedRealtime()
        caseRounds[key] = startedAt
        scope.launch { popUpStateStore.saveRound(key, startedAt) }
    }

    private fun closeRound(key: String) {
        if (caseRounds.remove(key) != null) {
            scope.launch { popUpStateStore.clearRound(key) }
        }
    }

    /**
     * True while the case-open round for [profileId] is still open. Bounded by [ROUND_WINDOW] so a
     * forgotten record cannot suppress a later, unrelated connection popup forever.
     */
    internal fun isSameRound(profileId: String?): Boolean {
        val startedAt = profileId?.let { caseRounds[it] } ?: return false
        val age = timeSource.elapsedRealtime() - startedAt
        return age in 0..ROUND_WINDOW.toMillis()
    }

    /**
     * Re-seeds round state after a process restart. Without it, a restart while the lid is still
     * open shows the same popup again, and a connection that completes right after the restart is
     * treated as a brand new round instead of updating the popup the user already saw.
     */
    private suspend fun restorePersistedRound() {
        val round = popUpStateStore.loadRestorableRound() ?: return
        caseRounds[round.profileId] = round.shownAtElapsedMillis
        // Mirror the round into the case cooldown so the still-open lid does not immediately
        // re-trigger a show. The cooldown is wall-clock based, so translate the elapsed delta.
        val ageMillis = timeSource.elapsedRealtime() - round.shownAtElapsedMillis
        if (ageMillis >= 0) {
            caseCoolDowns[round.profileId] = timeSource.now().minusMillis(ageMillis)
        }
        log(TAG) { "Restored popup round for ${round.profileId} (${ageMillis}ms old)." }
    }

    private val connectionCoolDowns = java.util.concurrent.ConcurrentHashMap<BluetoothAddress, Instant>()

    private data class ConnectionWindow(
        val direct: BluetoothDevice2?,
        val broadcast: PodDevice?,
        val eligible: Boolean,
    )

    private fun monitorConnection(): Flow<Event> = combine(
        bluetoothManager.connectedDevices,
        deviceMonitor.primaryDeviceByTier.distinctUntilChangedBy {
            Triple(it?.profileId, it?.reactions?.showStatusPopUp, it?.rawDataHex)
        },
    ) { devices, broadcast ->
        val eligible = broadcast?.reactions?.showStatusPopUp == true
        if (!eligible) {
            ConnectionWindow(direct = null, broadcast = null, eligible = false)
        } else {
            val primaryAddr = broadcast.address
                ?: return@combine ConnectionWindow(direct = null, broadcast = null, eligible = false)
            val direct = devices.singleOrNull { it.address == primaryAddr }.also {
                log(TAG, VERBOSE) { "Connected main device is $it" }
            }
            if (direct == null) {
                connectionCoolDowns.remove(primaryAddr).also {
                    if (it != null) log(TAG) { "Cleared connection cooldown for $primaryAddr due to disconnect" }
                }
            }
            ConnectionWindow(direct = direct, broadcast = broadcast, eligible = true)
        }
    }
        .withPrevious()
        .mapNotNull { (previous, current) ->
            val isEligible = current.eligible

            // The first emission after a (re)start is a snapshot of the current system state, not
            // an event. A device that is already connected must not be reported as a new
            // connection, and an empty snapshot must not be read as a disconnect either.
            if (previous == null) {
                val snapshotConnected = current.direct
                if (isEligible && snapshotConnected != null) {
                    connectionCoolDowns[snapshotConnected.address] = timeSource.now()
                    log(TAG) { "Baseline snapshot: ${snapshotConnected.address} is already connected, not a new connection." }
                }
                return@mapNotNull null
            }

            val wasEligible = previous.eligible

            // Eligibility transition: dismiss any visible overlay immediately.
            if (wasEligible && !isEligible) {
                log(TAG) { "Popup eligibility lost, emitting Hide" }
                return@mapNotNull Event.PopupHide(timeSource.now())
            }

            if (!isEligible) return@mapNotNull null

            val previousConnected = previous.direct
            log(TAG, VERBOSE) { "previousConnected: $previousConnected" }
            val previousBroadcasted = previous.broadcast
            log(TAG, VERBOSE) { "previousBroadcasted: $previousBroadcasted" }
            val currentConnected = current.direct
            log(TAG, VERBOSE) { "currentConnected: $currentConnected" }
            val currentBroadcasted = current.broadcast
            log(TAG, VERBOSE) { "currentBroadcasted: $currentBroadcasted" }

            if (previousConnected != null && previousBroadcasted != null && currentConnected == null) {
                // The connection ended, so the popup round ended with it. A later open or connect
                // is a genuinely new interaction.
                previousBroadcasted.profileId?.let { closeRound(it) }
                return@mapNotNull Event.PopupHide(timeSource.now())
            }

            if (currentConnected == null || currentBroadcasted == null) {
                return@mapNotNull null
            }

            // A directly observed audio connection identifies the paired profile even if no BLE
            // advertisement is available. Cached identity/battery timestamps remain unchanged.
            val now = timeSource.now()
            val broadcastAge = currentBroadcasted.ble?.seenLastAt?.let { Duration.between(it, now) } ?: Duration.ZERO
            val connectionAge = Duration.between(currentConnected.seenFirstAt, now)

            val decision = evaluateConnectionPopUp(
                hasConnectedDevice = true,
                hasPodDevice = true,
                hasAlreadyShown = connectionCoolDowns.containsKey(currentConnected.address),
                broadcastAge = broadcastAge,
                connectionAge = connectionAge,
                isObservedPairedConnection = true,
                isCaseSealed = currentBroadcasted.isCaseSealed,
            )

            log(TAG) {
                "Connection popup decision: ${decision.reason} " +
                    "(profile=${currentBroadcasted.profileId} ble=${currentBroadcasted.ble != null} " +
                    "lid=${currentBroadcasted.caseLidState} sealed=${currentBroadcasted.isCaseSealed} " +
                    "connAge=${connectionAge.toSeconds()}s broadcastAge=${broadcastAge.toSeconds()}s)"
            }

            if (decision.shouldShow) {
                connectionCoolDowns[currentConnected.address] = now
                if (isSameRound(currentBroadcasted.profileId)) {
                    // Same open-case round: refresh what is on screen instead of popping up again.
                    log(TAG) { "Connection completed the open-case round, updating popup content." }
                    Event.PopupUpdate(eventAt = now, device = currentBroadcasted)
                } else {
                    Event.PopupShow(eventAt = now, device = currentBroadcasted)
                }
            } else {
                null
            }
        }
        .setupCommonEventHandlers(TAG) { "popUpConnection" }

    /**
     * Backstop for case-open popups that never receive a CLOSED frame because the device left BLE
     * range while the lid was open — otherwise the overlay lingers until manually dismissed (one of
     * the symptoms in #598). A ticker re-checks the primary device's freshness; once a previously
     * fresh OPEN broadcast goes stale past [CASE_OPEN_STALE_TIMEOUT] (no newer advertisement, or the
     * device dropped to cache-only), a single Hide is emitted. The lid-driven [monitorCase] still
     * handles the normal close; a redundant Hide here is harmless ([PopUpWindow.close] is idempotent).
     */
    private fun monitorCaseStaleClose(): Flow<Event> = combine(
        deviceMonitor.primaryDeviceByTier,
        staleCheckTicker(),
    ) { device, _ -> isCaseOpenBroadcastFresh(device) }
        .distinctUntilChanged()
        .withPrevious()
        .mapNotNull { (wasFresh, isFresh) ->
            if (wasFresh == true && !isFresh) {
                log(TAG) { "Case-open broadcast went stale, emitting Hide" }
                Event.PopupHide(timeSource.now())
            } else {
                null
            }
        }
        .setupCommonEventHandlers(TAG) { "popUpCaseStale" }

    /** True while the primary device is eligible and currently advertising a fresh OPEN lid. */
    internal fun isCaseOpenBroadcastFresh(device: PodDevice?): Boolean {
        if (device?.reactions?.showStatusPopUp != true) return false
        if (device.caseLidState != DualApplePods.LidState.OPEN) return false
        // The lid is a BLE-only measurement. Callback delivery, cached timestamps and AAP activity
        // cannot extend its freshness; a wall-clock adjustment cannot rejuvenate it either.
        val age = device.ble?.scanResult?.observationAgeMillis(timeSource) ?: return false
        return age <= CASE_OPEN_STALE_TIMEOUT.toMillis()
    }

    private fun staleCheckTicker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(STALE_CHECK_INTERVAL.toMillis())
        }
    }

    fun monitor(): Flow<Event> = flow {
        restorePersistedRound()
        emitAll(merge(monitorCase(), monitorConnection(), monitorCaseStaleClose()))
    }

    sealed class Event {
        data class PopupShow(
            val eventAt: Instant,
            val device: PodDevice,
        ) : Event()

        /**
         * The popup for this interaction is already on screen (same round); refresh its content
         * without creating or re-animating an overlay.
         */
        data class PopupUpdate(
            val eventAt: Instant,
            val device: PodDevice,
        ) : Event()

        data class PopupHide(
            val eventAt: Instant,
        ) : Event()
    }

    internal fun evaluateCasePopUp(
        currentLidState: DualApplePods.LidState?,
        lastShownTime: Instant?,
        now: Instant,
        cooldownDuration: Duration = Duration.ofSeconds(10),
    ): CasePopUpDecision {
        if (currentLidState == null) {
            return CasePopUpDecision(
                shouldShow = false,
                shouldHide = false,
                shouldResetCooldown = false,
                reason = "Non-DualApplePods device",
            )
        }
        return when (currentLidState) {
            DualApplePods.LidState.OPEN -> {
                val sinceLastPop = lastShownTime?.let { Duration.between(it, now) }
                if (sinceLastPop == null || sinceLastPop >= cooldownDuration) {
                    CasePopUpDecision(
                        shouldShow = true,
                        shouldHide = false,
                        shouldResetCooldown = false,
                        reason = "Lid OPEN, cooldown expired or first show",
                    )
                } else {
                    CasePopUpDecision(
                        shouldShow = false,
                        shouldHide = false,
                        shouldResetCooldown = false,
                        reason = "Lid OPEN, still on cooldown ($sinceLastPop)",
                    )
                }
            }

            DualApplePods.LidState.CLOSED -> CasePopUpDecision(
                shouldShow = false,
                shouldHide = true,
                shouldResetCooldown = true,
                reason = "Lid CLOSED, resetting cooldown",
            )

            else -> CasePopUpDecision(
                shouldShow = false,
                shouldHide = true,
                shouldResetCooldown = false,
                reason = "Lid $currentLidState, refreshing cooldown",
            )
        }
    }

    data class CasePopUpDecision(
        val shouldShow: Boolean,
        val shouldHide: Boolean,
        val shouldResetCooldown: Boolean,
        val reason: String,
    )

    internal fun evaluateConnectionPopUp(
        hasConnectedDevice: Boolean,
        hasPodDevice: Boolean,
        hasAlreadyShown: Boolean,
        broadcastAge: Duration,
        connectionAge: Duration,
        maxConnectionAge: Duration = Duration.ofSeconds(30),
        maxAgeDiff: Duration = Duration.ofSeconds(30),
        isObservedPairedConnection: Boolean = false,
        /**
         * The pods are shut inside their case. An audio connection in that state cannot be a
         * user-facing "your earbuds connected" moment, so it must not pop up cached battery.
         */
        isCaseSealed: Boolean = false,
    ): ConnectionPopUpDecision {
        if (!hasConnectedDevice) {
            return ConnectionPopUpDecision(false, "No connected device")
        }
        if (!hasPodDevice) {
            return ConnectionPopUpDecision(false, "No pod device found")
        }
        if (isCaseSealed) {
            return ConnectionPopUpDecision(false, "Case is shut with both pods inside, ignoring phantom connection")
        }
        if (connectionAge.abs() > maxConnectionAge) {
            return ConnectionPopUpDecision(false, "Connection is no longer new")
        }
        if (!isObservedPairedConnection && broadcastAge.abs() > (connectionAge.abs() + maxAgeDiff)) {
            return ConnectionPopUpDecision(false, "Broadcast too old, likely false positive")
        }
        if (hasAlreadyShown) {
            return ConnectionPopUpDecision(false, "Already shown for this connection")
        }
        return ConnectionPopUpDecision(true, "New connection detected")
    }

    data class ConnectionPopUpDecision(
        val shouldShow: Boolean,
        val reason: String,
    )

    companion object {
        private val TAG = logTag("Reaction", "PopUp")

        /** A case-open popup is force-dismissed once its OPEN broadcast hasn't refreshed for this long. */
        private val CASE_OPEN_STALE_TIMEOUT: Duration = Duration.ofSeconds(4)

        /** How often the stale-close backstop re-evaluates freshness. */
        private val STALE_CHECK_INTERVAL: Duration = Duration.ofSeconds(2)

        /**
         * How long an open-case round stays valid. A connection completing inside this window
         * updates the popup that is already on screen instead of showing a second one.
         */
        internal val ROUND_WINDOW: Duration = Duration.ofSeconds(90)
    }
}
