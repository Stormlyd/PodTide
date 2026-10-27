package eu.darken.capod.main.ui.devicesettings.dialogs

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.reaction.core.autoconnect.AutoConnectCondition
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.compose.BaseComposeRobolectricTest

@Config(qualifiers = "w320dp-h420dp")
class AutoConnectConditionDialogTest : BaseComposeRobolectricTest() {
    @Test
    fun `guidance explains each condition and selection only changes on explicit tap`() {
        val selected = mutableListOf<AutoConnectCondition>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) {
                PreviewWrapper {
                    AutoConnectConditionDialog(
                        current = AutoConnectCondition.WHEN_SEEN,
                        hasEarDetection = true,
                        hasCase = true,
                        onSelect = { selected += it },
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("When seen").assertIsSelected()
        composeRule.runOnIdle { check(selected.isEmpty()) }
        composeRule.onNodeWithText("Recommended", substring = true).assertExists()
        composeRule.onNodeWithText("Background listening continues while the case is closed", substring = true).assertExists()
        composeRule.onNode(hasScrollAction()).assertExists()
        composeRule.onNodeWithText("In ear").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Case is open").performClick()
        composeRule.runOnIdle { check(selected == listOf(AutoConnectCondition.CASE_OPEN)) }
    }

    @Test
    fun `unsupported options are absent without changing the saved condition`() {
        val selected = mutableListOf<AutoConnectCondition>()
        composeRule.setContent { PreviewWrapper {
            AutoConnectConditionDialog(
                current = AutoConnectCondition.WHEN_SEEN,
                hasEarDetection = false,
                hasCase = false,
                onSelect = { selected += it },
                onDismiss = {},
            )
        } }
        composeRule.onNodeWithText("When seen").assertIsSelected()
        composeRule.onNodeWithText("Case is open").assertDoesNotExist()
        composeRule.onNodeWithText("In ear").assertDoesNotExist()
        composeRule.runOnIdle { check(selected.isEmpty()) }
    }
}

@Config(qualifiers = "zh-rCN-w320dp-h420dp")
class AutoConnectConditionDialogChineseTest : BaseComposeRobolectricTest() {
    @Test
    fun `recommended condition and closed-case listening guidance are localized`() {
        composeRule.setContent { PreviewWrapper {
            AutoConnectConditionDialog(
                current = AutoConnectCondition.CASE_OPEN,
                hasEarDetection = true,
                hasCase = true,
                onSelect = {},
                onDismiss = {},
            )
        } }
        composeRule.onNodeWithText("推荐", substring = true).assertExists()
        composeRule.onNodeWithText("充电盒关闭时仍会继续后台监听", substring = true).assertExists()
    }
}
