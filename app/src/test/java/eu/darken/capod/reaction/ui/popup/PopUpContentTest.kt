package eu.darken.capod.reaction.ui.popup

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class PopUpContentTest : BaseComposeRobolectricTest() {
    @Test fun `popup offers a click action to open the app`() {
        var opens = 0
        var closes = 0
        composeRule.setContent { PreviewWrapper { PopUpContent(MockPodDataProvider.dualPodMonitoredMixed().copy(isSystemConnected = true), { closes++ }, onOpen = { opens++ }) } }
        composeRule.onNodeWithText("Audio connected").assertHasClickAction().performClick()
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test fun `dual pods expose labels and missing values honestly`() {
        composeRule.setContent { PreviewWrapper {
            PopUpContent(MockPodDataProvider.dualPodBatteries(left = Float.NaN, right = 1.2f, case = .6f), {})
        } }
        composeRule.onNodeWithContentDescription("Left, Battery unavailable").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Case, 60%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Right, Battery unavailable").assertIsDisplayed()

        composeRule.onNodeWithText("120%").assertDoesNotExist()
    }
    @Test fun `observed system connection supplies connected wording`() {
        composeRule.setContent { PreviewWrapper {
            PopUpContent(MockPodDataProvider.dualPodMonitoredMixed().copy(isSystemConnected = true), {})
        } }
        composeRule.onNodeWithText("Audio connected").assertIsDisplayed()
    }
    @Test fun `control channel readiness alone is device detected`() {
        composeRule.setContent { PreviewWrapper { PopUpContent(MockPodDataProvider.dualPodMonitoredWithAap(), {}) } }
        composeRule.onNodeWithText("Device detected").assertIsDisplayed()
        composeRule.onNodeWithText("Audio connected").assertDoesNotExist()
    }
    @Test fun `single model exposes one honest battery group`() {
        composeRule.setContent { PreviewWrapper { PopUpContent(MockPodDataProvider.singlePodBattery(percent = .2f), {}) } }
        composeRule.onNodeWithContentDescription("Headphones, 20%").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Case", substring = true).assertDoesNotExist()
    }
    @Test fun `unknown model retains close and unavailable explanation`() {
        composeRule.setContent { PreviewWrapper { PopUpContent(MockPodDataProvider.unknownMonitored(), {}) } }
        composeRule.onNodeWithText("Battery information is not available for this device.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
    @Test fun `large font uses scrollable intact battery rows and keeps close visible`() {
        composeRule.setContent { PreviewWrapper {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.8f)) {
                PopUpContent(MockPodDataProvider.dualPodMonitoredMixed(), {}, androidx.compose.ui.Modifier.widthIn(max = 320.dp))
            }
        } }
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Left, 80%, Charging").assertIsDisplayed()
    }}
