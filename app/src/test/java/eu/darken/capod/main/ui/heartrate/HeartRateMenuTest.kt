package eu.darken.capod.main.ui.heartrate

import androidx.compose.runtime.mutableStateOf
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
import eu.darken.capod.reaction.core.heartrate.HeartRateSample
import eu.darken.capod.reaction.core.heartrate.HeartRateReading
import eu.darken.capod.reaction.core.heartrate.HeartRateStatus
import eu.darken.capod.reaction.core.heartrate.HeartRateController
import eu.darken.capod.main.ui.devicesettings.cards.HeartRateViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import eu.darken.capod.reaction.core.headgesture.HeadGestureConfig
import eu.darken.capod.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import testhelpers.compose.BaseComposeRobolectricTest

@Config(qualifiers = "zh-rCN")
class HeartRateMenuTest : BaseComposeRobolectricTest() {
    @Test fun `Pro3 saved profile offers heart rate even while offline`() {
        var opened = false
        composeRule.setContent {
            PreviewWrapper {
                DeviceSettingsScreen(
                    DeviceSettingsViewModel.State(device = null, profileModel = PodModel.AIRPODS_PRO3),
                    onNavigateUp = {}, onHeartRateClick = { opened = true },
                )
            }
        }
        composeRule.onNodeWithTag("device_settings_list").performScrollToNode(hasTestTag("heart_rate_entry"))
        composeRule.onNodeWithTag("heart_rate_entry").performClick()
        assertEntryUnavailable(hasTestTag("head_preview_entry"))
        assertTrue(opened)
    }

    @Test fun `paired Pro3 offers heart rate while disconnected`() {
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
        composeRule.onNodeWithTag("device_settings_list").performScrollToNode(hasTestTag("heart_rate_entry"))
        composeRule.onNodeWithTag("heart_rate_entry").performClick()
        assertEntryUnavailable(hasTestTag("head_preview_entry"))
        assertTrue(opened)
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
        composeRule.onNodeWithTag("device_settings_list").performScrollToNode(hasTestTag("heart_rate_entry"))
        composeRule.onNodeWithTag("heart_rate_entry").assertIsDisplayed()
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

    @Test fun `entering and leaving heart page starts and stops the collector`() {
        val showing = mutableStateOf(true)
        var starts = 0
        var stops = 0
        var collections = 0
        var cancellations = 0
        val controller = mockk<HeartRateController> {
            every { states } returns MutableStateFlow(emptyMap())
            every { state("owned") } returns HeartRateUiState()
            every { start("owned") } answers { starts++ }
            every { stop("owned") } answers { stops++ }
            every { monitor() } returns flow {
                collections++
                try { awaitCancellation() } finally { cancellations++ }
            }
        }
        val vm = HeartRateViewModel(controller)
        composeRule.setContent { PreviewWrapper { if (showing.value) HeartRateScreenHost("owned", vm) } }
        composeRule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, collections)
            showing.value = false
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, cancellations)
            assertEquals(1, stops)
        }
    }

    @Test fun `waiting shows measuring without redundant status text`() {
        composeRule.setContent { PreviewWrapper {
            HeartRateScreen(HeartRateUiState(requested = true, reading = HeartRateReading(HeartRateStatus.WAITING)), {}, {}, {}, {})
        } }
        composeRule.onNodeWithText("正在测量").assertIsDisplayed()
        composeRule.onNodeWithText("— 次/分").assertDoesNotExist()
        composeRule.onNodeWithText("采集请求已发送，等待耳机返回有效读数。").assertDoesNotExist()
    }

    @Test fun `heart history chart shows arithmetic mean and maximum of recorded samples`() {
        val history = listOf(HeartRateSample(1_000, 60), HeartRateSample(6_000, 80), HeartRateSample(11_000, 100))
        composeRule.setContent { PreviewWrapper {
            HeartRateScreen(HeartRateUiState(requested = true, reading = HeartRateReading(HeartRateStatus.ACTIVE, 80), history = history), {}, {}, {}, {})
        } }
        composeRule.onNodeWithText("80 次/分").assertIsDisplayed()
        composeRule.onNodeWithText("正在接收耳机读数。").assertDoesNotExist()
        composeRule.onNodeWithTag("heart_rate_chart").assertIsDisplayed()
        composeRule.onNodeWithText("平均 80 次/分").assertExists()
        composeRule.onNodeWithText("最高 100 次/分").assertExists()
    }

    @Test fun `one reading still produces a chart and both annotations`() {
        composeRule.setContent { PreviewWrapper {
            HeartRateScreen(HeartRateUiState(history = listOf(HeartRateSample(1_000, 72))), {}, {}, {}, {})
        } }
        composeRule.onNodeWithTag("heart_rate_chart").assertIsDisplayed()
        composeRule.onNodeWithText("平均 72 次/分").assertExists()
        composeRule.onNodeWithText("最高 72 次/分").assertExists()
    }

    @Test fun `stale readings still explain the problem instead of showing an old value`() {
        composeRule.setContent { PreviewWrapper {
            HeartRateScreen(HeartRateUiState(requested = true, reading = HeartRateReading(HeartRateStatus.STALE)), {}, {}, {}, {})
        } }
        composeRule.onNodeWithText("正在测量").assertIsDisplayed()
        composeRule.onNodeWithText("超过 10 秒没有新读数，请检查佩戴和连接。").assertIsDisplayed()
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
        composeRule.onNodeWithText("开始采集").assertDoesNotExist()
        assertTrue(!started)
        composeRule.onNodeWithTag("heart_rate_back").performClick()
        assertTrue(backed)
    }
}
