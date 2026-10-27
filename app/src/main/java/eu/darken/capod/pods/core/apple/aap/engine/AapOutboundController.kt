package eu.darken.capod.pods.core.apple.aap.engine

import eu.darken.capod.common.TimeSource
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting

internal data class VerificationState(
    val command: AapCommand,
    val attempt: Int = 0,
)

internal data class OutboundRuntimeState(
    val pendingCommands: List<AapCommand> = emptyList(),
    val verification: VerificationState? = null,
    val customEqVerification: VerificationState? = null,
)

internal data class OutboundDecision(
    val podState: AapPodState,
    val runtimeState: OutboundRuntimeState,
    val commandsToSend: List<AapCommand> = emptyList(),
    val timerActions: List<EngineTimerAction> = emptyList(),
    val logs: List<String> = emptyList(),
    val rejectedCommand: AapCommand? = null,
)

internal class AapOutboundController(
    timeSource: TimeSource,
) {

    private val coordinator = AapSettingsCoordinator(timeSource)

    companion object {
        /**
         * How long to wait for the device to confirm a setting write before treating it as diverged.
         *
         * AirPods Pro 3 answer a listening mode write in roughly 0.8-1.1s (measured: 833ms, 887ms,
         * 956ms, 1008ms). A 1000ms deadline sits inside that spread, so a perfectly healthy reply
         * could land just after the timer and trigger a bogus "Divergence detected" plus a
         * redundant re-send. The deadline is only a backstop now: [onStateObserved] resolves the
         * verification as soon as a matching report arrives, so raising this does not slow the
         * success path, only how long a genuinely unanswered write waits.
         *
         * Kept at roughly 2x the worst measured reply rather than higher, because a real rejection
         * still costs two full deadlines before the user is told about it.
         */
        const val VERIFICATION_TIMEOUT_MS = 2000L
    }

    fun onCommandRequested(
        podState: AapPodState,
        runtimeState: OutboundRuntimeState,
        command: AapCommand,
    ): OutboundDecision {
        // SetDeviceName and SetDynamicEndOfCharge bypass ear-gating:
        //  - Rename is a user-initiated metadata change, independent of wear state.
        //  - Charge cap (setting 0x3B) is toggled while pods sit in the closed case; queueing
        //    it until worn would make the toggle look broken for its main use case.
        if (command !is AapCommand.SetDeviceName && command !is AapCommand.SetDynamicEndOfCharge && command !is AapCommand.SetHeadTracking && command !is AapCommand.SetHeartRateTracking) {
            val earDetection = podState.setting<AapSetting.EarDetection>()
            if (earDetection != null && !earDetection.isEitherPodInEar) {
                val result = coordinator.enqueue(runtimeState.pendingCommands, command, podState)
                return OutboundDecision(
                    podState = applyPendingSnapshot(
                        result.optimisticState ?: podState,
                        result.snapshot,
                        if (command is AapCommand.SetAncMode) command.mode else result.snapshot.pendingAncMode,
                    ).let { if (command is AapCommand.SetCustomEq) it.copy(pendingCustomEq = command.value) else it },
                    runtimeState = runtimeState.copy(pendingCommands = result.pendingCommands),
                    logs = listOf("No pod in ear, queuing: ${command::class.simpleName}"),
                )
            }
        }

        var updatedPodState = podState
        var updatedRuntimeState = runtimeState
        if (command is AapCommand.SetAncMode || command is AapCommand.SetCustomEq) {
            val result = coordinator.removeFromQueue(runtimeState.pendingCommands, command::class)
            updatedPodState = applyPendingSnapshot(
                updatedPodState, result.snapshot,
                if (command is AapCommand.SetAncMode) command.mode else updatedPodState.pendingAncMode,
            )
            if (command is AapCommand.SetCustomEq) updatedPodState = updatedPodState.copy(pendingCustomEq = command.value)
            updatedRuntimeState = updatedRuntimeState.copy(pendingCommands = result.pendingCommands)
        }

        coordinator.optimisticUpdate(updatedPodState, command)?.let { updatedPodState = it }

        val verificationCheck = coordinator.verificationFor(command)
        return OutboundDecision(
            podState = updatedPodState,
            runtimeState = updatedRuntimeState.copy(
                verification = if (command is AapCommand.SetCustomEq) updatedRuntimeState.verification else
                    verificationCheck?.let { VerificationState(command = command, attempt = 0) } ?: updatedRuntimeState.verification,
                customEqVerification = if (command is AapCommand.SetCustomEq)
                    VerificationState(command = command) else updatedRuntimeState.customEqVerification,
            ),
            commandsToSend = listOf(command),
            timerActions = if (verificationCheck != null) {
                listOf(EngineTimerAction.Start(
                    if (command is AapCommand.SetCustomEq) EngineTimerKey.CustomEqVerification else EngineTimerKey.Verification,
                    VERIFICATION_TIMEOUT_MS,
                ))
            } else {
                emptyList()
            },
        )
    }

    fun onEarDetectionInEar(
        podState: AapPodState,
        runtimeState: OutboundRuntimeState,
    ): OutboundDecision {
        val result = coordinator.flush(runtimeState.pendingCommands)
        if (result.commands.isEmpty()) return OutboundDecision(podState, runtimeState.copy(pendingCommands = result.pendingCommands))

        val ancCommand = result.commands.firstOrNull { it is AapCommand.SetAncMode } as? AapCommand.SetAncMode
        val updatedPodState = applyPendingSnapshot(
            podState,
            result.snapshot,
            ancCommand?.mode ?: podState.pendingAncMode,
        )
        val eqCommand = result.commands.filterIsInstance<AapCommand.SetCustomEq>().lastOrNull()
        val toVerify = result.commands.firstOrNull { it is AapCommand.SetAncMode }
            ?: result.commands.lastOrNull { it !is AapCommand.SetCustomEq }
        val verificationCheck = toVerify?.let { coordinator.verificationFor(it) }
        return OutboundDecision(
            podState = updatedPodState.copy(pendingCustomEq = eqCommand?.value ?: updatedPodState.pendingCustomEq),
            runtimeState = runtimeState.copy(
                pendingCommands = result.pendingCommands,
                verification = if (verificationCheck != null) {
                    VerificationState(command = checkNotNull(toVerify), attempt = 0)
                } else {
                    runtimeState.verification
                },
                customEqVerification = eqCommand?.let { VerificationState(it) } ?: runtimeState.customEqVerification,
            ),
            commandsToSend = result.commands,
            timerActions = buildList {
                if (verificationCheck != null) add(EngineTimerAction.Start(EngineTimerKey.Verification, VERIFICATION_TIMEOUT_MS))
                if (eqCommand != null) add(EngineTimerAction.Start(EngineTimerKey.CustomEqVerification, VERIFICATION_TIMEOUT_MS))
            },
            logs = listOf("Pod in ear, flushing ${result.commands.size} queued commands"),
        )
    }

    /**
     * Re-check the outstanding verification against freshly applied device state, so a confirmation
     * is honoured the moment it arrives instead of waiting out [VERIFICATION_TIMEOUT_MS] and racing
     * it.
     *
     * ANC and custom EQ have no optimistic state write. For EQ, only a fresh 0x63 setting event
     * can clear its own verification slot; an earlier identical report or unrelated frame cannot.
     * Other commands retain the existing timer-based verification behavior.
     */
    fun onStateObserved(
        podState: AapPodState,
        runtimeState: OutboundRuntimeState,
        observedKey: kotlin.reflect.KClass<out AapSetting>,
    ): OutboundDecision {
        if (observedKey == AapSetting.CustomEq::class) {
            val eqVerification = runtimeState.customEqVerification ?: return OutboundDecision(podState, runtimeState)
            val check = coordinator.verificationFor(eqVerification.command) ?: return OutboundDecision(podState, runtimeState)
            if (!check(podState)) return OutboundDecision(podState, runtimeState)
            return OutboundDecision(
                podState = clearPendingForCommand(podState, eqVerification.command).copy(
                    customEqConfirmationRevision = podState.customEqConfirmationRevision + 1,
                ),
                runtimeState = runtimeState.copy(customEqVerification = null),
                timerActions = listOf(EngineTimerAction.Cancel(EngineTimerKey.CustomEqVerification)),
            )
        }
        val verification = runtimeState.verification ?: return OutboundDecision(podState, runtimeState)
        if (verification.command !is AapCommand.SetAncMode) return OutboundDecision(podState, runtimeState)
        val check = coordinator.verificationFor(verification.command)
            ?: return OutboundDecision(podState, runtimeState)
        if (!check(podState)) return OutboundDecision(podState, runtimeState)
        return OutboundDecision(
            podState = clearPendingForCommand(podState, verification.command),
            runtimeState = runtimeState.copy(verification = null),
            timerActions = listOf(EngineTimerAction.Cancel(EngineTimerKey.Verification)),
        )
    }

    fun onVerificationTimerFired(
        podState: AapPodState,
        runtimeState: OutboundRuntimeState,
    ): OutboundDecision {
        val verification = runtimeState.verification ?: return OutboundDecision(podState, runtimeState)
        val check = coordinator.verificationFor(verification.command)
            ?: return OutboundDecision(
                podState = podState,
                runtimeState = runtimeState.copy(verification = null),
            )

        if (check(podState)) {
            return OutboundDecision(
                podState = clearPendingForCommand(podState, verification.command),
                runtimeState = runtimeState.copy(verification = null),
            )
        }

        val ear = podState.setting<AapSetting.EarDetection>()
        if (ear != null && !ear.isEitherPodInEar) {
            // Drop the pending mode too: nothing is going to confirm it now, and leaving it set
            // would keep the UI showing a mode the device never reached.
            return OutboundDecision(
                podState = clearPendingForCommand(podState, verification.command),
                runtimeState = runtimeState.copy(verification = null),
                logs = listOf("Verification aborted for ${verification.command::class.simpleName}: no pod in ear"),
            )
        }

        if (verification.attempt == 0) {
            return OutboundDecision(
                podState = podState,
                runtimeState = runtimeState.copy(verification = verification.copy(attempt = 1)),
                commandsToSend = listOf(verification.command),
                timerActions = listOf(EngineTimerAction.Start(EngineTimerKey.Verification, VERIFICATION_TIMEOUT_MS)),
                logs = listOf("Divergence detected for ${verification.command::class.simpleName}, re-sending"),
            )
        }

        return OutboundDecision(
            podState = clearPendingForCommand(podState, verification.command),
            runtimeState = runtimeState.copy(verification = null),
            logs = listOf("Rejected after retry: ${verification.command::class.simpleName}"),
            rejectedCommand = verification.command,
        )
    }

    fun onCustomEqVerificationTimerFired(
        podState: AapPodState,
        runtimeState: OutboundRuntimeState,
    ): OutboundDecision {
        val verification = runtimeState.customEqVerification ?: return OutboundDecision(podState, runtimeState)
        // An older identical report in podState cannot acknowledge this write. Only a matching
        // CustomEq setting event received after the request clears this slot.
        val ear = podState.setting<AapSetting.EarDetection>()
        if (ear != null && !ear.isEitherPodInEar) {
            return OutboundDecision(
                podState = clearPendingForCommand(podState, verification.command),
                runtimeState = runtimeState.copy(customEqVerification = null),
                rejectedCommand = verification.command,
            )
        }
        if (verification.attempt == 0) {
            return OutboundDecision(
                podState = podState,
                runtimeState = runtimeState.copy(customEqVerification = verification.copy(attempt = 1)),
                commandsToSend = listOf(verification.command),
                timerActions = listOf(EngineTimerAction.Start(EngineTimerKey.CustomEqVerification, VERIFICATION_TIMEOUT_MS)),
            )
        }
        return OutboundDecision(
            podState = clearPendingForCommand(podState, verification.command),
            runtimeState = runtimeState.copy(customEqVerification = null),
            rejectedCommand = verification.command,
        )
    }

    private fun clearPendingForCommand(
        podState: AapPodState,
        command: AapCommand,
    ): AapPodState = when {
        command is AapCommand.SetAncMode && podState.pendingAncMode == command.mode -> podState.copy(pendingAncMode = null)
        command is AapCommand.SetCustomEq && podState.pendingCustomEq == command.value -> podState.copy(pendingCustomEq = null)
        else -> podState
    }

    private fun applyPendingSnapshot(
        podState: AapPodState,
        snapshot: AapSettingsCoordinator.PendingSnapshot,
        ancPendingMode: AapSetting.AncMode.Value?,
    ): AapPodState = podState.copy(
        pendingAncMode = ancPendingMode,
        pendingSettingsCount = snapshot.count,
    )
}
