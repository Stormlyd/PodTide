package eu.darken.capod.main.ui.devicesettings.dialogs

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.upgrade.UpgradeRepo
import eu.darken.capod.common.WebpageTool
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.bluetooth.NudgeCapabilityStore
import eu.darken.capod.main.core.MonitorMode
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsViewModel
import eu.darken.capod.main.ui.devicesettings.cards.ReactionsCard
import eu.darken.capod.main.ui.devicesettings.previewFullState
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.MonitorModeResolver
import eu.darken.capod.monitor.core.battery.BatteryDrainStore
import eu.darken.capod.monitor.core.battery.BatteryEstimator
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesContainer
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import eu.darken.capod.profiles.core.DeviceProfilesSettings
import eu.darken.capod.profiles.core.toReactionConfig
import eu.darken.capod.reaction.core.autoconnect.AutoConnectCondition
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import testhelpers.TestTimeSource
import testhelpers.compose.BaseComposeRobolectricTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.datastore.FakeDataStoreValue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectConditionPersistenceTest : BaseComposeRobolectricTest() {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val initial = AppleDeviceProfile(
        id = ADDRESS, label = "Pods", address = ADDRESS,
        autoConnect = true, autoConnectCondition = AutoConnectCondition.WHEN_SEEN,
    )
    private val saved = FakeDataStoreValue(DeviceProfilesContainer(listOf(initial)))
    private lateinit var vm: DeviceSettingsViewModel

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        if (::vm.isInitialized) vm.vmScope.cancel()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `condition selected from device settings is saved and selected again after reopening`() {
        val settings = mockk<DeviceProfilesSettings> {
            every { profiles } returns saved.mock
            every { defaultProfileCreated } returns FakeDataStoreValue(true).mock
            every { reactionMigrationDone } returns FakeDataStoreValue(true).mock
            every { hadLegacyReactionData } returns FakeDataStoreValue(false).mock
        }
        val repo = DeviceProfilesRepo(
            scope = scope,
            context = mockk(relaxed = true),
            generalSettings = mockk(relaxed = true),
            settings = settings,
            deviceStateCache = mockk(relaxed = true),
            batteryDrainStore = mockk(relaxed = true),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        val aap = mockk<AapConnectionManager>(relaxed = true) {
            every { offRejectedEvents } returns MutableSharedFlow()
            every { settingRejectedEvents } returns MutableSharedFlow()
        }
        vm = DeviceSettingsViewModel(
            dispatcherProvider = TestDispatcherProvider(dispatcher),
            deviceMonitor = mockk<DeviceMonitor>(relaxed = true),
            aapManager = aap,
            upgradeRepo = mockk<UpgradeRepo>(relaxed = true),
            bluetoothManager = mockk<BluetoothManager2>(relaxed = true),
            profilesRepo = repo,
            batteryEstimator = mockk<BatteryEstimator>(relaxed = true),
            drainStore = mockk<BatteryDrainStore>(relaxed = true),
            monitorModeResolver = mockk<MonitorModeResolver> {
                every { effectiveMode } returns MutableStateFlow(MonitorMode.AUTOMATIC)
            },
            nudgeCapabilityStore = mockk<NudgeCapabilityStore>(relaxed = true),
            timeSource = TestTimeSource(),
            webpageTool = mockk<WebpageTool>(relaxed = true),
        )
        vm.initialize(ADDRESS)

        composeRule.setContent { PreviewWrapper {
            val profiles by repo.profiles.collectAsState(initial = listOf(initial))
            val current = profiles.single() as AppleDeviceProfile
            ReactionsCard(
                device = previewFullState(isPro = false).device!!.copy(reactions = current.toReactionConfig()),
                features = PodModel.Features(hasCase = true),
                isPro = false,
                onAutoConnectConditionChange = vm::setAutoConnectCondition,
            )
        } }

        composeRule.onNodeWithText("Auto connect condition").performClick()
        composeRule.onNode(
            hasText("When seen") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        ).assertIsSelected()
        (saved.value.profiles.single() as AppleDeviceProfile).autoConnectCondition shouldBe AutoConnectCondition.WHEN_SEEN

        composeRule.onNodeWithText("Case is open").performClick()
        composeRule.waitUntil(3_000) {
            (saved.value.profiles.single() as AppleDeviceProfile).autoConnectCondition == AutoConnectCondition.CASE_OPEN
        }
        composeRule.onNodeWithText("Auto connect condition").performClick()
        composeRule.onNode(
            hasText("Case is open") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        ).assertIsSelected()
        (saved.value.profiles.single() as AppleDeviceProfile).autoConnectCondition shouldBe AutoConnectCondition.CASE_OPEN
    }

    private companion object { const val ADDRESS = "AA:BB:CC:DD:EE:FF" }
}
