package eu.darken.capod.main.ui.settings.general

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.theming.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class GeneralSettingsHelpScreenTest : BaseComposeRobolectricTest() {

    @Test fun `bottom popup remains available while top popup is removed`() {
        composeRule.setContent { PreviewWrapper {
            GeneralSettingsScreen(state = state(), onNavigateUp = {},
                onShowConnectedNotificationChanged = {}, onKeepNotificationAfterDisconnectChanged = {},
                onOffloadedFilteringDisabledChanged = {}, onOffloadedBatchingDisabledChanged = {},
                onUseIndirectScanResultCallbackChanged = {}, onHideUnmatchedDevicesChanged = {})
        } }
        scrollTo("Bottom popup")
        composeRule.onNodeWithText("Bottom popup").assertExists()
        composeRule.onNodeWithText("Top status popup").assertDoesNotExist()
    }

    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun state() = GeneralSettingsViewModel.State(
        isUpgradeLocked = false,
        showConnectedNotification = true,
        keepNotificationAfterDisconnect = false,
        showBatteryInStatusBar = false,
        isOffloadedFilteringDisabled = false,
        isOffloadedBatchingDisabled = true,
        useIndirectScanResultCallback = true,
        hideUnmatchedDevices = false,
        themeState = ThemeState(),
    )

    @Test
    fun `normal settings keep compatibility control and expose both help entries`() {
        composeRule.setContent {
            PreviewWrapper {
                GeneralSettingsScreen(
                    state = state(),
                    onNavigateUp = {},
                    onShowConnectedNotificationChanged = {},
                    onKeepNotificationAfterDisconnectChanged = {},
                    onOffloadedFilteringDisabledChanged = {},
                    onOffloadedBatchingDisabledChanged = {},
                    onUseIndirectScanResultCallbackChanged = {},
                    onHideUnmatchedDevicesChanged = {},
                )
            }
        }

        scrollTo("Background running help")
        composeRule.onNodeWithText("Background running help").assertExists()
        scrollTo("Find AirPods")
        composeRule.onNodeWithText("Find AirPods").assertExists()
        scrollTo("Indirect data delivery")
        composeRule.onNodeWithText("Indirect data delivery").assertExists()
    }

    @Test
    fun `opening and dismissing help does not change saved scan preferences`() {
        var writes = 0
        composeRule.setContent {
            PreviewWrapper {
                GeneralSettingsScreen(
                    state = state(),
                    onNavigateUp = {},
                    onShowConnectedNotificationChanged = {},
                    onKeepNotificationAfterDisconnectChanged = {},
                    onOffloadedFilteringDisabledChanged = { writes++ },
                    onOffloadedBatchingDisabledChanged = { writes++ },
                    onUseIndirectScanResultCallbackChanged = { writes++ },
                    onHideUnmatchedDevicesChanged = {},
                )
            }
        }

        scrollTo("Background running help")
        composeRule.onNodeWithText("Background running help").performClick()
        composeRule.onNodeWithText("OPPO / ColorOS example: App details → Battery usage → Fully allow background activity")
            .assertExists()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("OPPO / ColorOS example: App details → Battery usage → Fully allow background activity")
            .assertDoesNotExist()
        scrollTo("Find AirPods")
        composeRule.onNodeWithText("Find AirPods").performClick()
        composeRule.onNodeWithText("Open Apple Find Devices").assertExists()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.runOnIdle { assertEquals(0, writes) }
    }

    @Test
    fun `failed launches show localized feedback while dialogs remain dismissible`() {
        composeRule.setContent {
            PreviewWrapper {
                GeneralSettingsScreen(
                    state = state(),
                    onNavigateUp = {},
                    onShowConnectedNotificationChanged = {},
                    onKeepNotificationAfterDisconnectChanged = {},
                    onOffloadedFilteringDisabledChanged = {},
                    onOffloadedBatchingDisabledChanged = {},
                    onUseIndirectScanResultCallbackChanged = {},
                    onHideUnmatchedDevicesChanged = {},
                    onOpenAppDetails = { false },
                    onOpenFindDevices = { false },
                )
            }
        }

        scrollTo("Background running help")
        composeRule.onNodeWithText("Background running help").performClick()
        composeRule.onNodeWithText("Open app details").performClick()
        composeRule.onNodeWithText("Could not open app details.").assertExists()
        composeRule.onNodeWithText("Close").performClick()
        scrollTo("Find AirPods")
        composeRule.onNodeWithText("Find AirPods").performClick()
        composeRule.onNodeWithText("Open Apple Find Devices").performClick()
        composeRule.onNodeWithText("Could not open Apple Find Devices.").assertExists()
        composeRule.onNodeWithText("Close").performClick()
    }
}
