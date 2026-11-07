package eu.darken.capod.pods.core.apple.ble

import eu.darken.capod.common.bluetooth.BleScanResult
import eu.darken.capod.common.bluetooth.logSummary
import eu.darken.capod.common.bluetooth.redactedForLogs
import eu.darken.capod.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.pods.core.apple.ble.devices.ApplePods
import eu.darken.capod.pods.core.apple.ble.devices.ApplePodsFactory
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.ble.devices.misc.UnknownAppleSnapshotBle
import eu.darken.capod.pods.core.apple.ble.history.PodHistoryRepo
import eu.darken.capod.pods.core.apple.ble.protocol.ContinuityProtocol
import eu.darken.capod.pods.core.apple.ble.protocol.ProximityMessage
import eu.darken.capod.pods.core.apple.ble.protocol.ProximityPairing
import eu.darken.capod.pods.core.apple.ble.protocol.ProximityPayload
import eu.darken.capod.pods.core.apple.ble.protocol.RPAChecker
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import eu.darken.capod.profiles.core.currentProfiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppleFactory @Inject constructor(
    private val continuityProtocolDecoder: ContinuityProtocol.Decoder,
    private val proximityPairingDecoder: ProximityPairing.Decoder,
    private val proximityMessageDecrypter: ProximityMessage.Decrypter,
    private val podFactories: @JvmSuppressWildcards Set<ApplePodsFactory>,
    private val unknownAppleFactory: UnknownAppleSnapshotBle.Factory,
    private val rpaChecker: RPAChecker,
    private val profilesRepo: DeviceProfilesRepo,
    private val podHistoryRepo: PodHistoryRepo,
) {

    private val lock = Mutex()

    private fun getMessage(scanResult: BleScanResult): ProximityMessage? {
        val messages = try {
            continuityProtocolDecoder.decode(scanResult)
        } catch (e: Exception) {
            log(TAG, VERBOSE) { "Not a continuity payload: ${scanResult.logSummary()} (${e.javaClass.simpleName})" }
            return null
        }
        if (messages.isEmpty()) {
            log(TAG, VERBOSE) { "No continuity messages in ${scanResult.logSummary()}" }
            return null
        }

        if (messages.size > 1) {
            log(TAG, DEBUG) {
                "Decoded ${messages.size} continuity messages, picking first for ${scanResult.logSummary()}"
            }
        }

        val proximityMessage = proximityPairingDecoder.decode(messages.first())
        if (proximityMessage == null) {
            log(TAG, VERBOSE) { "Not a proximity pairing message for ${scanResult.logSummary()}" }
            return null
        }

        return proximityMessage
    }

    suspend fun create(scanResult: BleScanResult): BlePodSnapshot? = lock.withLock {
        val proximityMessage = getMessage(scanResult) ?: return@withLock null
        val factory = podFactories.firstOrNull { it.isResponsible(proximityMessage) } ?: unknownAppleFactory

        val profiles = profilesRepo.currentProfiles().filterIsInstance<AppleDeviceProfile>()
        val irkMatch = profiles.firstNotNullOfOrNull { candidate ->
            val identityKey = candidate.identityKey ?: return@firstNotNullOfOrNull null
            rpaChecker.resolve(scanResult.address, identityKey)?.let { candidate to it }
        }
        var profile = irkMatch?.first
        val irkOrder = irkMatch?.second

        val isIrkMatch = profile != null
        if (isIrkMatch) {
            log(TAG, VERBOSE) { "IRK match for ${scanResult.logSummary()} -> ${profile?.logSummary()}" }
        }

        var payload = ProximityPayload(
            public = ProximityPayload.Public(
                proximityMessage.data.take(9).toUByteArray()
            ),
            private = null,
        )

        if (profile != null && profile.encryptionKey != null) {
            payload = payload.copy(
                private = run {
                    if (proximityMessage.data.size != ProximityPairing.PAIRING_MESSAGE_LENGTH) return@run null
                    val encKey = profile.encryptionKey
                    val encrypted = proximityMessage.data.takeLast(16).toUByteArray().toByteArray()
                    proximityMessageDecrypter.decrypt(encrypted, encKey)?.let {
                        ProximityPayload.Private(data = it)
                    }
                },
            )
        }

        var matchReason = if (isIrkMatch) MatchReason.IRK_MATCH else MatchReason.NO_IRK_MATCH

        if (profile == null) {
            val tempDevice = factory.create(
                scanResult = scanResult,
                payload = payload,
                meta = ApplePods.AppleMeta(),
            )
            // Legacy weak match for profiles without an identity key. Identity is NOT verified
            // here, so it must stay unambiguous: with several same-model keyless profiles the pod
            // is left unmatched ("not linked to a profile") instead of being handed to whichever
            // one happens to sort first.
            val keylessCandidates = profiles
                .filter { it.identityKey == null }
                .filter { it.model == PodModel.UNKNOWN || it.model.isInterchangeableWith(tempDevice.model) }
                .filter { it.minimumSignalQuality <= tempDevice.signalQuality }

            profile = when {
                keylessCandidates.size == 1 -> {
                    matchReason = MatchReason.WEAK_MODEL_ONLY
                    keylessCandidates.first()
                }

                keylessCandidates.size > 1 -> {
                    matchReason = MatchReason.AMBIGUOUS_SAME_MODEL
                    log(TAG, WARN) {
                        "${keylessCandidates.size} keyless profiles share model ${tempDevice.model}; " +
                            "leaving ${scanResult.logSummary()} unmatched instead of guessing an owner."
                    }
                    null
                }

                else -> {
                    val legacyCandidate = profiles
                        .filter { it.identityKey != null && (it.model == PodModel.UNKNOWN || it.model.isInterchangeableWith(tempDevice.model)) }
                        .firstOrNull { it.minimumSignalQuality <= tempDevice.signalQuality }
                    if (legacyCandidate != null) {
                        matchReason = MatchReason.IRK_KEY_UNRESOLVED
                        log(TAG, WARN) {
                            "Keyed profile ${legacyCandidate.id} would match via old fallback, " +
                                "its key resolved neither address order"
                        }
                    } else if (profiles.isEmpty()) {
                        matchReason = MatchReason.NO_PROFILES
                    } else if (profiles.none { it.identityKey != null }) {
                        matchReason = MatchReason.MODEL_MISMATCH
                    } else {
                        matchReason = MatchReason.MODEL_OR_SIGNAL_MISMATCH
                    }
                    null
                }
            }
        }

        // One structured line per created pod: enough to diagnose identity problems from a bug
        // report without dumping keys, raw packets or rotating addresses.
        log(TAG) {
            "Identity reason=$matchReason model=${"${factory.javaClass.simpleName}"} profiles=${profiles.size} " +
                "matchedProfile=${profile?.id} irkMatch=$isIrkMatch"
        }

        val device = factory.create(
            scanResult = scanResult,
            payload = payload,
            meta = ApplePods.AppleMeta(
                isIRKMatch = isIrkMatch,
                profile = profile,
            ),
        )
        podHistoryRepo.recordPublished(device)

        log(TAG, DEBUG) {
            val rawHex = scanResult.manufacturerSpecificData.entries.joinToString("; ") { (id, bytes) ->
                "$id:${bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}"
            }
            val publicHex = payload.public.data.joinToString(" ") { "%02X".format(it.toInt()) }
            val privateHex = payload.private?.data?.joinToString(" ") { "%02X".format(it.toInt()) } ?: "-"
            "Apple decoded: model=${device.model}, addr=${scanResult.address.redactedForLogs()}, " +
                "irkMatch=$isIrkMatch, irkOrder=${irkOrder?.name ?: "-"}, " +
                "raw=[$rawHex], public=[$publicHex], private=[$privateHex]"
        }

        device
    }

    /**
     * Why a scanned pod was, or was not, attached to a configured profile. Diagnostics only: it is
     * logged once per created pod so a bug report can tell "no key", "key did not resolve" and
     * "model-only fallback" apart without exposing keys or raw packets.
     */
    internal enum class MatchReason {
        IRK_MATCH,
        NO_IRK_MATCH,
        WEAK_MODEL_ONLY,
        AMBIGUOUS_SAME_MODEL,
        IRK_KEY_UNRESOLVED,
        NO_PROFILES,
        MODEL_MISMATCH,
        MODEL_OR_SIGNAL_MISMATCH,
    }

    companion object {
        private val TAG = logTag("Pod", "Apple", "Factory")
    }
}
