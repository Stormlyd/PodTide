package eu.darken.capod.main.ui.settings.general

import androidx.compose.ui.test.*
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.theming.ThemeState
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.compose.BaseComposeRobolectricTest

@Config(qualifiers = "zh-rCN")
class GeneralSettingsPopupChineseTest : BaseComposeRobolectricTest() {
    @Test fun `Chinese popup controls and shared trigger explanation are visible`() {
        composeRule.setContent { PreviewWrapper {
            GeneralSettingsScreen(
                state = GeneralSettingsViewModel.State(false, true, false, false, false, false, false, false, ThemeState()),
                onNavigateUp = {}, onShowConnectedNotificationChanged = {}, onKeepNotificationAfterDisconnectChanged = {},
                onOffloadedFilteringDisabledChanged = {}, onOffloadedBatchingDisabledChanged = {},
                onUseIndirectScanResultCallbackChanged = {}, onHideUnmatchedDevicesChanged = {},
            )
        } }
        for (text in listOf("弹窗样式", "两种样式均使用各设备设置中的开盒和连接触发条件。", "底部弹窗", "顶部状态弹窗")) {
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
    }
}
