package eu.darken.capod.monitor.core.aap

import android.bluetooth.BluetoothDevice
import eu.darken.capod.common.bluetooth.BluetoothDevice2
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.AapDisconnectEvent
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.pods.core.apple.aap.protocol.AapDeviceInfo
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The recovery driver is an open-ended per-address loop, so tests never use `advanceUntilIdle()`
 * (it would never return). They advance virtual time in bounded steps and verify the observable
 * side effects: connect attempts, their cadence and their cancellation.
 */
class AapAutoConnectTest : BaseTest() {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var aapManager: AapConnectionManager
    private lateinit var profilesRepo: DeviceProfilesRepo
    private lateinit var bluetoothManager: BluetoothManager2

    private lateinit var profilesFlow: MutableStateFlow<List<DeviceProfile>>
    private lateinit var connectedDevicesFlow: MutableStateFlow<List<BluetoothDevice2>>
    private lateinit var bondedDevicesFlow: MutableStateFlow<Set<BluetoothDevice2>>
    private lateinit var disconnectEventsFlow: MutableSharedFlow<AapDisconnectEvent>
    private lateinit var allStatesFlow: MutableStateFlow<Map<String, AapPodState>>

    private val testAddress = "AA:BB:CC:DD:EE:FF"
    private val otherAddress = "11:22:33:44:55:66"

    private val testProfile = AppleDeviceProfile(
        label = "Test AirPods",
        model = PodModel.AIRPODS_PRO3,
        address = testAddress,
    )
    private val otherProfile = AppleDeviceProfile(
        label = "Other AirPods",
        model = PodModel.AIRPODS_PRO3,
        address = otherAddress,
    )

    private val testBondedDevice: BluetoothDevice2 = mockk {
        every { address } returns testAddress
        every { internal } returns mockk<BluetoothDevice>(relaxed = true)
    }
    private val otherBondedDevice: BluetoothDevice2 = mockk {
        every { address } returns otherAddress
        every { internal } returns mockk<BluetoothDevice>(relaxed = true)
    }

    @BeforeEach
    fun setup() {
        profilesFlow = MutableStateFlow(emptyList())
        connectedDevicesFlow = MutableStateFlow(emptyList())
        bondedDevicesFlow = MutableStateFlow(setOf(testBondedDevice))
        disconnectEventsFlow = MutableSharedFlow(extraBufferCapacity = 16)
        allStatesFlow = MutableStateFlow(emptyMap())

        aapManager = mockk(relaxed = true) {
            every { allStates } returns allStatesFlow
            every { disconnectEvents } returns disconnectEventsFlow
        }

        profilesRepo = mockk(relaxUnitFun = true) {
            every { profiles } returns profilesFlow
        }

        bluetoothManager = mockk {
            every { bondedDevices() } returns bondedDevicesFlow
            every { connectedDevices } returns connectedDevicesFlow
        }
    }

    private fun createAutoConnect() = AapAutoConnect(
        aapManager = aapManager,
        profilesRepo = profilesRepo,
        bluetoothManager = bluetoothManager,
    )

    private fun TestScope.startMonitor(): Job {
        val job = launch { createAutoConnect().monitor().collect { } }
        runCurrent()
        return job
    }

    private fun aapState(state: AapPodState.ConnectionState) =
        mapOf(testAddress to AapPodState(connectionState = state))

    @Nested
    inner class Scheduling {

        @Test
        fun `connects when the profiled device is system connected`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)

            val job = startMonitor()

            coVerify(exactly = 1) { aapManager.connect(testAddress, any(), PodModel.AIRPODS_PRO3) }
            job.cancel()
        }

        @Test
        fun `does not connect while the system connection is missing`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile)

            val job = startMonitor()
            coVerify(exactly = 0) { aapManager.connect(any(), any(), any()) }

            connectedDevicesFlow.value = listOf(testBondedDevice)
            runCurrent()

            coVerify(exactly = 1) { aapManager.connect(testAddress, any(), PodModel.AIRPODS_PRO3) }
            job.cancel()
        }

        @Test
        fun `skips profiles without an address`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile.copy(address = null))
            connectedDevicesFlow.value = listOf(testBondedDevice)

            val job = startMonitor()

            coVerify(exactly = 0) { aapManager.connect(any(), any(), any()) }
            job.cancel()
        }

        @Test
        fun `does not connect when the device is not bonded`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            bondedDevicesFlow.value = emptySet()

            val job = startMonitor()

            coVerify(exactly = 0) { aapManager.connect(any(), any(), any()) }
            job.cancel()
        }

        @Test
        fun `does not connect when the AAP session is already ready`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            allStatesFlow.value = aapState(AapPodState.ConnectionState.READY)

            val job = startMonitor()

            coVerify(exactly = 0) { aapManager.connect(any(), any(), any()) }
            job.cancel()
        }

        @Test
        fun `creates exactly one task per address`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile, otherProfile)
            bondedDevicesFlow.value = setOf(testBondedDevice, otherBondedDevice)
            connectedDevicesFlow.value = listOf(testBondedDevice)

            val job = startMonitor()
            coVerify(exactly = 1) { aapManager.connect(testAddress, any(), any()) }

            // A second device arriving re-emits the combined state, but must not duplicate the
            // still-running task for the first address.
            connectedDevicesFlow.value = listOf(testBondedDevice, otherBondedDevice)
            runCurrent()

            coVerify(exactly = 1) { aapManager.connect(testAddress, any(), any()) }
            coVerify(exactly = 1) { aapManager.connect(otherAddress, any(), any()) }
            job.cancel()
        }
    }

    @Nested
    inner class PersistentRecovery {

        @Test
        fun `keeps retrying after failed rounds without any external change`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                throw RuntimeException("socket refused")
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()

            // Round 1 at t=0, then the gentle 2s/5s ladder keeps cycling.
            advanceTimeBy(50_000)
            runCurrent()
            val attemptsBefore = attempts
            (attemptsBefore > 3) shouldBe true

            // Nothing external changed: the loop must still be alive and able to recover.
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                allStatesFlow.value = aapState(AapPodState.ConnectionState.CONNECTING)
            }
            advanceTimeBy(60_000)
            runCurrent()
            (attempts > attemptsBefore) shouldBe true

            allStatesFlow.value = aapState(AapPodState.ConnectionState.READY)
            runCurrent()
            val attemptsAtReady = attempts

            // Reaching READY ends recovery; no further attempts afterwards.
            advanceTimeBy(300_000)
            runCurrent()
            attempts shouldBe attemptsAtReady
            job.cancel()
        }

        @Test
        fun `stops retrying once the handshake reaches READY`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                allStatesFlow.value = aapState(AapPodState.ConnectionState.CONNECTING)
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()
            attempts shouldBe 1

            allStatesFlow.value = aapState(AapPodState.ConnectionState.READY)
            runCurrent()

            advanceTimeBy(300_000)
            runCurrent()
            attempts shouldBe 1
            job.cancel()
        }

        @Test
        fun `a socket that never reaches READY is not treated as success`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                allStatesFlow.value = aapState(AapPodState.ConnectionState.CONNECTING)
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()
            attempts shouldBe 1

            // Handshake stalls: after the READY timeout and the first cooldown, a new attempt starts.
            advanceTimeBy(AapAutoConnect.READY_TIMEOUT_MS)
            runCurrent()
            attempts shouldBe 1

            advanceTimeBy(AapAutoConnect.HANDSHAKE_BACKOFF.first())
            runCurrent()
            attempts shouldBe 2
            job.cancel()
        }

        @Test
        fun `a fresh system connection clears an escalated cooldown`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                throw RuntimeException("socket refused")
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()

            advanceTimeBy(50_000)
            runCurrent()
            val beforeReconnect = attempts
            (beforeReconnect > 3) shouldBe true

            // Device leaves and comes back: that is a new session, so recovery must react at once
            // instead of waiting out the cooldown earned by the previous outage.
            connectedDevicesFlow.value = emptyList()
            runCurrent()
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                allStatesFlow.value = aapState(AapPodState.ConnectionState.CONNECTING)
            }
            connectedDevicesFlow.value = listOf(testBondedDevice)

            runCurrent()
            attempts shouldBe beforeReconnect + 1
            job.cancel()
        }

        @Test
        fun `recovers after an AAP drop while the system connection stays`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                allStatesFlow.value = aapState(AapPodState.ConnectionState.CONNECTING)
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()
            attempts shouldBe 1

            allStatesFlow.value = aapState(AapPodState.ConnectionState.READY)
            runCurrent()

            // The pods stop advertising (no BLE data at all) and the AAP channel drops anyway.
            allStatesFlow.value = aapState(AapPodState.ConnectionState.DISCONNECTED)
            disconnectEventsFlow.emit(AapDisconnectEvent(testAddress, wasEverReady = true))
            runCurrent()

            attempts shouldBe 2
            job.cancel()
        }
    }

    @Nested
    inner class Cancellation {

        @Test
        fun `cancels recovery when the system disconnects`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                allStatesFlow.value = aapState(AapPodState.ConnectionState.CONNECTING)
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()

            connectedDevicesFlow.value = emptyList()
            runCurrent()

            advanceTimeBy(300_000)
            runCurrent()
            attempts shouldBe 1
            job.cancel()
        }

        @Test
        fun `cancels recovery when the profile is removed`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                throw RuntimeException("socket refused")
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()

            profilesFlow.value = emptyList()
            runCurrent()

            advanceTimeBy(300_000)
            runCurrent()
            attempts shouldBe 1
            job.cancel()
        }

        @Test
        fun `cancels recovery when the device is unpaired`() = runTest(testDispatcher) {
            var attempts = 0
            coEvery { aapManager.connect(any(), any(), any()) } coAnswers {
                attempts++
                throw RuntimeException("socket refused")
            }

            profilesFlow.value = listOf(testProfile)
            connectedDevicesFlow.value = listOf(testBondedDevice)
            val job = startMonitor()

            bondedDevicesFlow.value = emptySet()
            runCurrent()

            advanceTimeBy(300_000)
            runCurrent()
            attempts shouldBe 1
            job.cancel()
        }
    }

    @Nested
    inner class ModelCorrection {

        private val testDeviceInfo = AapDeviceInfo(
            name = "AirPods Pro 3",
            modelNumber = "A3064",
            manufacturer = "Apple Inc.",
            serialNumber = "XXXX",
            firmwareVersion = "1.0",
        )

        @Test
        fun `corrects the model when deviceInfo reports a different one`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile.copy(model = PodModel.UNKNOWN))

            var capturedProfile: AppleDeviceProfile? = null
            coEvery { profilesRepo.updateProfile(ofType<AppleDeviceProfile>()) } coAnswers {
                capturedProfile = firstArg()
            }

            val job = startMonitor()
            allStatesFlow.value = mapOf(
                testAddress to AapPodState(
                    connectionState = AapPodState.ConnectionState.READY,
                    deviceInfo = testDeviceInfo,
                )
            )
            advanceTimeBy(2_100)
            runCurrent()

            capturedProfile!!.model shouldBe PodModel.AIRPODS_PRO3
            coVerify(exactly = 1) { aapManager.disconnect(testAddress) }
            coVerify { aapManager.connect(testAddress, any(), PodModel.AIRPODS_PRO3) }
            job.cancel()
        }

        @Test
        fun `does not correct when the model already matches`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile)

            val job = startMonitor()
            allStatesFlow.value = mapOf(
                testAddress to AapPodState(
                    connectionState = AapPodState.ConnectionState.READY,
                    deviceInfo = testDeviceInfo,
                )
            )
            advanceTimeBy(2_100)
            runCurrent()

            coVerify(exactly = 0) { profilesRepo.updateProfile(ofType<AppleDeviceProfile>()) }
            coVerify(exactly = 0) { aapManager.disconnect(testAddress) }
            job.cancel()
        }

        @Test
        fun `does not correct when the reported model number is unknown`() = runTest(testDispatcher) {
            profilesFlow.value = listOf(testProfile.copy(model = PodModel.UNKNOWN))

            val job = startMonitor()
            allStatesFlow.value = mapOf(
                testAddress to AapPodState(
                    connectionState = AapPodState.ConnectionState.READY,
                    deviceInfo = testDeviceInfo.copy(modelNumber = "ZZZZ"),
                )
            )
            advanceTimeBy(2_100)
            runCurrent()

            coVerify(exactly = 0) { profilesRepo.updateProfile(ofType<AppleDeviceProfile>()) }
            coVerify(exactly = 0) { aapManager.disconnect(testAddress) }
            job.cancel()
        }
    }
}