package eu.darken.capod.main.ui.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class PhoneBatteryConfigurationScreenTest : BaseComposeRobolectricTest() {
    @Test fun `battery config switch previews phone and calls draft callback`() {
        val enabled = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                WidgetConfigurationScreen(
                    state = WidgetConfigurationViewModel.State(
                        isPro = true, showPhoneBattery = enabled.value,
                    ),
                    onSelectProfile = {}, onSelectPreset = {}, onEnterCustomMode = { _, _ -> },
                    onSetBackgroundColor = {}, onSetForegroundColor = {},
                    onSetBackgroundAlpha = {}, onSetShowDeviceLabel = {},
                    onSetShowPhoneBattery = { enabled.value = it },
                    onReset = {}, onConfirm = {}, onCancel = {},
                )
            }
        }
        composeRule.onNodeWithTag("widget_show_phone_battery").assertExists().performScrollTo().performClick()
        composeRule.waitForIdle()
        enabled.value shouldBe true
        composeRule.onNodeWithText("74%").assertExists()
    }

    @Test fun `anc config has no phone switch or phone preview`() {
        composeRule.setContent {
            MaterialTheme {
                WidgetConfigurationScreen(
                    state = WidgetConfigurationViewModel.State(
                        isPro = true, isAncWidget = true, showPhoneBattery = true,
                    ),
                    onSelectProfile = {}, onSelectPreset = {}, onEnterCustomMode = { _, _ -> },
                    onSetBackgroundColor = {}, onSetForegroundColor = {},
                    onSetBackgroundAlpha = {}, onSetShowDeviceLabel = {},
                    onReset = {}, onConfirm = {}, onCancel = {},
                )
            }
        }
        composeRule.onNodeWithTag("widget_show_phone_battery").assertDoesNotExist()
        composeRule.onNodeWithText("74%").assertDoesNotExist()
    }

    @Test fun `chinese configuration and preview use localized phone label`() {
        val base: Context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
        val zhContext = base.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides zhContext) {
                MaterialTheme {
                    WidgetConfigurationScreen(
                        state = WidgetConfigurationViewModel.State(
                            isPro = true, showPhoneBattery = true,
                        ),
                        onSelectProfile = {}, onSelectPreset = {}, onEnterCustomMode = { _, _ -> },
                        onSetBackgroundColor = {}, onSetForegroundColor = {},
                        onSetBackgroundAlpha = {}, onSetShowDeviceLabel = {},
                        onReset = {}, onConfirm = {}, onCancel = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("显示手机电量").assertExists()
        composeRule.onNodeWithText("手机").assertExists()
        composeRule.onNodeWithText("74%").assertExists()
    }
}
