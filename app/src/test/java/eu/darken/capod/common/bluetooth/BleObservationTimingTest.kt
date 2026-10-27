package eu.darken.capod.common.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.os.Parcel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import testhelpers.TestTimeSource
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class BleObservationTimingTest : BaseTest() {
    private val wall = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = TestTimeSource(wallNow = wall, elapsedRealtimeNanosValue = 100_000_000_000L)

    private fun native(timestamp: Long): ScanResult = mockk {
        every { device } returns mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        every { rssi } returns -55
        every { timestampNanos } returns timestamp
        every { scanRecord } returns null
    }

    @Test fun `platform conversion preserves twenty second radio age and current callback time`() {
        val result = BleScanResult.fromScanResult(native(80_000_000_000L), clock)
        result.receivedAt shouldBe wall
        result.observedAt shouldBe wall.minusSeconds(20)
        result.observationAgeMillis(clock) shouldBe 20_000L
        result.generatedAtNanos shouldBe 80_000_000_000L
        result.copy(rssi = -50).observationAgeMillis(clock) shouldBe 20_000L
    }

    @Test fun `invalid or future native timestamp never grants measurement authority`() {
        listOf(0L, -1L, 100_000_000_001L).forEach { invalid ->
            val result = BleScanResult.fromScanResult(native(invalid), clock)
            result.observedAt shouldBe null
            result.observationAgeMillis(clock) shouldBe null
        }
    }

    @Test fun `wall clock change cannot rejuvenate radio measurement`() {
        val result = BleScanResult.fromScanResult(native(80_000_000_000L), clock)
        clock.wallNow = wall.plusSeconds(3600)
        result.observationAgeMillis(clock) shouldBe 20_000L
        clock.wallNow = wall.minusSeconds(3600)
        result.observationAgeMillis(clock) shouldBe 20_000L
        clock.elapsedRealtimeNanosValue = 101_000_000_000L
        result.observationAgeMillis(clock) shouldBe 21_000L
    }

    @Test fun `sub millisecond age past five second boundary is not rounded down`() {
        val result = BleScanResult.fromScanResult(native(94_999_999_999L), clock)
        result.observationAgeMillis(clock) shouldBe 5_001L
    }

    @Test fun `legacy JSON and parcel stay readable but have no live authority`() {
        val legacyJson = """{"receivedAt":1767225600000,"address":"AA:BB:CC:DD:EE:FF","rssi":-55,"generatedAtNanos":80000000000,"manufacturerSpecificData":{}}"""
        val decoded = Json.decodeFromString<BleScanResult>(legacyJson)
        decoded.receivedAt shouldBe wall
        decoded.observationAgeMillis(clock) shouldBe null

        val live = BleScanResult.fromScanResult(native(80_000_000_000L), clock)
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(live, 0)
            parcel.setDataPosition(0)
            val restored = parcel.readParcelable(BleScanResult::class.java.classLoader, BleScanResult::class.java)!!
            restored.receivedAt shouldBe live.receivedAt
            restored.generatedAtNanos shouldBe live.generatedAtNanos
            restored.observationAgeMillis(clock) shouldBe null
        } finally {
            parcel.recycle()
        }
    }
}
