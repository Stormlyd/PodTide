package eu.darken.capod.pods.core.apple.ble.devices.airpods

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.util.SparseArray
import eu.darken.capod.common.bluetooth.BleScanResult
import eu.darken.capod.common.fromHex
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.ble.devices.BaseBlePodsTest
import eu.darken.capod.pods.core.apple.ble.devices.DualApplePods
import eu.darken.capod.pods.core.apple.ble.devices.HasAppleColor
import eu.darken.capod.pods.core.apple.ble.protocol.ContinuityProtocol
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.TestTimeSource
import java.time.Instant

class AirPodsPro3Test : BaseBlePodsTest() {

    @Test
    fun `AirPods Pro 3 native delayed batch retains measured age in first and history snapshots`() = runTest {
        val now = Instant.now()
        val clock = TestTimeSource(wallNow = now, elapsedRealtimeNanosValue = 100_000_000_000L)
        val bytes = "07 19 01 27 20 53 AA 98 32 00 05 49 0A B8 BF 8E 29 D8 70 12 0D A7 0C CE 77 56 00".fromHex()
        val data = mockk<SparseArray<ByteArray>> {
            every { size() } returns 1
            every { keyAt(0) } returns ContinuityProtocol.APPLE_COMPANY_IDENTIFIER
            every { valueAt(0) } returns bytes
        }
        fun scan(timestamp: Long): BleScanResult {
            val native = mockk<ScanResult> {
                every { device } returns mockk<BluetoothDevice> { every { address } returns "77:49:4C:D8:25:0C" }
                every { rssi } returns -60
                every { timestampNanos } returns timestamp
                every { scanRecord } returns mockk<ScanRecord> { every { manufacturerSpecificData } returns data }
            }
            return BleScanResult.fromScanResult(native, clock)
        }
        val first = factory.create(scan(90_000_000_000L)) as AirPodsPro3
        first.seenFirstAt shouldBe now.minusSeconds(10)
        first.seenLastAt shouldBe now.minusSeconds(10)
        val newer = factory.create(scan(99_000_000_000L)) as AirPodsPro3
        newer.identifier shouldBe first.identifier
        newer.seenFirstAt shouldBe now.minusSeconds(10)
        newer.seenLastAt shouldBe now.minusSeconds(1)
    }

    @Test
    fun `rejected Pro3 frame returns enriched recent case and signal aggregate`() = runTest {
        val now = Instant.now()
        val clock = TestTimeSource(wallNow = now, elapsedRealtimeNanosValue = 100_000_000_000L)
        val inCase = "07 19 01 27 20 53 AA 98 32 00 05 49 0A B8 BF 8E 29 D8 70 12 0D A7 0C CE 77 56 00"
        val outOfCase = inCase.replace(" 53 AA 98 32", " 0B AA 9F 32")
        fun scan(hex: String, timestamp: Long, strength: Int): BleScanResult {
            val data = mockk<SparseArray<ByteArray>> {
                every { size() } returns 1
                every { keyAt(0) } returns ContinuityProtocol.APPLE_COMPANY_IDENTIFIER
                every { valueAt(0) } returns hex.fromHex()
            }
            val native = mockk<ScanResult> {
                every { device } returns mockk<BluetoothDevice> { every { address } returns "77:49:4C:D8:25:0C" }
                every { rssi } returns strength
                every { timestampNanos } returns timestamp
                every { scanRecord } returns mockk<ScanRecord> { every { manufacturerSpecificData } returns data }
            }
            return BleScanResult.fromScanResult(native, clock)
        }
        factory.create(scan(inCase, 97_500_000_000L, -40)) as AirPodsPro3
        factory.create(scan(inCase, 97_600_000_000L, -40)) as AirPodsPro3
        val latest = factory.create(scan(outOfCase, 99_000_000_000L, -80)) as AirPodsPro3
        latest.caseLidState shouldBe DualApplePods.LidState.OPEN
        latest.batteryCasePercent shouldBe 0.8f
        latest.rssi.let { it > -80 } shouldBe true
        val rejected = factory.create(scan(inCase, 98_500_000_000L, -90)) as AirPodsPro3
        rejected.seenFirstAt shouldBe latest.seenFirstAt
        rejected.seenCounter shouldBe latest.seenCounter
        rejected.caseLidState shouldBe latest.caseLidState
        rejected.batteryCasePercent shouldBe latest.batteryCasePercent
        rejected.rssi shouldBe latest.rssi
        rejected.scanResult.generatedAtNanos shouldBe latest.scanResult.generatedAtNanos
    }

    /**
     * Test case for AirPods Pro 3 - placeholder with correct device code
     * Following the pattern of other AirPods models (0x2720)
     */
    @Test
    fun `AirPods Pro 3 - placeholder test`() = runTest {
        // This test uses a placeholder hex string with the correct device code 0x2720
        // The actual proximity pairing data will need to be captured from real AirPods Pro 3
        create<AirPodsPro3>("07 19 01 27 20 0B 99 8F 11 00 04 BD A7 3B FF 2D 8A 3C AF 9B 1A 7C 74 B7 A9 D1 C3") {

            pubPrefix shouldBe 0x01.toUByte()
            pubDeviceModel shouldBe 0x2720.toUShort()
            pubStatus shouldBe 0x0B.toUByte()
            pubPodsBattery shouldBe 0x99.toUByte()
            pubFlags shouldBe 0x8.toUShort()
            pubCaseBattery shouldBe 0xF.toUShort()
            pubCaseLidState shouldBe 0x11.toUByte()
            pubDeviceColor shouldBe 0x0.toUByte()
            pubSuffix shouldBe 0x04.toUByte()

            isLeftPodMicrophone shouldBe false
            isRightPodMicrophone shouldBe true

            isLeftPodInEar shouldBe true
            isRightPodInEar shouldBe true

            batteryLeftPodPercent shouldBe 0.9f
            batteryRightPodPercent shouldBe 0.9f

            isCaseCharging shouldBe false
            isRightPodCharging shouldBe false
            isLeftPodCharging shouldBe false
            batteryCasePercent shouldBe null

            podStyle.identifier shouldBe HasAppleColor.DeviceColor.WHITE.name

            model shouldBe PodModel.AIRPODS_PRO3
        }
    }

    @Test
    fun `AirPods Pro 3 - placeholder test - in case`() = runTest {
        // This test uses a placeholder hex string with the correct device code 0x2720
        // The actual proximity pairing data will need to be captured from real AirPods Pro 3
        create<AirPodsPro3>("07 19 01 27 20 53 AA 98 32 00 05 49 0A B8 BF 8E 29 D8 70 12 0D A7 0C CE 77 56 00") {

            isLeftPodMicrophone shouldBe true
            isRightPodMicrophone shouldBe false

            isLeftPodInEar shouldBe true
            isRightPodInEar shouldBe false

            batteryLeftPodPercent shouldBe 1f
            batteryRightPodPercent shouldBe 1f

            isCaseCharging shouldBe false
            isRightPodCharging shouldBe true
            isLeftPodCharging shouldBe false
            batteryCasePercent shouldBe 0.8f

            podStyle.identifier shouldBe HasAppleColor.DeviceColor.WHITE.name

            model shouldBe PodModel.AIRPODS_PRO3
        }
    }

    @Test
    fun `AirPods Pro 3`() = runTest {
        create<AirPodsPro3>("07 19 01 27 20 0B 99 8F 11 00 05 43 A8 85 17 07 FF 41 62 2C FE 5E 20 08 1A 52 40") {
            isLeftPodInEar shouldBe true
            isRightPodInEar shouldBe true

            isCaseCharging shouldBe false
            isRightPodCharging shouldBe false
            isLeftPodCharging shouldBe false
            batteryCasePercent shouldBe null

            podStyle.identifier shouldBe HasAppleColor.DeviceColor.WHITE.name

            model shouldBe PodModel.AIRPODS_PRO3
        }
    }
}
