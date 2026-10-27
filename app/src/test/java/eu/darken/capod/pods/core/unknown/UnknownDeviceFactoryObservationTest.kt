package eu.darken.capod.pods.core.unknown

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import eu.darken.capod.common.bluetooth.BleScanResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.TestTimeSource
import java.time.Instant

class UnknownDeviceFactoryObservationTest : BaseTest() {
    @Test
    fun `unknown factory uses measured age on first and recovered snapshots without old rollback`() = runTest {
        val now = Instant.now()
        val clock = TestTimeSource(wallNow = now, elapsedRealtimeNanosValue = 100_000_000_000L)
        fun scan(timestamp: Long, strength: Int): BleScanResult {
            val native = mockk<ScanResult> {
                every { device } returns mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
                every { rssi } returns strength
                every { timestampNanos } returns timestamp
                every { scanRecord } returns null
            }
            return BleScanResult.fromScanResult(native, clock)
        }
        val factory = UnknownDeviceFactory()
        val first = factory.create(scan(97_500_000_000L, -40))
        first.seenFirstAt shouldBe now.minusMillis(2_500)
        first.seenLastAt shouldBe now.minusMillis(2_500)
        factory.create(scan(97_600_000_000L, -40))
        val newer = factory.create(scan(99_000_000_000L, -80))
        newer.identifier shouldBe first.identifier
        newer.seenFirstAt shouldBe now.minusMillis(2_500)
        newer.seenLastAt shouldBe now.minusSeconds(1)
        newer.rssi.let { it > -80 } shouldBe true
        for (rejected in listOf(scan(98_500_000_000L, -90), scan(0L, -90))) {
            val kept = factory.create(rejected)
            kept.identifier shouldBe first.identifier
            kept.seenFirstAt shouldBe newer.seenFirstAt
            kept.seenLastAt shouldBe newer.seenLastAt
            kept.seenCounter shouldBe newer.seenCounter
            kept.rssi shouldBe newer.rssi
            kept.reliability shouldBe newer.reliability
            kept.scanResult.generatedAtNanos shouldBe newer.scanResult.generatedAtNanos
        }
        val next = factory.create(scan(100_000_000_000L, -80))
        next.seenCounter shouldBe newer.seenCounter + 1
    }
}
