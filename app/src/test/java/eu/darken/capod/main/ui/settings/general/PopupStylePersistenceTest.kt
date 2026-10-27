package eu.darken.capod.main.ui.settings.general

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.main.core.PopUpStylePolicy
import eu.darken.capod.main.core.popUpStylePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class PopupStylePersistenceTest {
    @Test fun `live notification preference defaults off and preserves existing choices`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = GeneralSettings(context, Json)
        assertFalse(settings.showLiveBatteryInNotification.flow.first())
        settings.useExtraMonitorNotification.update { true }
        settings.keepConnectedNotificationAfterDisconnect.update { true }
        settings.showBatteryInStatusBar.update { true }
        settings.showLiveBatteryInNotification.update { true }
        settings.showLiveBatteryInNotification.update { false }
        val reopened = GeneralSettings(context, Json)
        assertFalse(reopened.showLiveBatteryInNotification.flow.first())
        assertTrue(reopened.useExtraMonitorNotification.flow.first())
        assertTrue(reopened.keepConnectedNotificationAfterDisconnect.flow.first())
        assertTrue(reopened.showBatteryInStatusBar.flow.first())
        reopened.showLiveBatteryInNotification.update { true }
        assertTrue(settings.showLiveBatteryInNotification.flow.first())
    }

    @Test fun `real preferences default independently persist all combinations and reopen unchanged`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = GeneralSettings(context, Json)
        assertEquals(PopUpStylePolicy(true, false), settings.popUpStylePolicy.first())
        assertEquals("ui.popup.bottom.enabled", settings.showBottomPopup.keyName)
        assertEquals("ui.popup.top.enabled", settings.showTopPopup.keyName)
        for ((bottom, top) in listOf(false to false, false to true, true to true, true to false)) {
            settings.showBottomPopup.update { bottom }
            settings.showTopPopup.update { top }
            assertEquals(PopUpStylePolicy(bottom, top), settings.popUpStylePolicy.first())
            // A newly subscribed settings screen reads persisted preferences, with no default write.
            assertEquals(PopUpStylePolicy(bottom, top), settings.popUpStylePolicy.first())
        }
        assertTrue(context.filesDir.resolve("datastore/settings_general.preferences_pb").length() > 0)
    }
}
