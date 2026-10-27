package eu.darken.capod.reaction.ui.popup

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import org.junit.Test
import org.junit.Assert.assertEquals
import testhelpers.compose.BaseComposeRobolectricTest

class TopPopUpContentTest : BaseComposeRobolectricTest() {
    @Test fun `real labels zero missing and charging are exposed`() {
        composeRule.setContent { PreviewWrapper {
            TopPopUpContent(MockPodDataProvider.dualPodBatteries(left = 0f, right = Float.NaN, case = 1.2f), {})
        } }
        composeRule.onNodeWithText("Left: 0%").assertIsDisplayed()
        composeRule.onNodeWithText("Right: —").assertIsDisplayed()
        composeRule.onNodeWithText("Case: —").assertIsDisplayed()
        composeRule.onNodeWithText("120%", substring = true).assertDoesNotExist()
    }
    @Test fun `control channel readiness alone remains detected`() {
        composeRule.setContent { PreviewWrapper { TopPopUpContent(MockPodDataProvider.dualPodMonitoredWithAap(), {}) } }
        composeRule.onNodeWithText("Device detected").assertIsDisplayed()
        composeRule.onNodeWithText("Audio connected").assertDoesNotExist()
    }
    @Test fun `system connected wording and local close action`() {
        var closes = 0
        composeRule.setContent { PreviewWrapper {
            TopPopUpContent(MockPodDataProvider.dualPodMonitoredMixed().copy(isSystemConnected = true), { closes++ })
        } }
        composeRule.onNodeWithText("Audio connected").assertIsDisplayed()
        composeRule.onNodeWithText("Left: 80% · Charging").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closes)
    }
    @Test fun `single model has headset value without invented pair or case`() {
        composeRule.setContent { PreviewWrapper { TopPopUpContent(MockPodDataProvider.singlePodBattery(.2f), {}) } }
        composeRule.onNodeWithText("Headphones: 20%").assertIsDisplayed()
        composeRule.onNodeWithText("Left", substring = true).assertDoesNotExist()
    }
    @Test fun `unknown model keeps honest explanation and close`() {
        composeRule.setContent { PreviewWrapper { TopPopUpContent(MockPodDataProvider.unknownMonitored(), {}) } }
        composeRule.onNodeWithText("Battery information is not available for this device.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }
    @Test fun `narrow landscape large font keeps close and scrolls to real batteries`() {
        composeRule.setContent { PreviewWrapper {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                TopPopUpContent(MockPodDataProvider.dualPodMonitoredMixed(), {}, Modifier.width(240.dp).heightIn(max = 200.dp))
            }
        } }
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
        composeRule.onNodeWithText("Left: 80% · Charging").performScrollTo().assertIsDisplayed()
    }
}
