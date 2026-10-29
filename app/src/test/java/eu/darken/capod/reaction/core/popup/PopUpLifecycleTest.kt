package eu.darken.capod.reaction.core.popup

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import eu.darken.capod.common.bluetooth.BleScanResult
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.bluetooth.BluetoothDevice2
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.ble.devices.DualApplePods
import eu.darken.capod.profiles.core.ReactionConfig
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import testhelpers.BaseTest
import testhelpers.TestTimeSource
import java.time.Duration

class PopUpLifecycleTest : BaseTest() {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `new observed audio connection shows popup independently of missing or stale BLE`(withStaleBle: Boolean) = runTest {
        val clock = TestTimeSource()
        val address = "AA:BB:CC:DD:EE:FF"
        val device: PodDevice = mockk {
            every { profileId } returns "owned-pods"
            every { this@mockk.address } returns address
            every { reactions } returns ReactionConfig(showPopUpOnConnection = true)
            every { rawDataHex } returns emptyList()
            every { caseLidState } returns DualApplePods.LidState.UNKNOWN
            every { ble } returns if (withStaleBle) mockk { every { seenLastAt } returns clock.now().minusSeconds(120) } else null
        }
        val devices = MutableStateFlow(emptyList<BluetoothDevice2>())
        val monitor = mockk<DeviceMonitor> { every { primaryDeviceByTier } returns flowOf(device) }
        val bluetooth = mockk<BluetoothManager2> { every { connectedDevices } returns devices }
        val shown = mutableListOf<PopUpReaction.Event.PopupShow>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PopUpReaction(monitor, bluetooth, clock).monitor()
                .filterIsInstance<PopUpReaction.Event.PopupShow>().collect { shown += it }
        }
        runCurrent()
        repeat(3) {
            devices.value = listOf(BluetoothDevice2(address, "Pods", clock.now()))
            runCurrent()
            devices.value = emptyList()
            runCurrent()
        }
        shown.size shouldBe 3
    }

    @Test
    fun `startup open case and twenty reopen events show independently without disconnect`() = runTest {
        val clock = TestTimeSource(elapsedRealtimeNanosValue = 100_000_000_000L)
        val deviceFlow = MutableStateFlow<PodDevice?>(null)
        val monitor = mockk<DeviceMonitor> { every { primaryDeviceByTier } returns deviceFlow }
        val bluetooth = mockk<BluetoothManager2> { every { connectedDevices } returns flowOf(emptyList()) }
        val reaction = PopUpReaction(monitor, bluetooth, clock)
        fun device(lid: DualApplePods.LidState): PodDevice = mockk {
            every { profileId } returns "owned-pods"
            every { reactions } returns ReactionConfig(showPopUpOnCaseOpen = true)
            every { rawDataHex } returns listOf(lid.name)
            every { caseLidState } returns lid
            every { ble } returns mockk {
                every { seenLastAt } returns clock.now()
                every { scanResult } returns nativeScan(clock, clock.elapsedRealtimeNanos())
            }
        }
        val shown = mutableListOf<PopUpReaction.Event.PopupShow>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reaction.monitor().filterIsInstance<PopUpReaction.Event.PopupShow>().collect { shown += it }
        }
        runCurrent()
        repeat(20) {
            deviceFlow.value = device(DualApplePods.LidState.OPEN)
            runCurrent()
            deviceFlow.value = device(DualApplePods.LidState.CLOSED)
            runCurrent()
        }
        shown.size shouldBe 20
    }

    @Test
    fun `queued old OPEN after resume is ignored and a measured fresh OPEN shows`() = runTest {
        val clock = TestTimeSource(elapsedRealtimeNanosValue = 100_000_000_000L)
        val deviceFlow = MutableStateFlow<PodDevice?>(null)
        val monitor = mockk<DeviceMonitor> { every { primaryDeviceByTier } returns deviceFlow }
        val bluetooth = mockk<BluetoothManager2> { every { connectedDevices } returns flowOf(emptyList()) }
        val shown = mutableListOf<PopUpReaction.Event.PopupShow>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PopUpReaction(monitor, bluetooth, clock).monitor()
                .filterIsInstance<PopUpReaction.Event.PopupShow>().collect { shown += it }
        }
        runCurrent()
        fun device(lid: DualApplePods.LidState, timestamp: Long): PodDevice = mockk {
            every { profileId } returns "owned-pods"
            every { reactions } returns ReactionConfig(showPopUpOnCaseOpen = true)
            every { rawDataHex } returns listOf(lid.name, timestamp.toString())
            every { caseLidState } returns lid
            every { ble } returns mockk {
                every { seenLastAt } returns clock.now()
                every { scanResult } returns nativeScan(clock, timestamp)
            }
        }
        deviceFlow.value = device(DualApplePods.LidState.OPEN, 80_000_000_000L)
        runCurrent()
        shown.size shouldBe 0
        deviceFlow.value = device(DualApplePods.LidState.CLOSED, 99_000_000_000L)
        runCurrent()
        deviceFlow.value = device(DualApplePods.LidState.OPEN, 100_000_000_000L)
        runCurrent()
        shown.size shouldBe 1
    }

    @Test
    fun `stale OPEN recovery does not redisplay an already shown open case`() = runTest {
        val clock = TestTimeSource(elapsedRealtimeNanosValue = 100_000_000_000L)
        val deviceFlow = MutableStateFlow<PodDevice?>(null)
        val monitor = mockk<DeviceMonitor> { every { primaryDeviceByTier } returns deviceFlow }
        val bluetooth = mockk<BluetoothManager2> { every { connectedDevices } returns flowOf(emptyList()) }
        val shown = mutableListOf<PopUpReaction.Event.PopupShow>()
        val hidden = mutableListOf<PopUpReaction.Event.PopupHide>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PopUpReaction(monitor, bluetooth, clock).monitor().collect {
                when (it) {
                    is PopUpReaction.Event.PopupShow -> shown += it
                    is PopUpReaction.Event.PopupHide -> hidden += it
                }
            }
        }
        runCurrent()
        fun open(nativeAt: Long): PodDevice = mockk {
            every { profileId } returns "owned-pods"
            every { reactions } returns ReactionConfig(showPopUpOnCaseOpen = true)
            every { rawDataHex } returns listOf("identical-OPEN-payload")
            every { caseLidState } returns DualApplePods.LidState.OPEN
            every { ble } returns mockk {
                every { seenLastAt } returns clock.now()
                every { scanResult } returns nativeScan(clock, nativeAt)
            }
        }
        deviceFlow.value = open(80_000_000_000L)
        runCurrent()
        shown.size shouldBe 0
        deviceFlow.value = open(100_000_000_000L)
        runCurrent()
        shown.size shouldBe 1
        deviceFlow.value = open(100_000_000_000L)
        runCurrent()
        shown.size shouldBe 1

        clock.advanceBy(Duration.ofSeconds(11))
        advanceTimeBy(2_001)
        runCurrent()
        hidden.size shouldBe 1
        deviceFlow.value = open(clock.elapsedRealtimeNanos())
        runCurrent()
        shown.size shouldBe 1
        clock.advanceBy(Duration.ofSeconds(1))
        deviceFlow.value = open(clock.elapsedRealtimeNanos())
        runCurrent()
        shown.size shouldBe 1
    }

    @Test
    fun `case OPEN stale ticker hides after native age exceeds four seconds`() = runTest {
        val clock = TestTimeSource(elapsedRealtimeNanosValue = 100_000_000_000L)
        val deviceFlow = MutableStateFlow<PodDevice?>(null)
        val monitor = mockk<DeviceMonitor> { every { primaryDeviceByTier } returns deviceFlow }
        val bluetooth = mockk<BluetoothManager2> { every { connectedDevices } returns flowOf(emptyList()) }
        val shown = mutableListOf<PopUpReaction.Event.PopupShow>()
        val hidden = mutableListOf<PopUpReaction.Event.PopupHide>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PopUpReaction(monitor, bluetooth, clock).monitor().collect {
                when (it) {
                    is PopUpReaction.Event.PopupShow -> shown += it
                    is PopUpReaction.Event.PopupHide -> hidden += it
                }
            }
        }
        runCurrent()
        val scan = nativeScan(clock, clock.elapsedRealtimeNanos())
        deviceFlow.value = mockk {
            every { profileId } returns "owned-pods"
            every { reactions } returns ReactionConfig(showPopUpOnCaseOpen = true)
            every { rawDataHex } returns listOf("OPEN")
            every { caseLidState } returns DualApplePods.LidState.OPEN
            every { ble } returns mockk {
                every { seenLastAt } answers { clock.now() }
                every { scanResult } returns scan
            }
        }
        runCurrent()
        shown.size shouldBe 1
        hidden.size shouldBe 0
        clock.advanceBy(Duration.ofSeconds(5))
        advanceTimeBy(2_001)
        runCurrent()
        hidden.size shouldBe 1
    }

    private fun nativeScan(clock: TestTimeSource, timestamp: Long): BleScanResult {
        val native = mockk<ScanResult> {
            every { device } returns mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
            every { rssi } returns -55
            every { timestampNanos } returns timestamp
            every { scanRecord } returns null
        }
        return BleScanResult.fromScanResult(native, clock)
    }
}
