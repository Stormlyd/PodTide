package eu.darken.capod.main.ui.headpreview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsScreen
import eu.darken.capod.main.ui.devicesettings.DeviceSettingsViewModel
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.reaction.core.headgesture.HeadPreviewUiState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.compose.BaseComposeRobolectricTest

@Config(qualifiers = "zh-rCN")
class HeadPreviewMenuTest : BaseComposeRobolectricTest() {
    @Test fun `saved offline Pro3 profile opens head preview`() {
        var opened = false
        composeRule.setContent { PreviewWrapper {
            DeviceSettingsScreen(DeviceSettingsViewModel.State(device = null, profileModel = PodModel.AIRPODS_PRO3),
                onNavigateUp = {}, onHeadPreviewClick = { opened = true })
        } }
        composeRule.onNodeWithTag("device_settings_list").performScrollToNode(hasTestTag("head_preview_entry"))
        composeRule.onNodeWithTag("head_preview_entry").assertIsDisplayed().performClick()
        assertTrue(opened)
    }

    @Test fun `Chinese preview page has explicit safe controls`() {
        var started = false
        var backed = false
        composeRule.setContent { PreviewWrapper {
            HeadPreviewScreen(HeadPreviewUiState(), { backed = true }, { started = true }, {}, {}, {}, {})
        } }
        composeRule.onNodeWithText("头部姿态与手势测试").assertIsDisplayed()
        composeRule.onNodeWithText("开始预览").performClick()
        assertTrue(started)
        composeRule.onNodeWithTag("head_preview_back").performClick()
        assertTrue(backed)
    }
}
