package eu.darken.capod.main.ui.devicesettings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import eu.darken.capod.common.TimeSource
import eu.darken.capod.common.bluetooth.BluetoothAddress
import eu.darken.capod.common.bluetooth.BluetoothDevice2
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.bluetooth.NudgeAttemptResult
import eu.darken.capod.common.bluetooth.NudgeAvailability
import eu.darken.capod.common.bluetooth.NudgeCapabilityStore
import eu.darken.capod.common.navigation.Nav
import eu.darken.capod.common.navigation.NavEvent
import eu.darken.capod.common.upgrade.UpgradeRepo
import eu.darken.capod.main.core.MonitorMode
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.MonitorModeResolver
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.monitor.core.battery.BatteryDrainStore
import eu.darken.capod.monitor.core.battery.BatteryEstimator
import eu.darken.capod.monitor.core.battery.BatteryHealth
import eu.darken.capod.monitor.core.battery.DrainProfile
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import eu.darken.capod.profiles.core.DeviceProfilesSettings
import eu.darken.capod.profiles.core.DeviceProfilesContainer
import eu.darken.capod.profiles.core.ProfileId
import eu.darken.capod.profiles.core.SelectedDeviceTarget
import eu.darken.capod.reaction.core.conversation.ConversationVolumeMode
import eu.darken.capod.reaction.core.stem.StemAction
import eu.darken.capod.reaction.core.stem.StemActionsConfig
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import testhelpers.BaseTest
import testhelpers.TestTimeSource
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.livedata.InstantExecutorExtension
import testhelpers.datastore.FakeDataStoreValue

@ExtendWith(InstantExecutorExtension::class)
class DeviceSettingsViewModelTest : BaseTest() {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testAddress: BluetoothAddress = "AA:BB:CC:DD:EE:FF"
    private val selectedAddress: BluetoothAddress = "11:22:33:44:55:66"

    private var vm: DeviceSettingsViewModel? = null

    private lateinit var deviceMonitor: DeviceMonitor
    private lateinit var aapManager: AapConnectionManager
    private lateinit var upgradeRepo: UpgradeRepo
    private lateinit var bluetoothManager: BluetoothManager2
    private lateinit var profilesRepo: DeviceProfilesRepo
    private lateinit var batteryEstimator: BatteryEstimator
    private lateinit var drainStore: BatteryDrainStore
    private lateinit var drainProfilesFlow: MutableStateFlow<Map<ProfileId, DrainProfile>>
    private lateinit var monitorModeResolver: MonitorModeResolver
    private lateinit var nudgeCapabilityStore: NudgeCapabilityStore
    private lateinit var nudgeAvailabilityFlow: MutableStateFlow<NudgeAvailability>
    private lateinit var effectiveModeFlow: MutableStateFlow<MonitorMode>
    private val timeSource: TimeSource = TestTimeSource()

    private lateinit var devicesFlow: MutableStateFlow<List<PodDevice>>
    private lateinit var upgradeInfoFlow: MutableStateFlow<UpgradeRepo.Info>
    private lateinit var connectedDevicesFlow: MutableStateFlow<List<BluetoothDevice2>>
    private lateinit var profilesFlow: MutableStateFlow<List<DeviceProfile>>
    private var selectedRevision = 0L
    private lateinit var offRejectedFlow: kotlinx.coroutines.flow.MutableSharedFlow<BluetoothAddress>
    private lateinit var settingRejectedFlow: kotlinx.coroutines.flow.MutableSharedFlow<Pair<BluetoothAddress, AapCommand>>

    private fun mockBondedDevice(address: BluetoothAddress): BluetoothDevice2 = mockk {
        every { this@mockk.address } returns address
    }

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        devicesFlow = MutableStateFlow(emptyList())
        upgradeInfoFlow = MutableStateFlow(mockk<UpgradeRepo.Info>(relaxed = true).also {
            every { it.isPro } returns false
            // Hot flow + settled + no error: isProForUi resolves immediately. A finite flowOf or an
            // unsettled Info would send every gate through the fail-open timeout path instead.
            every { it.isSettled } returns true
            every { it.error } returns null
        })

        val syntheticDevice = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.address } returns testAddress
        }
        deviceMonitor = mockk<DeviceMonitor>().also {
            every { it.devices } returns devicesFlow
            coEvery { it.getDeviceForProfile(testAddress) } returns syntheticDevice
        }
        offRejectedFlow = kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 16)
        settingRejectedFlow = kotlinx.coroutines.flow.MutableSharedFlow(extraBufferCapacity = 16)
        aapManager = mockk(relaxed = true) {
            every { offRejectedEvents } returns offRejectedFlow
            every { settingRejectedEvents } returns settingRejectedFlow
        }
        upgradeRepo = mockk<UpgradeRepo>().also {
            every { it.upgradeInfo } returns upgradeInfoFlow
        }
        connectedDevicesFlow = MutableStateFlow(emptyList())
        bluetoothManager = mockk(relaxed = true) {
            every { bondedDevices() } returns flowOf(emptySet())
            every { connectedDevices } returns connectedDevicesFlow
            coEvery { nudgeConnection(any(), any()) } returns NudgeAttemptResult.Rejected
        }
        profilesFlow = MutableStateFlow(
            listOf(
                AppleDeviceProfile(id = testAddress, label = "Test", address = testAddress)
            )
        )
        selectedRevision = 0L
        profilesRepo = mockk(relaxed = true) {
            every { profiles } returns profilesFlow
            coEvery { selectedTarget(any()) } coAnswers {
                val id = firstArg<ProfileId>()
                SelectedDeviceTarget(profilesFlow.value.firstOrNull { it.id == id }?.address, selectedRevision)
            }
        }
        batteryEstimator = mockk(relaxed = true)
        drainProfilesFlow = MutableStateFlow(emptyMap())
        drainStore = mockk<BatteryDrainStore>().also {
            every { it.profiles } returns drainProfilesFlow
        }
        effectiveModeFlow = MutableStateFlow(MonitorMode.AUTOMATIC)
        monitorModeResolver = mockk<MonitorModeResolver>().also {
            every { it.effectiveMode } returns effectiveModeFlow
        }
        nudgeAvailabilityFlow = MutableStateFlow(NudgeAvailability.UNKNOWN)
        nudgeCapabilityStore = mockk<NudgeCapabilityStore>(relaxed = true).also {
            every { it.availability } returns nudgeAvailabilityFlow
        }
    }

    @AfterEach
    fun teardown() {
        vm?.vmScope?.cancel()
        vm = null
        Dispatchers.resetMain()
    }

    private fun createViewModel(dispatchers: TestDispatcherProvider = TestDispatcherProvider(testDispatcher)) = DeviceSettingsViewModel(
        dispatcherProvider = dispatchers,
        deviceMonitor = deviceMonitor,
        aapManager = aapManager,
        upgradeRepo = upgradeRepo,
        bluetoothManager = bluetoothManager,
        profilesRepo = profilesRepo,
        batteryEstimator = batteryEstimator,
        drainStore = drainStore,
        monitorModeResolver = monitorModeResolver,
        nudgeCapabilityStore = nudgeCapabilityStore,
        timeSource = timeSource,
        webpageTool = mockk(relaxed = true),
    ).also { vm = it }

    private fun runVmTest(testBody: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        try {
            testBody()
        } finally {
            vm?.vmScope?.cancel()
            vm = null
        }
    }

    private fun changeSelection(address: BluetoothAddress?) {
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = address))
        selectedRevision++
    }

    @ParameterizedTest(name = "profile {0}, selection change {1}")
    @CsvSource(
        "2,address", "2,remove", "2,restore", "2,screen", "2,cancel", "2,unchanged",
        "1,address", "1,remove", "1,restore", "1,screen", "1,cancel", "1,unchanged",
    )
    fun `manual connect revalidates selection at actual platform proxy boundary`(
        heldProfile: Int,
        change: String,
    ) = runVmTest {
        // Real repository authority, starting from persisted state; no UI profile collector.
        // In particular, restore never exposes its intermediate state to the ViewModel.
        val original = AppleDeviceProfile(id = testAddress, label = "Test", address = testAddress)
        val settings = mockk<DeviceProfilesSettings> {
            every { profiles } returns FakeDataStoreValue(DeviceProfilesContainer(listOf(original))).mock
            every { defaultProfileCreated } returns FakeDataStoreValue(true).mock
            every { reactionMigrationDone } returns FakeDataStoreValue(true).mock
            every { hadLegacyReactionData } returns FakeDataStoreValue(false).mock
        }
        profilesRepo = DeviceProfilesRepo(
            scope = backgroundScope,
            context = mockk(relaxed = true),
            generalSettings = mockk(relaxed = true),
            settings = settings,
            deviceStateCache = mockk(relaxed = true),
            batteryDrainStore = mockk(relaxed = true),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        val device = mockk<BluetoothDevice> {
            every { address } returns testAddress
            every { name } returns "Selected pods"
        }
        val listeners = mutableMapOf<Int, BluetoothProfile.ServiceListener>()
        val adapter = mockk<BluetoothAdapter> {
            every { bondedDevices } returns setOf(device)
            every { getProfileProxy(any(), any(), any()) } answers {
                listeners[thirdArg()] = secondArg()
                true
            }
            every { closeProfileProxy(any(), any()) } returns Unit
        }
        bluetoothManager = BluetoothManager2(
            appScope = backgroundScope,
            dispatcherProvider = TestDispatcherProvider(testDispatcher),
            context = mockk(),
            manager = mockk<BluetoothManager> { every { this@mockk.adapter } returns adapter },
            timeSource = timeSource,
        )
        // A rejected audio request makes a retired HEADSET result capable of triggering the
        // settings fallback too, so those cases verify both feedback and capability suppression.
        val audio = RecordingConnectionProfile(accepted = heldProfile != BluetoothProfile.HEADSET)
        val headset = RecordingConnectionProfile()
        val vm = createViewModel(TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.initialize(testAddress)
        vm.forceConnect()
        runCurrent()
        listeners.containsKey(BluetoothProfile.A2DP) shouldBe true
        if (heldProfile == BluetoothProfile.HEADSET) {
            listeners.getValue(BluetoothProfile.A2DP).onServiceConnected(BluetoothProfile.A2DP, audio)
            runCurrent()
            audio.connectedAddresses shouldBe listOf(testAddress)
        }
        listeners.containsKey(heldProfile) shouldBe true
        when (change) {
            "address" -> profilesRepo.updateProfile(original.copy(address = selectedAddress))
            "remove" -> profilesRepo.removeProfile(testAddress)
            "restore" -> {
                profilesRepo.removeProfile(testAddress)
                profilesRepo.addProfile(original)
            }
            "screen" -> {
                vm.initialize(selectedAddress)
                vm.initialize(testAddress)
            }
            "cancel" -> vm.vmScope.cancel()
            "unchanged" -> Unit
            else -> error("Unknown change $change")
        }
        val heldProxy = if (heldProfile == BluetoothProfile.A2DP) audio else headset
        listeners.getValue(heldProfile).onServiceConnected(heldProfile, heldProxy)
        runCurrent()
        if (change == "unchanged" && heldProfile == BluetoothProfile.A2DP) {
            listeners.getValue(BluetoothProfile.HEADSET).onServiceConnected(BluetoothProfile.HEADSET, headset)
            runCurrent()
        }
        if (change == "unchanged") {
            audio.connectedAddresses shouldBe listOf(testAddress)
            headset.connectedAddresses shouldBe listOf(testAddress)
            verify(exactly = 1) { nudgeCapabilityStore.record(NudgeAttemptResult.Accepted) }
            verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.A2DP, audio) }
            verify(exactly = 1) { adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset) }
        } else {
            heldProxy.connectedAddresses shouldBe emptyList()
            headset.connectedAddresses shouldBe emptyList()
            verify(exactly = 0) { nudgeCapabilityStore.record(any()) }
            verify(exactly = 1) { adapter.closeProfileProxy(heldProfile, heldProxy) }
        }
        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
    }

    class RecordingConnectionProfile(private val accepted: Boolean = true) : BluetoothProfile {
        val connectedAddresses = mutableListOf<String>()
        fun connect(device: BluetoothDevice): Boolean {
            connectedAddresses.add(device.address)
            return accepted
        }
        override fun getConnectedDevices(): List<BluetoothDevice> = emptyList()
        override fun getDevicesMatchingConnectionStates(states: IntArray): List<BluetoothDevice> = emptyList()
        override fun getConnectionState(device: BluetoothDevice): Int = BluetoothProfile.STATE_DISCONNECTED
    }

    @Test
    fun `forceConnect happy path - bonded exists, nudge accepted, no event emitted`() = runVmTest {
        val bonded = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(bonded))
        coEvery { bluetoothManager.nudgeConnection(bonded, any()) } returns NudgeAttemptResult.Accepted

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()

        coVerify { bluetoothManager.nudgeConnection(bonded, any()) }
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `forceConnect when nudge not accepted - emits OpenBluetoothSettings`() = runVmTest {
        val bonded = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(bonded))
        coEvery { bluetoothManager.nudgeConnection(bonded, any()) } returns NudgeAttemptResult.Rejected

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()

        val event = vm.events.first()
        event shouldBe DeviceSettingsViewModel.Event.OpenBluetoothSettings
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `forceConnect when nudge unavailable - emits OpenBluetoothSettings without calling nudge`() = runVmTest {
        val bonded = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(bonded))
        nudgeAvailabilityFlow.value = NudgeAvailability.BROKEN

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()

        val event = vm.events.first()
        event shouldBe DeviceSettingsViewModel.Event.OpenBluetoothSettings
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `forceConnect when no bonded device - emits OpenBluetoothSettings`() = runVmTest {
        every { bluetoothManager.bondedDevices() } returns flowOf(emptySet())

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()

        val event = vm.events.first()
        event shouldBe DeviceSettingsViewModel.Event.OpenBluetoothSettings
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `forceConnect when bondedDevices throws SecurityException - emits OpenBluetoothSettings`() = runVmTest {
        every { bluetoothManager.bondedDevices() } throws SecurityException("BLUETOOTH_CONNECT denied")

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()

        val event = vm.events.first()
        event shouldBe DeviceSettingsViewModel.Event.OpenBluetoothSettings
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `forceConnect when nudgeConnection throws - emits OpenBluetoothSettings and resets in-flight`() = runVmTest {
        val bonded = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(bonded))
        coEvery { bluetoothManager.nudgeConnection(bonded, any()) } throws RuntimeException("oops")

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()

        val event = vm.events.first()
        event shouldBe DeviceSettingsViewModel.Event.OpenBluetoothSettings
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `forceConnect concurrent calls - second call is a no-op while first in flight`() = runVmTest {
        val bonded = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(bonded))

        val gate = CompletableDeferred<NudgeAttemptResult>()
        coEvery { bluetoothManager.nudgeConnection(bonded, any()) } coAnswers { gate.await() }

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.forceConnect()
        vm.forceConnect()

        gate.complete(NudgeAttemptResult.Accepted)

        coVerify(exactly = 1) { bluetoothManager.nudgeConnection(bonded, any()) }
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `setDeviceName forwards SetDeviceName command to aapManager`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setDeviceName("NewName")

        coVerify { aapManager.sendCommand(testAddress, AapCommand.SetDeviceName("NewName")) }
    }

    @Test
    fun `commands and rename use selected address when device cache still names old address`() = runVmTest {
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.setNcWithOneAirPod(true)
        vm.setDeviceName("Selected")

        coVerify(exactly = 1) { aapManager.sendCommand(selectedAddress, AapCommand.SetNcWithOneAirPod(true)) }
        coVerify(exactly = 1) { aapManager.sendCommand(selectedAddress, AapCommand.SetDeviceName("Selected")) }
        coVerify(exactly = 0) { aapManager.sendCommand(testAddress, any()) }
    }

    @Test
    fun `removing selected address prevents settings commands despite stale device`() = runVmTest {
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = null))
        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.setNcWithOneAirPod(true)
        vm.setDeviceName("Removed")

        coVerify(exactly = 0) { aapManager.sendCommand(any(), any()) }
    }

    @Test
    fun `removing profile prevents settings commands and old manual connection`() = runVmTest {
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(oldBond))
        val vm = createViewModel()
        vm.initialize(testAddress)
        profilesFlow.value = emptyList()

        vm.setNcWithOneAirPod(true)
        vm.setDeviceName("Removed")
        vm.forceConnect()

        coVerify(exactly = 0) { aapManager.sendCommand(any(), any()) }
        coVerify(exactly = 0) { bluetoothManager.requestAudioConnection(any(), any()) }
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
        vm.events.first() shouldBe DeviceSettingsViewModel.Event.OpenBluetoothSettings
    }

    @Test
    fun `off rejection follows selected address rather than stale device address`() = runVmTest {
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        val vm = createViewModel()
        vm.initialize(testAddress)

        offRejectedFlow.emit(testAddress)
        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
        offRejectedFlow.emit(selectedAddress)

        vm.events.first() shouldBe DeviceSettingsViewModel.Event.OffModeRejectedByDevice
        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
    }

    @Test
    fun `setting rejection follows selected address rather than stale device address`() = runVmTest {
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        val vm = createViewModel()
        vm.initialize(testAddress)

        settingRejectedFlow.emit(testAddress to AapCommand.SetDynamicEndOfCharge(true))
        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
        settingRejectedFlow.emit(selectedAddress to AapCommand.SetDynamicEndOfCharge(true))

        vm.events.first() shouldBe DeviceSettingsViewModel.Event.DynamicEndOfChargeRejectedByDevice
        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
    }

    @Test
    fun `rejections are ignored after the saved profile is removed`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        profilesFlow.value = emptyList()

        offRejectedFlow.emit(testAddress)
        settingRejectedFlow.emit(testAddress to AapCommand.SetDynamicEndOfCharge(true))

        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
    }

    @Test
    fun `system Bluetooth name is looked up using selected address`() = runVmTest {
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        val oldBond = mockk<BluetoothDevice2> {
            every { address } returns testAddress
            every { name } returns "Old"
        }
        val selectedBond = mockk<BluetoothDevice2> {
            every { address } returns selectedAddress
            every { name } returns "Selected"
        }
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(oldBond, selectedBond))
        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().systemBluetoothName shouldBe "Selected"
    }

    @Test
    fun `changed selection during bonded lookup cannot request old audio or nudge`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            entered.complete(Unit)
            release.await()
            emit(setOf(oldBond))
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        entered.await()
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        release.complete(Unit)

        coVerify(exactly = 0) { bluetoothManager.requestAudioConnection(any(), any()) }
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
    }

    @Test
    fun `remove and restore same address during bonded lookup cancels old manual connection`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            entered.complete(Unit)
            release.await()
            emit(setOf(oldBond))
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        entered.await()
        changeSelection(null)
        changeSelection(testAddress)
        release.complete(Unit)

        coVerify(exactly = 0) { bluetoothManager.requestAudioConnection(any(), any()) }
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
    }

    @Test
    fun `switching the ViewModel profile away and back during lookup cancels old manual connection`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            entered.complete(Unit)
            release.await()
            emit(setOf(oldBond))
        }
        profilesFlow.value = listOf(
            AppleDeviceProfile(id = testAddress, label = "Test", address = testAddress),
            AppleDeviceProfile(id = selectedAddress, label = "Other", address = selectedAddress),
        )
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        entered.await()
        vm.initialize(selectedAddress)
        vm.initialize(testAddress)
        release.complete(Unit)

        coVerify(exactly = 0) { bluetoothManager.requestAudioConnection(any(), any()) }
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
    }

    @Test
    fun `remove and restore same address during audio request cancels old nudge`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Boolean>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(oldBond))
        coEvery { bluetoothManager.requestAudioConnection(oldBond, any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        entered.await()
        changeSelection(null)
        changeSelection(testAddress)
        release.complete(true)

        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
    }

    @Test
    fun `remove and restore same address during rename lookup cancels old alias`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            entered.complete(Unit)
            release.await()
            emit(setOf(oldBond))
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.setDeviceName("Fresh")
        entered.await()
        changeSelection(null)
        changeSelection(testAddress)
        release.complete(Unit)

        verify(exactly = 0) { bluetoothManager.setDeviceAlias(any(), any()) }
    }

    @Test
    fun `changed profile during bonded lookup cannot request old audio or nudge`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            entered.complete(Unit)
            release.await()
            emit(setOf(oldBond))
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        entered.await()
        profilesFlow.value = listOf(
            AppleDeviceProfile(id = testAddress, label = "Test", address = testAddress),
            AppleDeviceProfile(id = selectedAddress, label = "Other", address = selectedAddress),
        )
        vm.initialize(selectedAddress)
        release.complete(Unit)

        coVerify(exactly = 0) { bluetoothManager.requestAudioConnection(any(), any()) }
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
    }

    @Test
    fun `selection change while audio request is suspended prevents old nudge`() = runVmTest {
        val audioEntered = CompletableDeferred<Unit>()
        val releaseAudio = CompletableDeferred<Boolean>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flowOf(setOf(oldBond))
        coEvery { bluetoothManager.requestAudioConnection(oldBond, any()) } coAnswers {
            audioEntered.complete(Unit)
            releaseAudio.await()
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        audioEntered.await()
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        releaseAudio.complete(true)

        coVerify(exactly = 1) { bluetoothManager.requestAudioConnection(oldBond, any()) }
        coVerify(exactly = 0) { bluetoothManager.nudgeConnection(any(), any()) }
        vm.state.first().isForceConnecting shouldBe false
    }

    @Test
    fun `selection change during name bond lookup cannot rename old system alias`() = runVmTest {
        val lookupEntered = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val oldBond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            lookupEntered.complete(Unit)
            releaseLookup.await()
            emit(setOf(oldBond))
        }
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.setDeviceName("Fresh")
        lookupEntered.await()
        profilesFlow.value = listOf(AppleDeviceProfile(id = testAddress, label = "Test", address = selectedAddress))
        releaseLookup.complete(Unit)

        coVerify(exactly = 1) { aapManager.sendCommand(testAddress, AapCommand.SetDeviceName("Fresh")) }
        verify(exactly = 0) { bluetoothManager.setDeviceAlias(any(), any()) }
        withTimeoutOrNull(100) { vm.events.first() } shouldBe null
    }

    @Test
    fun `unchanged selection after bonded lookup requests audio and nudge`() = runVmTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bond = mockBondedDevice(testAddress)
        every { bluetoothManager.bondedDevices() } returns flow {
            entered.complete(Unit)
            release.await()
            emit(setOf(bond))
        }
        coEvery { bluetoothManager.nudgeConnection(bond, any()) } returns NudgeAttemptResult.Accepted
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.forceConnect()
        entered.await()
        release.complete(Unit)

        coVerify(exactly = 1) { bluetoothManager.requestAudioConnection(bond, any()) }
        coVerify(exactly = 1) { bluetoothManager.nudgeConnection(bond, any()) }
    }

    @Test
    fun `setDeviceName when no target address is a no-op`() = runVmTest {
        val vm = createViewModel()

        vm.setDeviceName("NewName")

        coVerify(exactly = 0) { aapManager.sendCommand(any(), AapCommand.SetDeviceName("NewName")) }
    }

    @Test
    fun `setDeviceName failure emits SendFailed event`() = runVmTest {
        val failure = IllegalStateException("socket closed")
        coEvery {
            aapManager.sendCommand(testAddress, AapCommand.SetDeviceName("NewName"))
        } throws failure

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setDeviceName("NewName")

        val event = vm.events.first()
        val sendFailed = event.shouldBeInstanceOf<DeviceSettingsViewModel.Event.SendFailed>()
        sendFailed.command shouldBe AapCommand.SetDeviceName("NewName")
        sendFailed.message shouldBe "socket closed"
    }

    @Test
    fun `isClassicallyConnected is true when device address is in connected devices`() = runVmTest {
        connectedDevicesFlow.value = listOf(mockBondedDevice(testAddress))

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().isClassicallyConnected shouldBe true
    }

    @Test
    fun `isClassicallyConnected is false when device address is not in connected devices`() = runVmTest {
        connectedDevicesFlow.value = listOf(mockBondedDevice("XX:XX:XX:XX:XX:XX"))

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().isClassicallyConnected shouldBe false
    }

    @Test
    fun `state emits even when connected devices flow has not emitted yet`() = runVmTest {
        every { bluetoothManager.connectedDevices } returns flow { awaitCancellation() }

        val vm = createViewModel()
        vm.initialize(testAddress)

        val state = withTimeout(1_000) { vm.state.first() }

        state.device?.address shouldBe testAddress
        state.isClassicallyConnected shouldBe false
    }

    @Test
    fun `hasCustomLongPressStemAction is true when either long-press action is assigned`() = runVmTest {
        profilesFlow.value = listOf(
            AppleDeviceProfile(
                id = testAddress,
                label = "Test",
                address = testAddress,
                stemActions = StemActionsConfig(leftLong = StemAction.PlayPause),
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().hasCustomLongPressStemAction shouldBe true
    }

    @Test
    fun `setOnePodMode updates profile and does not send SetNcWithOneAirPod`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setOnePodMode(true)

        coVerify { profilesRepo.updateAppleProfile(testAddress, any()) }
        coVerify(exactly = 0) {
            aapManager.sendCommand(any(), any<AapCommand.SetNcWithOneAirPod>())
        }
    }

    @Test
    fun `setNcWithOneAirPod sends command and does not touch profile`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setNcWithOneAirPod(true)

        coVerify { aapManager.sendCommand(testAddress, AapCommand.SetNcWithOneAirPod(true)) }
        coVerify(exactly = 0) { profilesRepo.updateAppleProfile(any(), any()) }
    }

    @Test
    fun `setNcWithOneAirPod failure emits SendFailed event`() = runVmTest {
        val failure = IllegalStateException("socket closed")
        coEvery {
            aapManager.sendCommand(testAddress, AapCommand.SetNcWithOneAirPod(true))
        } throws failure

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setNcWithOneAirPod(true)

        val event = vm.events.first()
        val sendFailed = event.shouldBeInstanceOf<DeviceSettingsViewModel.Event.SendFailed>()
        sendFailed.command shouldBe AapCommand.SetNcWithOneAirPod(true)
        sendFailed.message shouldBe "socket closed"
    }

    @Test
    fun `offRejectedEvents for current address emits OffModeRejectedByDevice`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        offRejectedFlow.emit(testAddress)

        val event = vm.events.first()
        event shouldBe DeviceSettingsViewModel.Event.OffModeRejectedByDevice
    }

    @Test
    fun `offRejectedEvents for other address is ignored`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        offRejectedFlow.emit("11:22:33:44:55:66")
        // No event should have been emitted — send another recognized event afterward
        // so we can assert that the first emission from vm.events is the later one.
        coEvery {
            aapManager.sendCommand(testAddress, AapCommand.SetNcWithOneAirPod(true))
        } throws IllegalStateException("socket closed")
        vm.setNcWithOneAirPod(true)

        val event = vm.events.first()
        event.shouldBeInstanceOf<DeviceSettingsViewModel.Event.SendFailed>()
    }

    @Test
    fun `setAllowOffOption(true) as Pro sends only SetAllowOffOption`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns true

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setAllowOffOption(true)

        coVerify(exactly = 1) { aapManager.sendCommand(testAddress, AapCommand.SetAllowOffOption(true)) }
        coVerify(exactly = 0) { aapManager.sendCommand(any(), AapCommand.SetListeningModeCycle(0x0E)) }
    }

    @Test
    fun `setAllowOffOption(false) as Pro always sends SetListeningModeCycle + SetAllowOffOption(false) in order`() =
        runVmTest {
            every { upgradeInfoFlow.value.isPro } returns true

            val vm = createViewModel()
            vm.initialize(testAddress)
            vm.state.first()

            vm.setAllowOffOption(false)

            // Fallback cycle mask (0x0F) with OFF bit stripped = 0x0E.
            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                aapManager.sendCommand(testAddress, AapCommand.SetListeningModeCycle(0x0E))
                aapManager.sendCommand(testAddress, AapCommand.SetAllowOffOption(false))
            }
        }

    @Test
    fun `setAllowOffOption as non-Pro sends no commands`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns false

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setAllowOffOption(true)
        vm.setAllowOffOption(false)

        coVerify(exactly = 0) { aapManager.sendCommand(any(), AapCommand.SetAllowOffOption(true)) }
        coVerify(exactly = 0) { aapManager.sendCommand(any(), AapCommand.SetAllowOffOption(false)) }
        coVerify(exactly = 0) { aapManager.sendCommand(any(), AapCommand.SetListeningModeCycle(0x0E)) }
    }

    @Test
    fun `setSleepDetection(true) as Pro sends command`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns true

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setSleepDetection(true)

        coVerify(exactly = 1) { aapManager.sendCommand(testAddress, AapCommand.SetSleepDetection(true)) }
    }

    @Test
    fun `setSleepDetection(true) as non-Pro sends no command`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns false

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setSleepDetection(true)

        coVerify(exactly = 0) { aapManager.sendCommand(any(), any<AapCommand.SetSleepDetection>()) }
    }

    @Test
    fun `state reflects the profile's batteryEstimateEnabled`() = runVmTest {
        profilesFlow.value = listOf(
            AppleDeviceProfile(
                id = testAddress,
                label = "Test",
                address = testAddress,
                batteryEstimateEnabled = false,
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().batteryEstimateEnabled shouldBe false
    }

    @Test
    fun `state derives battery health from learned rates`() = runVmTest {
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
        }
        devicesFlow.value = listOf(device)
        // Rated 6h, learned 3h of runtime (0.333/hr) -> ~50% health.
        drainProfilesFlow.value = mapOf(
            testAddress to DrainProfile(
                model = PodModel.AIRPODS_PRO2.name,
                listeningRates = mapOf(
                    "UNKNOWN/LEFT" to DrainProfile.LearnedRate(
                        fractionPerHour = 1f / 3f,
                        sampleCount = 10,
                        updateCount = 3,
                        updatedAt = java.time.Instant.EPOCH,
                    )
                ),
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().batteryHealth shouldBe BatteryHealth.PerPod(
            left = BatteryHealth.Reading(percent = 50, isPromotable = false),
        )
    }

    @Test
    fun `low runtime on recent evidence raises a warning`() = runVmTest {
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
        }
        devicesFlow.value = listOf(device)
        drainProfilesFlow.value = mapOf(
            testAddress to DrainProfile(
                model = PodModel.AIRPODS_PRO2.name,
                listeningRates = mapOf(
                    "UNKNOWN/LEFT" to DrainProfile.LearnedRate(
                        fractionPerHour = 1f / 3f,
                        sampleCount = 10,
                        updateCount = BatteryHealth.MIN_PROMOTE_UPDATE_COUNT,
                        updatedAt = timeSource.now(),
                    )
                ),
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().batteryRuntimeWarning shouldBe BatteryHealth.SlotReading(
            slot = BatteryHealth.Slot.LEFT,
            reading = BatteryHealth.Reading(percent = 50, isPromotable = true),
        )
    }

    @Test
    fun `low runtime on thin evidence raises no warning`() = runVmTest {
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
        }
        devicesFlow.value = listOf(device)
        drainProfilesFlow.value = mapOf(
            testAddress to DrainProfile(
                model = PodModel.AIRPODS_PRO2.name,
                listeningRates = mapOf(
                    "UNKNOWN/LEFT" to DrainProfile.LearnedRate(
                        fractionPerHour = 1f / 3f,
                        sampleCount = 10,
                        updateCount = BatteryHealth.MIN_PROMOTE_UPDATE_COUNT - 1,
                        updatedAt = timeSource.now(),
                    )
                ),
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        val state = vm.state.first()
        state.batteryHealth?.left?.percent shouldBe 50
        state.batteryRuntimeWarning shouldBe null
    }

    @Test
    fun `the warning follows the per-device estimate toggle`() = runVmTest {
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
        }
        devicesFlow.value = listOf(device)
        drainProfilesFlow.value = mapOf(
            testAddress to DrainProfile(
                model = PodModel.AIRPODS_PRO2.name,
                listeningRates = mapOf(
                    "UNKNOWN/LEFT" to DrainProfile.LearnedRate(
                        fractionPerHour = 1f / 3f,
                        sampleCount = 10,
                        updateCount = BatteryHealth.MIN_PROMOTE_UPDATE_COUNT,
                        updatedAt = timeSource.now(),
                    )
                ),
            )
        )
        profilesFlow.value = listOf(
            AppleDeviceProfile(
                id = testAddress,
                label = "Test",
                address = testAddress,
                batteryEstimateEnabled = false,
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().batteryRuntimeWarning shouldBe null
    }

    @Test
    fun `battery health pending flag is set for rated models without data`() = runVmTest {
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
            every { it.hasSelectedPairedDevice } returns true
        }
        devicesFlow.value = listOf(device)

        val vm = createViewModel()
        vm.initialize(testAddress)

        val state = vm.state.first()
        state.batteryHealth shouldBe null
        state.batteryHealthPending shouldBe true
    }

    @Test
    fun `battery health pending flag stays off without a paired device`() = runVmTest {
        // No paired address -> the listening gate can never open -> "check back later" would lie.
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
            every { it.hasSelectedPairedDevice } returns false
        }
        devicesFlow.value = listOf(device)

        val vm = createViewModel()
        vm.initialize(testAddress)

        vm.state.first().batteryHealthPending shouldBe false
    }

    @Test
    fun `battery health hides when the estimate is disabled for the device`() = runVmTest {
        val device = mockk<PodDevice>(relaxed = true).also {
            every { it.profileId } returns testAddress
            every { it.model } returns PodModel.AIRPODS_PRO2
        }
        devicesFlow.value = listOf(device)
        drainProfilesFlow.value = mapOf(
            testAddress to DrainProfile(
                model = PodModel.AIRPODS_PRO2.name,
                listeningRates = mapOf(
                    "UNKNOWN/LEFT" to DrainProfile.LearnedRate(
                        fractionPerHour = 1f / 3f,
                        sampleCount = 10,
                        updateCount = 3,
                        updatedAt = java.time.Instant.EPOCH,
                    )
                ),
            )
        )
        profilesFlow.value = listOf(
            AppleDeviceProfile(
                id = testAddress,
                label = "Test",
                address = testAddress,
                batteryEstimateEnabled = false,
            )
        )

        val vm = createViewModel()
        vm.initialize(testAddress)

        val state = vm.state.first()
        state.batteryHealth shouldBe null
        state.batteryHealthPending shouldBe false
    }

    @Test
    fun `setBatteryEstimateEnabled updates the profile`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setBatteryEstimateEnabled(false)

        coVerify { profilesRepo.updateAppleProfile(testAddress, any()) }
    }

    @Test
    fun `resetBatteryEstimate resets the estimator for the profile`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.resetBatteryEstimate()

        coVerify { batteryEstimator.reset(testAddress) }
    }

    @Test
    fun `setSleepDetection(false) as non-Pro still sends command`() = runVmTest {
        // Disabling must work regardless of pro status so users who enabled it
        // before a subscription expired can still turn it off.
        every { upgradeInfoFlow.value.isPro } returns false

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setSleepDetection(false)

        coVerify(exactly = 1) { aapManager.sendCommand(testAddress, AapCommand.SetSleepDetection(false)) }
    }

    private fun capturedProfileUpdates(base: AppleDeviceProfile): List<AppleDeviceProfile> {
        val transforms = mutableListOf<(AppleDeviceProfile) -> AppleDeviceProfile>()
        coVerify { profilesRepo.updateAppleProfile(testAddress, capture(transforms)) }
        return transforms.map { it(base) }
    }

    private val baseProfile = AppleDeviceProfile(id = testAddress, label = "Test", address = testAddress)

    @Test
    fun `conversation mode and both values persist independently for non Pro profile`() = runVmTest {
        val saved = FakeDataStoreValue(DeviceProfilesContainer(listOf(baseProfile)))
        val settings = mockk<DeviceProfilesSettings> {
            every { profiles } returns saved.mock
            every { defaultProfileCreated } returns FakeDataStoreValue(true).mock
            every { reactionMigrationDone } returns FakeDataStoreValue(true).mock
            every { hadLegacyReactionData } returns FakeDataStoreValue(false).mock
        }
        profilesRepo = DeviceProfilesRepo(
            scope = backgroundScope,
            context = mockk(relaxed = true),
            generalSettings = mockk(relaxed = true),
            settings = settings,
            deviceStateCache = mockk(relaxed = true),
            batteryDrainStore = mockk(relaxed = true),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setConversationVolumeMode(ConversationVolumeMode.ABSOLUTE_TARGET)
        vm.setConversationVolumeTarget(23)
        vm.setConversationVolumeReduction(65)
        val stored = saved.value.profiles.single() as AppleDeviceProfile
        stored.conversationVolumeMode shouldBe ConversationVolumeMode.ABSOLUTE_TARGET
        stored.conversationVolumeTarget shouldBe 23
        stored.conversationVolumeReduction shouldBe 65

        vm.setConversationVolumeMode(ConversationVolumeMode.RELATIVE_REDUCTION)
        val switched = saved.value.profiles.single() as AppleDeviceProfile
        switched.conversationVolumeMode shouldBe ConversationVolumeMode.RELATIVE_REDUCTION
        switched.conversationVolumeTarget shouldBe 23
        switched.conversationVolumeReduction shouldBe 65
    }

    @Test
    fun `setNotifyWhenCaseLow(true) as non-Pro opens upgrade and keeps the profile`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns false

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setNotifyWhenCaseLow(true)

        vm.navEvents.first().shouldBeInstanceOf<NavEvent.GoTo>().destination shouldBe Nav.Main.Upgrade()
        coVerify(exactly = 0) { profilesRepo.updateAppleProfile(any(), any()) }
    }

    @Test
    fun `setNotifyWhenCaseLow(true) as Pro enables the reminder`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns true

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setNotifyWhenCaseLow(true)

        capturedProfileUpdates(baseProfile).single().notifyWhenCaseLow shouldBe true
    }

    @Test
    fun `setNotifyWhenCaseLow(false) as non-Pro disables the reminder without upgrade`() = runVmTest {
        every { upgradeInfoFlow.value.isPro } returns false

        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setNotifyWhenCaseLow(false)

        capturedProfileUpdates(baseProfile.copy(notifyWhenCaseLow = true))
            .single().notifyWhenCaseLow shouldBe false
        withTimeoutOrNull(1_000) { vm.navEvents.first() }.shouldBeNull()
    }

    @Test
    fun `setCaseLowThreshold snaps to the step and clamps to the range`() = runVmTest {
        val vm = createViewModel()
        vm.initialize(testAddress)
        vm.state.first()

        vm.setCaseLowThreshold(24)
        vm.setCaseLowThreshold(26)
        vm.setCaseLowThreshold(5)
        vm.setCaseLowThreshold(90)

        capturedProfileUpdates(baseProfile).map { it.caseLowThreshold } shouldBe listOf(20, 30, 10, 50)
    }
}
