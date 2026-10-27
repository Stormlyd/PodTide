package eu.darken.capod.common.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import testhelpers.BaseTest
import testhelpers.TestTimeSource
import testhelpers.coroutine.TestDispatcherProvider
import java.time.Duration

class BluetoothManager2Test : BaseTest() {

    private val deviceA = mockk<BluetoothDevice>().apply {
        every { address } returns ADDRESS_A
        every { name } returns "Pods A"
    }
    private val deviceB = mockk<BluetoothDevice>().apply {
        every { address } returns ADDRESS_B
        every { name } returns "Pods B"
    }
    private val btAdapter = mockk<BluetoothAdapter>().apply {
        every { bondedDevices } returns setOf(deviceA, deviceB)
    }
    private val btManager = mockk<BluetoothManager>().apply {
        every { adapter } returns btAdapter
    }
    private val timeSource = TestTimeSource()

    // Unconfined, so the appScope.launch bodies of the mark* methods complete in place.
    private val appScope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterEach
    fun teardown() {
        appScope.cancel()
    }

    private fun create(
        scope: CoroutineScope = appScope,
        dispatchers: TestDispatcherProvider = TestDispatcherProvider(),
    ) = BluetoothManager2(
        appScope = scope,
        dispatcherProvider = dispatchers,
        context = mockk<Context>(),
        manager = btManager,
        timeSource = timeSource,
    )

    private suspend fun BluetoothManager2.bonded(address: BluetoothAddress) =
        bondedDevices().first().single { it.address == address }

    /**
     * A bonded device is not necessarily a connected one, so querying bonded devices must not put a
     * timestamp into the cache that [BluetoothManager2.connectedDevices] later reads as a connect
     * time.
     */
    @Test
    fun `querying bonded devices does not cache a timestamp`() = runTest {
        val manager = create()

        manager.bonded(ADDRESS_A).seenFirstAt shouldBe timeSource.now()

        timeSource.advanceBy(Duration.ofSeconds(60))

        manager.bonded(ADDRESS_A).seenFirstAt shouldBe timeSource.now()
    }

    @Test
    fun `music profile observation starts unknown until a platform query completes`() = runTest {
        create(backgroundScope, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
            .audioConnectedAddresses.first() shouldBe null
    }

    /**
     * The ACL broadcast arrives whether or not anything is collecting the connected-devices flow,
     * so the stamp it leaves has to survive until the device disconnects.
     */
    @Test
    fun `an ACL connect stamp survives the passage of time`() = runTest {
        val manager = create()
        val connectedAt = timeSource.now()

        manager.markDeviceConnected(ADDRESS_A)

        timeSource.advanceBy(Duration.ofSeconds(60))

        manager.bonded(ADDRESS_A).seenFirstAt shouldBe connectedAt
    }

    @Test
    fun `a repeated ACL connect keeps the first stamp`() = runTest {
        val manager = create()
        val connectedAt = timeSource.now()

        manager.markDeviceConnected(ADDRESS_A)

        timeSource.advanceBy(Duration.ofSeconds(60))
        manager.markDeviceConnected(ADDRESS_A)

        manager.bonded(ADDRESS_A).seenFirstAt shouldBe connectedAt
    }

    @Test
    fun `an ACL disconnect drops the stamp`() = runTest {
        val manager = create()

        manager.markDeviceConnected(ADDRESS_A)

        timeSource.advanceBy(Duration.ofSeconds(60))
        manager.markDeviceDisconnected(ADDRESS_A)

        manager.bonded(ADDRESS_A).seenFirstAt shouldBe timeSource.now()
    }

    @Test
    fun `audio request gives up acquiring profile after 2000 milliseconds`() = runTest {
        every { btAdapter.getProfileProxy(any(), any(), any()) } returns true
        val manager = create()
        val request = backgroundScope.async { manager.requestAudioConnection(manager.bonded(ADDRESS_A)) }
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        request.isCompleted shouldBe true
        if (request.isCompleted) request.await() shouldBe false
    }

    @Test
    fun `profile callback arriving after cancellation closes its proxy`() = runTest {
        val listener = slot<BluetoothProfile.ServiceListener>()
        every { btAdapter.getProfileProxy(any(), capture(listener), any()) } returns true
        every { btAdapter.closeProfileProxy(any(), any()) } returns Unit
        val manager = create()
        val request = backgroundScope.async { manager.requestAudioConnection(manager.bonded(ADDRESS_A)) }
        runCurrent()
        request.cancel()
        runCurrent()
        val proxy = mockk<BluetoothProfile>()
        listener.captured.onServiceConnected(BluetoothProfile.A2DP, proxy)
        runCurrent()
        request.isCancelled shouldBe true
        verify(exactly = 1) { btAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
    }

    @Test
    fun `state becoming unsafe while awaiting A2DP proxy prevents actual connect`() = runTest {
        val listener = slot<BluetoothProfile.ServiceListener>()
        every { btAdapter.getProfileProxy(any(), capture(listener), any()) } returns true
        every { btAdapter.closeProfileProxy(any(), any()) } returns Unit
        val manager = create()
        var isIdle = true
        val request = backgroundScope.async {
            manager.requestAudioConnection(manager.bonded(ADDRESS_A)) { isIdle }
        }
        runCurrent()
        isIdle = false // Music/call, stale BLE, changed target, or disabled setting during acquisition.
        val proxy = RecordingProfile()
        listener.captured.onServiceConnected(BluetoothProfile.A2DP, proxy)
        runCurrent()
        request.await() shouldBe false
        proxy.connectCalls shouldBe 0
        verify(exactly = 1) { btAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
    }

    @Test
    fun `both profiles recheck permission immediately before connect invocation`() = runTest {
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val listener = slot<BluetoothProfile.ServiceListener>()
            every { btAdapter.getProfileProxy(any(), capture(listener), profile) } returns true
            every { btAdapter.closeProfileProxy(any(), any()) } returns Unit
            val manager = create()
            var checks = 0
            val guard = { ++checks == 1 }
            val request = backgroundScope.async {
                val device = manager.bonded(ADDRESS_A)
                if (profile == BluetoothProfile.A2DP) manager.requestAudioConnection(device, guard)
                else manager.nudgeConnection(device, guard) == NudgeAttemptResult.Accepted
            }
            runCurrent()
            val proxy = RecordingProfile()
            listener.captured.onServiceConnected(profile, proxy)
            runCurrent()
            request.await() shouldBe false
            checks shouldBe 2
            proxy.connectCalls shouldBe 0
            verify(exactly = 1) { btAdapter.closeProfileProxy(profile, proxy) }
        }
    }

    @Test
    fun `manual requests can connect both profiles with default authorization`() = runTest {
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val listener = slot<BluetoothProfile.ServiceListener>()
            every { btAdapter.getProfileProxy(any(), capture(listener), profile) } returns true
            every { btAdapter.closeProfileProxy(any(), any()) } returns Unit
            val manager = create()
            val request = backgroundScope.async {
                val device = manager.bonded(ADDRESS_A)
                if (profile == BluetoothProfile.A2DP) manager.requestAudioConnection(device)
                else manager.nudgeConnection(device) == NudgeAttemptResult.Accepted
            }
            runCurrent()
            val proxy = RecordingProfile()
            listener.captured.onServiceConnected(profile, proxy)
            runCurrent()
            request.await() shouldBe true
            proxy.connectCalls shouldBe 1
            verify(exactly = 1) { btAdapter.closeProfileProxy(profile, proxy) }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [BluetoothProfile.A2DP, BluetoothProfile.HEADSET])
    fun `cancellation during last suspending guard closes proxy without connecting`(profile: Int) = runTest {
        val listener = slot<BluetoothProfile.ServiceListener>()
        every { btAdapter.getProfileProxy(any(), capture(listener), profile) } returns true
        every { btAdapter.closeProfileProxy(any(), any()) } returns Unit
        val manager = create()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var checks = 0
        val guard: suspend () -> Boolean = {
            if (++checks == 2) {
                entered.complete(Unit)
                // Even a guard that finishes a non-cancellable read cannot authorize a cancelled job.
                withContext(NonCancellable) { release.await() }
            }
            true
        }
        val request = backgroundScope.async {
            val device = manager.bonded(ADDRESS_A)
            if (profile == BluetoothProfile.A2DP) manager.requestAudioConnection(device, guard)
            else manager.nudgeConnection(device, guard) == NudgeAttemptResult.Accepted
        }
        runCurrent()
        val proxy = RecordingProfile()
        listener.captured.onServiceConnected(profile, proxy)
        runCurrent()
        entered.isCompleted shouldBe true
        request.cancel()
        release.complete(Unit)
        runCurrent()
        request.isCancelled shouldBe true
        proxy.connectCalls shouldBe 0
        verify(exactly = 1) { btAdapter.closeProfileProxy(profile, proxy) }
    }

    class RecordingProfile : BluetoothProfile {
        var connectCalls = 0
        fun connect(device: BluetoothDevice): Boolean { connectCalls++; return true }
        override fun getConnectedDevices(): List<BluetoothDevice> = emptyList()
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = emptyList()
        override fun getConnectionState(device: BluetoothDevice): Int = BluetoothProfile.STATE_DISCONNECTED
    }

    companion object {
        private const val ADDRESS_A = "AA:BB:CC:DD:EE:F1"
        private const val ADDRESS_B = "AA:BB:CC:DD:EE:F2"
    }
}
