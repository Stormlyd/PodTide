package eu.darken.capod.main.ui.devicesettings.cards

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsScreen
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsViewModel
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapPodState
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest
import org.robolectric.annotation.Config

@Config(qualifiers = "zh-rCN")
class CustomEqIntegrationTest : BaseComposeRobolectricTest() {
    @Test
    fun `paired Pro3 exposes experimental free three band EQ even before firmware readback`() {
        composeRule.setContent {
            PreviewWrapper {
                DeviceSettingsScreen(
                    state = DeviceSettingsViewModel.State(device = PodDevice(
                        profileId = "test", ble = null,
                        aap = AapPodState(connectionState = AapPodState.ConnectionState.READY),
                        profileAddress = "AA:BB:CC:DD:EE:FF", profileModel = PodModel.AIRPODS_PRO3,
                    ), isPro = false, isClassicallyConnected = true),
                    onNavigateUp = {},
                )
            }
        }
        composeRule.onNodeWithTag("device_settings_list")
            .performScrollToNode(hasText("三频段均衡器 · 实验功能"))
        composeRule.onNodeWithText("三频段均衡器 · 实验功能").assertIsDisplayed()
    }
}
