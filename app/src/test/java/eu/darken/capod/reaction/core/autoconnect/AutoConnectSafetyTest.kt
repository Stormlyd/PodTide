package eu.darken.capod.reaction.core.autoconnect

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import eu.darken.capod.common.SystemTimeSource
import eu.darken.capod.common.TimeSource
import eu.darken.capod.common.bluetooth.BleScanResult
import eu.darken.capod.common.bluetooth.BluetoothDevice2
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.bluetooth.NudgeAvailability
import eu.darken.capod.common.bluetooth.NudgeAttemptResult
import eu.darken.capod.common.bluetooth.NudgeCapabilityStore
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.ble.devices.ApplePods
import eu.darken.capod.pods.core.apple.ble.devices.airpods.HasStateDetectionAirPods
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.ReactionConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.time.Instant
import java.time.Duration
import testhelpers.TestTimeSource

class AutoConnectSafetyTest : BaseTest() {
    private val monotonicEpochNanos = System.nanoTime()
    private fun monotonicNowNanos(): Long = 100_000_000_000L + (System.nanoTime() - monotonicEpochNanos)
    private val address = "AA:BB:CC:DD:EE:FF"
    private val paired = mockk<BluetoothDevice2> { every { address } returns this@AutoConnectSafetyTest.address }
    private val connected = MutableStateFlow(emptyList<BluetoothDevice2>())
    private val audioConnected = MutableStateFlow(emptySet<String>())
    private val headsetConnected = MutableStateFlow(emptySet<String>())
    private val devices = MutableStateFlow(emptyList<PodDevice>())
    private val bluetooth = mockk<BluetoothManager2>(relaxed = true) {
        every { connectedDevices } returns connected
        every { audioConnectedAddresses } returns audioConnected
        every { headsetConnectedAddresses } returns headsetConnected
        every { bondedDevices() } returns flowOf(setOf(paired))
        coEvery { nudgeConnection(any(), any()) } returns NudgeAttemptResult.Rejected
    }
    private val monitor = mockk<DeviceMonitor> { every { devices } returns this@AutoConnectSafetyTest.devices }
    private val capability = mockk<NudgeCapabilityStore>(relaxed = true) {
        every { availability } returns MutableStateFlow(NudgeAvailability.AVAILABLE)
    }
    private fun create(elapsedMillis: () -> Long) = AutoConnect(
        bluetooth, monitor, capability,
        object : TimeSource by SystemTimeSource {
            override fun elapsedRealtime() = elapsedMillis()
            override fun elapsedRealtimeNanos() = monotonicNowNanos()
        },
    )
    private fun pod(
        verified: Boolean = true,
        state: HasStateDetectionAirPods.ConnectionState = HasStateDetectionAirPods.ConnectionState.IDLE,
        seenAt: Instant? = null,
        nativeAgeMillis: Long? = null,
        nativeTimestampNanos: Long? = null,
    ): PodDevice {
        val now = SystemTimeSource.now()
        val monotonicNow = monotonicNowNanos()
        val ageNanos = nativeAgeMillis?.let { it * 1_000_000L }
            ?: Duration.between(seenAt ?: now, now).toNanos().coerceAtLeast(0L)
        val native = mockk<ScanResult> {
            every { device } returns mockk<BluetoothDevice> { every { address } returns this@AutoConnectSafetyTest.address }
            every { rssi } returns -55
            every { timestampNanos } returns (nativeTimestampNanos ?: monotonicNow - ageNanos)
            every { scanRecord } returns null
        }
        val scan = BleScanResult.fromScanResult(native, TestTimeSource(wallNow = now, elapsedRealtimeNanosValue = monotonicNow))
        val profile = mockk<AppleDeviceProfile> {
            every { address } returns this@AutoConnectSafetyTest.address
            every { id } returns "profile"
        }
        val ble = mockk<HasStateDetectionAirPods>(relaxed = true) {
            every { model } returns PodModel.AIRPODS_PRO3
            every { meta } returns ApplePods.AppleMeta(isIRKMatch = verified, profile = profile)
            every { seenLastAt } answers { seenAt ?: SystemTimeSource.now() }
            every { scanResult } returns scan
            every { this@mockk.state } returns state
        }
        return PodDevice("profile", ble = ble, aap = null, profileAddress = address, reactions = ReactionConfig(autoConnect = true))
    }

    @Test
    fun `twenty second native IDLE cannot authorize request or resumed proxy guard`() = runTest {
        devices.value = listOf(pod())
        val captured = CompletableDeferred<suspend () -> Boolean>()
        val pending = CompletableDeferred<Unit>()
        coEvery { bluetooth.requestAudioConnection(any(), any()) } coAnswers {
            captured.complete(secondArg())
            pending.await()
            false
        }
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        captured.isCompleted shouldBe true
        captured.await().invoke() shouldBe true
        devices.value = listOf(pod(seenAt = SystemTimeSource.now(), nativeAgeMillis = 20_000))
        runCurrent()
        captured.await().invoke() shouldBe false
        pending.complete(Unit)
        runCurrent()
        job.cancel()
    }

    @Test
    fun `invalid and future native IDLE cannot request audio`() = runTest {
        val monotonicNow = monotonicNowNanos()
        for (invalid in listOf(0L, -1L, monotonicNow + 1_000_000_000L)) {
            devices.value = listOf(pod(seenAt = SystemTimeSource.now(), nativeTimestampNanos = invalid))
            val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
            runCurrent()
            coVerify(exactly = 0) { bluetooth.requestAudioConnection(any(), any()) }
            job.cancel()
            runCurrent()
        }
    }

    @Test
    fun `same model heuristic profile match cannot authorize reconnect`() = runTest {
        devices.value = listOf(pod(verified = false))
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        coVerify(exactly = 0) { bluetooth.requestAudioConnection(any(), any()) }
        job.cancel()
    }

    @Test
    fun `HFP only connection still retries music audio after rejected requests`() = runTest {
        devices.value = listOf(pod())
        connected.value = listOf(paired) // HEADSET is connected, A2DP is not.
        headsetConnected.value = setOf(address)
        coEvery { bluetooth.requestAudioConnection(any(), any()) } returns false
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        coVerify(exactly = 2) { bluetooth.requestAudioConnection(any(), any()) }
        job.cancel()
    }

    @Test
    fun `observed HFP does not receive duplicate requests while audio retries`() = runTest {
        devices.value = listOf(pod())
        connected.value = listOf(paired) // Only HFP observed; music profile remains disconnected.
        headsetConnected.value = setOf(address)
        coEvery { bluetooth.requestAudioConnection(any(), any()) } returns false
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        coVerify(exactly = 2) { bluetooth.requestAudioConnection(any(), any()) }
        coVerify(exactly = 0) { bluetooth.nudgeConnection(any(), any()) }
        job.cancel()
    }

    @Test
    fun `accepted request retries until A2DP is observed and disconnection resets attempts`() = runTest {
        devices.value = listOf(pod())
        connected.value = listOf(paired)
        coEvery { bluetooth.requestAudioConnection(any(), any()) } returns true
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        coVerify(exactly = 2) { bluetooth.requestAudioConnection(any(), any()) }
        audioConnected.value = setOf(address)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        coVerify(exactly = 2) { bluetooth.requestAudioConnection(any(), any()) }
        audioConnected.value = emptySet()
        runCurrent()
        coVerify(exactly = 3) { bluetooth.requestAudioConnection(any(), any()) }
        job.cancel()
    }

    @Test
    fun `A2DP observed before headset acquisition still permits idle headset request`() = runTest {
        devices.value = listOf(pod())
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        coEvery { bluetooth.requestAudioConnection(any(), any()) } coAnswers {
            started.complete(Unit)
            resume.await()
            true
        }
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        started.isCompleted shouldBe true
        audioConnected.value = setOf(address)
        runCurrent()
        resume.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { bluetooth.nudgeConnection(any(), any()) }
        job.cancel()
    }

    @Test
    fun `A2DP observed while waiting for headset proxy preserves idle identity guard`() = runTest {
        devices.value = listOf(pod())
        coEvery { bluetooth.requestAudioConnection(any(), any()) } returns true
        val captured = CompletableDeferred<suspend () -> Boolean>()
        val pending = CompletableDeferred<Unit>()
        coEvery { bluetooth.nudgeConnection(any(), any()) } coAnswers {
            captured.complete(secondArg())
            pending.await()
            NudgeAttemptResult.Rejected
        }
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
        runCurrent()
        captured.isCompleted shouldBe true
        captured.await().invoke() shouldBe true
        audioConnected.value = setOf(address)
        runCurrent()
        captured.await().invoke() shouldBe true
        devices.value = listOf(pod(state = HasStateDetectionAirPods.ConnectionState.MUSIC))
        runCurrent()
        captured.await().invoke() shouldBe false
        devices.value = listOf(pod(seenAt = SystemTimeSource.now().minusMillis(5_001)))
        runCurrent()
        captured.await().invoke() shouldBe false
        job.cancel()
    }

    @Test
    fun `permission denial during bonded lookup does not end retries after grant`() = runTest {
        devices.value = listOf(pod())
        var granted = false
        every { bluetooth.bondedDevices() } returns flow {
            if (!granted) throw SecurityException("BLUETOOTH_CONNECT pending")
            emit(setOf(paired))
        }
        val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().catch { }.collect() }
        runCurrent()
        job.isActive shouldBe true
        coVerify(exactly = 0) { bluetooth.requestAudioConnection(any(), any()) }
        granted = true
        advanceTimeBy(1_001)
        runCurrent()
        coVerify(exactly = 1) { bluetooth.requestAudioConnection(any(), any()) }
        job.cancel()
    }

    @Test
    fun `latest settings and identity are rechecked while a request awaits its proxy`() = runTest {
        val original = pod()
        val unsafeStates = listOf(
            listOf(original.copy(reactions = ReactionConfig(autoConnect = false))),
            listOf(original.copy(profileId = "different-profile")),
            listOf(original.copy(profileAddress = "11:22:33:44:55:66")),
            listOf(original.copy(profileAddress = null)),
            listOf(original.copy(reactions = ReactionConfig(autoConnect = true, autoConnectCondition = AutoConnectCondition.IN_EAR))),
            listOf(pod(verified = false)),
            listOf(pod(state = HasStateDetectionAirPods.ConnectionState.MUSIC)),
            listOf(pod(state = HasStateDetectionAirPods.ConnectionState.CALL)),
            listOf(pod(state = HasStateDetectionAirPods.ConnectionState.UNKNOWN)),
            listOf(pod(seenAt = SystemTimeSource.now().minusMillis(5_001))),
            emptyList(),
        )
        for (unsafe in unsafeStates) {
            devices.value = listOf(original)
            val captured = CompletableDeferred<suspend () -> Boolean>()
            val pending = CompletableDeferred<Unit>()
            coEvery { bluetooth.requestAudioConnection(any(), any()) } coAnswers {
                captured.complete(secondArg())
                pending.await()
                false
            }
            val job = backgroundScope.launch { create { testScheduler.currentTime }.monitor().collect() }
            runCurrent()
            captured.isCompleted shouldBe true
            captured.await().invoke() shouldBe true
            devices.value = unsafe
            runCurrent()
            captured.await().invoke() shouldBe false
            job.cancel()
            runCurrent()
        }
    }
}
