package eu.darken.capod.main.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import eu.darken.capod.R
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.common.navigation.Nav
import eu.darken.capod.common.navigation.NavigationController
import eu.darken.capod.common.theming.ThemeState
import eu.darken.capod.main.ui.onboarding.OnboardingScreen
import eu.darken.capod.main.ui.settings.general.GeneralSettingsScreen
import eu.darken.capod.main.ui.settings.general.GeneralSettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class PodTideFreeExperienceTest : BaseComposeRobolectricTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `legacy upgrade pages are removed and cannot be reopened`() {
        val controller = NavigationController()
        lateinit var stack: NavBackStack<NavKey>
        composeRule.setContent {
            stack = rememberNavBackStack(Nav.Main.Upgrade())
            controller.setup(stack)
        }
        composeRule.runOnIdle {
            assertEquals(listOf(Nav.Main.Overview), stack.toList())
            controller.goTo(Nav.Main.Upgrade())
            assertEquals(listOf(Nav.Main.Overview), stack.toList())
            assertFalse(controller.up())
        }
    }

    @Test fun `welcome does not promote a paid upgrade`() {
        composeRule.setContent { PreviewWrapper { OnboardingScreen(onPrivacyPolicy = {}, onContinue = {}) } }
        composeRule.onNodeWithText(context.getString(R.string.onboarding_body4)).assertDoesNotExist()
    }

    @Test fun `theme controls remain available even with stale legacy entitlement state`() {
        var upgradeTaps = 0
        composeRule.setContent { PreviewWrapper {
            GeneralSettingsScreen(
                state = GeneralSettingsViewModel.State(isUpgradeLocked = true, showConnectedNotification = false,
                    keepNotificationAfterDisconnect = false, showBatteryInStatusBar = false,
                    isOffloadedFilteringDisabled = false, isOffloadedBatchingDisabled = false,
                    useIndirectScanResultCallback = false, hideUnmatchedDevices = false, themeState = ThemeState()),
                onNavigateUp = {}, onShowConnectedNotificationChanged = {}, onKeepNotificationAfterDisconnectChanged = {},
                onOffloadedFilteringDisabledChanged = {}, onOffloadedBatchingDisabledChanged = {},
                onUseIndirectScanResultCallbackChanged = {}, onHideUnmatchedDevicesChanged = {},
                onUpgrade = { upgradeTaps++ },
            )
        } }
        composeRule.onNodeWithText(context.getString(R.string.ui_theme_mode_label)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(0, upgradeTaps) }
        composeRule.onNodeWithText(context.getString(R.string.common_upgrade_required_label), substring = true).assertDoesNotExist()
    }
}
