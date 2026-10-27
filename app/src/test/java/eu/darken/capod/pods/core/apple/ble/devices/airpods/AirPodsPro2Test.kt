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

class AirPodsPro2Test : BaseBlePodsTest() {

    @Test
    fun `factory timestamps first and recovered history from radio observation and ignores older lid batch`() = runTest {
        val now = Instant.now()
        val clock = TestTimeSource(wallNow = now, elapsedRealtimeNanosValue = 100_000_000_000L)
        val open = "07 19 01 14 20 55 88 F9 51 00 04 20 50 03 CA D5 C9 AC 0F FA 84 78 94 5A 4D DF F5"
        val closed = open.replace(" F9 51 00", " F9 59 00")

        fun scan(hex: String, timestampNanos: Long): BleScanResult {
            val data = mockk<SparseArray<ByteArray>> {
                every { size() } returns 1
                every { keyAt(0) } returns ContinuityProtocol.APPLE_COMPANY_IDENTIFIER
                every { valueAt(0) } returns hex.fromHex()
            }
            val native = mockk<ScanResult> {
                every { device } returns mockk<BluetoothDevice> { every { address } returns "77:49:4C:D8:25:0C" }
                every { rssi } returns -66
                every { this@mockk.timestampNanos } returns timestampNanos
                every { scanRecord } returns mockk<ScanRecord> { every { manufacturerSpecificData } returns data }
            }
            return BleScanResult.fromScanResult(native, clock)
        }

        val first = factory.create(scan(open, 80_000_000_000L)) as AirPodsPro2
        first.seenFirstAt shouldBe now.minusSeconds(20)
        first.seenLastAt shouldBe now.minusSeconds(20)

        val newer = factory.create(scan(closed, 99_000_000_000L)) as AirPodsPro2
        newer.identifier shouldBe first.identifier
        newer.seenFirstAt shouldBe now.minusSeconds(20)
        newer.seenLastAt shouldBe now.minusSeconds(1)
        newer.caseLidState shouldBe DualApplePods.LidState.CLOSED

        val delayed = factory.create(scan(open, 90_000_000_000L)) as AirPodsPro2
        delayed.identifier shouldBe first.identifier
        delayed.seenLastAt shouldBe now.minusSeconds(1)
        delayed.caseLidState shouldBe DualApplePods.LidState.CLOSED
    }

    @Test
    fun `rejected old and invalid frames retain full Pro2 aggregate without extending raw lid recovery`() = runTest {
        val now = Instant.now()
        val clock = TestTimeSource(wallNow = now, elapsedRealtimeNanosValue = 100_000_000_000L)
        val inCaseOpen = "07 19 01 14 20 55 88 F9 51 00 04 20 50 03 CA D5 C9 AC 0F FA 84 78 94 5A 4D DF F5"
        val inCaseClosed = inCaseOpen.replace(" F9 51 00", " F9 59 00")
        val outOfCase = inCaseOpen.replace(" 55 88 F9 51", " 0B 88 FF 51")
        fun scan(hex: String, nativeAt: Long, rssi: Int): BleScanResult {
            val data = mockk<SparseArray<ByteArray>> {
                every { size() } returns 1
                every { keyAt(0) } returns ContinuityProtocol.APPLE_COMPANY_IDENTIFIER
                every { valueAt(0) } returns hex.fromHex()
            }
            val native = mockk<ScanResult> {
                every { device } returns mockk<BluetoothDevice> { every { address } returns "77:49:4C:D8:25:0C" }
                every { this@mockk.rssi } returns rssi
                every { timestampNanos } returns nativeAt
                every { scanRecord } returns mockk<ScanRecord> { every { manufacturerSpecificData } returns data }
            }
            return BleScanResult.fromScanResult(native, clock)
        }
        val first = factory.create(scan(inCaseOpen, 97_500_000_000L, -40)) as AirPodsPro2
        factory.create(scan(inCaseOpen, 97_600_000_000L, -40)) as AirPodsPro2
        val latest = factory.create(scan(outOfCase, 99_000_000_000L, -80)) as AirPodsPro2
        latest.seenFirstAt shouldBe now.minusMillis(2_500)
        latest.seenCounter.let { it > 1 } shouldBe true
        latest.caseLidState shouldBe DualApplePods.LidState.OPEN
        latest.batteryCasePercent shouldBe 0.9f
        latest.rssi.let { it > -80 } shouldBe true

        for (rejected in listOf(
            scan(inCaseClosed, 98_500_000_000L, -90),
            scan(inCaseClosed, 0L, -90),
        )) {
            val kept = factory.create(rejected) as AirPodsPro2
            kept.identifier shouldBe first.identifier
            kept.seenFirstAt shouldBe latest.seenFirstAt
            kept.seenLastAt shouldBe latest.seenLastAt
            kept.seenCounter shouldBe latest.seenCounter
            kept.caseLidState shouldBe latest.caseLidState
            kept.batteryCasePercent shouldBe latest.batteryCasePercent
            kept.rssi shouldBe latest.rssi
            kept.reliability shouldBe latest.reliability
            kept.scanResult.generatedAtNanos shouldBe latest.scanResult.generatedAtNanos
        }

        val later = factory.create(scan(outOfCase, 100_000_000_000L, -80)) as AirPodsPro2
        later.caseLidState shouldBe DualApplePods.LidState.NOT_IN_CASE
    }

    /**
     * https://github.com/d4rken-org/capod/issues/31#issuecomment-1256791084
     */
    @Test
    fun `test AirPods Pro 2 - unknown setup from #31`() = runTest {
        create<AirPodsPro2>("07 19 01 14 20 55 88 F9 51 00 04 20 50 03 CA D5 C9 AC 0F FA 84 78 94 5A 4D DF F5") {

            pubPrefix shouldBe 0x01.toUByte()
            pubDeviceModel shouldBe 0x1420.toUShort()
            pubStatus shouldBe 0x55.toUByte()
            pubPodsBattery shouldBe 0x88.toUByte()
            pubFlags shouldBe 0xF.toUShort()
            pubCaseBattery shouldBe 0x9.toUShort()
            pubCaseLidState shouldBe 0x51.toUByte()
            pubDeviceColor shouldBe 0x0.toUByte()
            pubSuffix shouldBe 0x04.toUByte()

            isLeftPodMicrophone shouldBe true
            isRightPodMicrophone shouldBe false

            isLeftPodInEar shouldBe false
            isRightPodInEar shouldBe false

            batteryLeftPodPercent shouldBe 0.8f
            batteryRightPodPercent shouldBe 0.8f

            isCaseCharging shouldBe true
            isRightPodCharging shouldBe true
            isLeftPodCharging shouldBe true
            batteryCasePercent shouldBe 0.9f

            podStyle.identifier shouldBe HasAppleColor.DeviceColor.WHITE.name

            model shouldBe PodModel.AIRPODS_PRO2
        }
    }

    /**
     * https://old.reddit.com/message/messages/1hst12h
     */
    @Test
    fun `test AirPods Pro 2 - unknown setup from reddit user`() = runTest {
        create<AirPodsPro2>("07 19 01 14 20 2B 9A 8F 01 00 04 0F 26 1A C4 2B FA 2F B9 B6 08 CD 60 CB DF 75 AB") {

            pubPrefix shouldBe 0x01.toUByte()
            pubDeviceModel shouldBe 0x1420.toUShort()
            pubStatus shouldBe 0x2B.toUByte()
            pubPodsBattery shouldBe 0x9A.toUByte()
            pubFlags shouldBe 0x8.toUShort()
            pubCaseBattery shouldBe 0xF.toUShort()
            pubCaseLidState shouldBe 0x01.toUByte()
            pubDeviceColor shouldBe 0x0.toUByte()
            pubSuffix shouldBe 0x04.toUByte()

            isLeftPodMicrophone shouldBe true
            isRightPodMicrophone shouldBe false

            isLeftPodInEar shouldBe true
            isRightPodInEar shouldBe true

            batteryLeftPodPercent shouldBe 1.0f
            batteryRightPodPercent shouldBe 0.9f

            isCaseCharging shouldBe false
            isRightPodCharging shouldBe false
            isLeftPodCharging shouldBe false
            batteryCasePercent shouldBe null

            podStyle.identifier shouldBe HasAppleColor.DeviceColor.WHITE.name
        }
    }
}
