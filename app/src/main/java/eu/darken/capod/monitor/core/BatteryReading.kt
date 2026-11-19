package eu.darken.capod.monitor.core

import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.pods.core.apple.ble.BATTERY_RESOLUTION_DECILE
import eu.darken.capod.pods.core.apple.ble.BATTERY_RESOLUTION_PERCENT
import eu.darken.capod.pods.core.apple.ble.DualBlePodSnapshot
import eu.darken.capod.pods.core.apple.ble.SingleBlePodSnapshot
import eu.darken.capod.pods.core.apple.ble.devices.HasCase
import java.time.Duration
import java.time.Instant

/**
 * A battery percentage together with the step size of the source it came from. A reading of 20%
 * from a decile source means "somewhere in [20%, 30%)", which is the difference between a claim we
 * can make and one we can't.
 */
data class BatteryReading(
    val percent: Float,
    val resolution: Float,
    val source: BatterySource = BatterySource.AAP,
    /** When this source actually reported this slot; null when unknown or legacy. */
    val sampledAt: Instant? = null,
    /** True when the value comes from persisted cache rather than a live source. */
    val isHistorical: Boolean = false,
)

enum class BatterySource { AAP, BLE, CACHE }

/**
 * A live BLE advertisement may overtake an AAP slot only when it is genuinely newer, not merely
 * because a later unrelated AAP message refreshed [AapPodState.lastMessageAt].
 */
private val BLE_OVERTAKE_GRACE: Duration = Duration.ofSeconds(30)

private fun resolveReading(
    aapPercent: Float?,
    aapAt: Instant?,
    blePercent: Float?,
    bleAt: Instant?,
    bleResolution: Float,
    cachedPercent: Float?,
    cachedAt: Instant?,
): BatteryReading? {
    if (aapPercent != null) {
        val bleIsNewer = blePercent != null && aapAt != null && bleAt != null &&
                bleAt.isAfter(aapAt.plus(BLE_OVERTAKE_GRACE))
        if (bleIsNewer) {
            return BatteryReading(
                percent = blePercent!!,
                resolution = bleResolution,
                source = BatterySource.BLE,
                sampledAt = bleAt,
            )
        }
        return BatteryReading(
            percent = aapPercent,
            resolution = BATTERY_RESOLUTION_PERCENT,
            source = BatterySource.AAP,
            sampledAt = aapAt,
        )
    }
    if (blePercent != null) {
        return BatteryReading(
            percent = blePercent,
            resolution = bleResolution,
            source = BatterySource.BLE,
            sampledAt = bleAt,
        )
    }
    if (cachedPercent != null) {
        return BatteryReading(
            percent = cachedPercent,
            resolution = BATTERY_RESOLUTION_DECILE,
            source = BatterySource.CACHE,
            sampledAt = cachedAt,
            isHistorical = true,
        )
    }
    return null
}

private fun AapPodState.batteryAt(type: AapPodState.BatteryType): Instant? = batteries[type]?.reportedAt

val PodDevice.batteryLeftReading: BatteryReading?
    get() {
        val dual = ble as? DualBlePodSnapshot
        return resolveReading(
            aapPercent = aap?.batteryLeft,
            aapAt = aap?.batteryAt(AapPodState.BatteryType.LEFT),
            blePercent = dual?.batteryLeftPodPercent,
            bleAt = ble?.seenLastAt,
            bleResolution = BATTERY_RESOLUTION_DECILE,
            cachedPercent = cached?.left?.percent,
            cachedAt = cached?.left?.updatedAt,
        )
    }

val PodDevice.batteryRightReading: BatteryReading?
    get() {
        val dual = ble as? DualBlePodSnapshot
        return resolveReading(
            aapPercent = aap?.batteryRight,
            aapAt = aap?.batteryAt(AapPodState.BatteryType.RIGHT),
            blePercent = dual?.batteryRightPodPercent,
            bleAt = ble?.seenLastAt,
            bleResolution = BATTERY_RESOLUTION_DECILE,
            cachedPercent = cached?.right?.percent,
            cachedAt = cached?.right?.updatedAt,
        )
    }

/**
 * [PodDevice.batteryCase] resolved through the same precedence, but keeping the resolution of
 * whichever source won. Cache entries store no resolution, so they report the coarsest one.
 */
val PodDevice.batteryCaseReading: BatteryReading?
    get() {
        val caseSnapshot = ble as? HasCase
        return resolveReading(
            aapPercent = aap?.batteryCase,
            aapAt = aap?.batteryAt(AapPodState.BatteryType.CASE),
            blePercent = caseSnapshot?.batteryCasePercent,
            bleAt = ble?.seenLastAt,
            bleResolution = caseSnapshot?.batteryCaseResolution ?: BATTERY_RESOLUTION_DECILE,
            cachedPercent = cached?.case?.percent,
            cachedAt = cached?.case?.updatedAt,
        )
    }

val PodDevice.batteryHeadsetReading: BatteryReading?
    get() {
        val single = ble as? SingleBlePodSnapshot
        return resolveReading(
            aapPercent = aap?.batteryHeadset,
            aapAt = aap?.batteryAt(AapPodState.BatteryType.SINGLE),
            blePercent = single?.batteryHeadsetPercent,
            bleAt = ble?.seenLastAt,
            bleResolution = BATTERY_RESOLUTION_DECILE,
            cachedPercent = cached?.headset?.percent,
            cachedAt = cached?.headset?.updatedAt,
        )
    }

internal val PodDevice.allBatteryReadings: List<BatteryReading>
    get() = listOfNotNull(
        batteryLeftReading,
        batteryRightReading,
        batteryCaseReading,
        batteryHeadsetReading,
    )