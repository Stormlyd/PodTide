package eu.darken.capod.common.bluetooth

import android.bluetooth.le.ScanResult
import android.os.Parcelable
import androidx.core.util.forEach
import eu.darken.capod.common.SystemTimeSource
import eu.darken.capod.common.TimeSource
import eu.darken.capod.common.serialization.InstantEpochMillisSerializer
import eu.darken.capod.common.serialization.MapIntByteArrayBase64Serializer
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

@Parcelize
@Serializable
data class BleScanResult(
    @SerialName("receivedAt") @Serializable(with = InstantEpochMillisSerializer::class) val receivedAt: Instant,
    @SerialName("address") val address: String,
    @SerialName("rssi") val rssi: Int,
    @SerialName("generatedAtNanos") val generatedAtNanos: Long,
    @SerialName("manufacturerSpecificData") @Serializable(with = MapIntByteArrayBase64Serializer::class) val manufacturerSpecificData: Map<Int, ByteArray>,
    /** Radio time translated to wall time only for display/history; null means unknown measurement age. */
    @IgnoredOnParcel @Transient val observedAt: Instant? = null,
    /** Native time since boot. Runtime authority is deliberately lost at cache/parcel boundaries. */
    @IgnoredOnParcel @Transient val observedElapsedRealtimeNanos: Long? = null,
) : Parcelable {

    fun observationAgeMillis(timeSource: TimeSource): Long? {
        val measured = observedElapsedRealtimeNanos ?: return null
        val now = timeSource.elapsedRealtimeNanos()
        if (measured <= 0L || now < measured) return null
        val ageNanos = now - measured
        val wholeMillis = ageNanos / 1_000_000L
        return if (ageNanos % 1_000_000L == 0L) wholeMillis else wholeMillis + 1L
    }

    fun getManufacturerSpecificData(id: Int): ByteArray? = manufacturerSpecificData[id]

    override fun toString(): String {
        val sb = StringBuilder()
        manufacturerSpecificData.forEach { (key, value) ->
            sb.append("$key: ${value.joinToString(separator = " ") { String.format("%02X", it) }}")
        }
        return "BleScanResult($rssi, $address, $generatedAtNanos, $sb"
    }

    companion object {
        fun fromScanResult(
            scanResult: ScanResult,
            timeSource: TimeSource = SystemTimeSource,
        ): BleScanResult {
            val callbackAt = timeSource.now()
            val monotonicNow = timeSource.elapsedRealtimeNanos()
            val generated = scanResult.timestampNanos
            val observation = if (generated > 0L && generated <= monotonicNow) {
                runCatching { callbackAt.minusNanos(monotonicNow - generated) }.getOrNull()
            } else null
            return BleScanResult(
                receivedAt = callbackAt,
                address = scanResult.device.address,
                rssi = scanResult.rssi,
                generatedAtNanos = generated,
                manufacturerSpecificData = mutableMapOf<Int, ByteArray>().apply {
                    scanResult.scanRecord?.manufacturerSpecificData?.forEach { key, value ->
                        this[key] = value
                    }
                },
                observedAt = observation,
                observedElapsedRealtimeNanos = if (observation != null) generated else null,
            )
        }
    }
}
