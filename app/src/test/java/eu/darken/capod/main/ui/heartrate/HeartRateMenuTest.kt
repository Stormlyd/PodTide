package eu.darken.capod.main.ui.heartrate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsScreen
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsViewModel
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.reaction.core.heartrate.HeartRateUiState
import eu.darken.capod.reaction.core.headgesture.HeadGestureConfig
import eu.darken.capod.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import testhelpers.compose.BaseComposeRobolectricTest

@Config(qualifiers = "zh-rCN")
class HeartRateMenuTest : BaseComposeRobolectricTest() {
    @Test fun `public release hides sensors on saved Pro3 profile before any current detection`() {
        var opened = false
        composeRule.setContent {
            PreviewWrapper {
                DeviceSettingsScreen(
                    DeviceSettingsViewModel.State(device = null, profileModel = PodModel.AIRPODS_PRO3),
                    onNavigateUp = {}, onHeartRateClick = { opened = true },
                )
            }
        }
        assertEntryUnavailable(hasTestTag("heart_rate_entry"))
        assertEntryUnavailable(hasTestTag("head_preview_entry"))
        assertTrue(!opened)
    }

    @Test fun `public release hides sensors on paired Pro3 while disconnected`() {
        var opened = false
        composeRule.setContent {
            PreviewWrapper {
                DeviceSettingsScreen(
                    DeviceSettingsViewModel.State(device = PodDevice("owned", ble = null, aap = null,
                        profileAddress = "AA:BB:CC:DD:EE:FF", profileModel = PodModel.AIRPODS_PRO3)),
                    onNavigateUp = {}, onHeartRateClick = { opened = true },
                )
            }
        }
        assertEntryUnavailable(hasTestTag("heart_rate_entry"))
        assertEntryUnavailable(hasTestTag("head_preview_entry"))
        assertTrue(!opened)
    }

    @Test fun `public release hides enabled head gestures while keeping normal connected reactions`() {
        composeRule.setContent { PreviewWrapper {
            DeviceSettingsScreen(
                DeviceSettingsViewModel.State(
                    device = PodDevice("owned", ble = null, aap = null,
                        profileAddress = "AA:BB:CC:DD:EE:FF", profileModel = PodModel.AIRPODS_PRO3,
                        isSystemConnected = true),
                    isClassicallyConnected = true, isPro = true,
                ),
                onNavigateUp = {}, headGestureConfig = HeadGestureConfig(enabled = true, media = true),
            )
        } }
        assertEntryUnavailable(hasText(RuntimeEnvironment.getApplication().getString(R.string.head_gestures_title)))
        assertEntryUnavailable(hasTestTag("heart_rate_entry"))
        assertEntryUnavailable(hasTestTag("head_preview_entry"))
        val autoConnect = RuntimeEnvironment.getApplication().getString(R.string.settings_autoconnect_label)
        composeRule.onNodeWithTag("device_settings_list").performScrollToNode(hasText(autoConnect))
        composeRule.onNodeWithText(autoConnect).assertIsDisplayed()
    }

    @Test fun `public release hides head preview on saved Pro2 profile`() {
        composeRule.setContent { PreviewWrapper {
            DeviceSettingsScreen(
                DeviceSettingsViewModel.State(device = null, profileModel = PodModel.AIRPODS_PRO2),
                onNavigateUp = {},
            )
        } }
        assertEntryUnavailable(hasTestTag("head_preview_entry"))
    }

    /** Search the entire lazy list so an off-screen entry cannot satisfy an absence assertion. */
    private fun assertEntryUnavailable(matcher: SemanticsMatcher) {
        try {
            composeRule.onNodeWithTag("device_settings_list").performScrollToNode(matcher)
        } catch (missing: AssertionError) {
            assertTrue(missing.message.orEmpty().contains("No node found"))
        }
        composeRule.onNode(matcher).assertDoesNotExist()
    }

    @Test fun `dedicated Chinese page exposes monitoring controls and a back action`() {
        var started = false
        var backed = false
        composeRule.setContent {
            PreviewWrapper {
                HeartRateScreen(HeartRateUiState(), { backed = true }, { started = true }, {}, {})
            }
        }
        composeRule.onNodeWithText("心率监测").assertIsDisplayed()
        composeRule.onNodeWithText("开始采集").performClick()
        assertTrue(started)
        composeRule.onNodeWithTag("heart_rate_back").performClick()
        assertTrue(backed)
    }
}
