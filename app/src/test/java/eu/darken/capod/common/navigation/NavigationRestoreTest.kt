package eu.darken.capod.common.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class NavigationRestoreTest : BaseComposeRobolectricTest() {
    @Test fun `restored heart page returns to existing device settings with normal Back`() {
        assertReturnsToSettings(Nav.Main.HeartRate("owned"))
    }

    @Test fun `restored head preview returns to existing device settings with normal Back`() {
        assertReturnsToSettings(Nav.Main.HeadPreview("owned"))
    }

    @Test fun `retired entries below the current page cannot reappear when navigating Back`() {
        val restored = restoreLegacyStack(
            Nav.Main.Overview, Nav.Main.HeartRate("owned"), Nav.Settings.General,
            Nav.Main.HeadPreview("owned"), Nav.Settings.Support,
        )
        composeRule.runOnIdle {
            assertEquals(listOf(Nav.Main.Overview, Nav.Settings.General, Nav.Settings.Support), restored.stack().toList())
            assertTrue(restored.controller.up())
            assertEquals(Nav.Settings.General, restored.stack().last())
            assertTrue(restored.controller.up())
            assertEquals(Nav.Main.Overview, restored.stack().last())
            assertFalse(restored.controller.up())
        }
    }

    @Test fun `an entirely retired restored stack falls back to Overview without a navigation loop`() {
        val restored = restoreLegacyStack(Nav.Main.HeartRate("owned"), Nav.Main.HeadPreview("owned"))
        composeRule.runOnIdle {
            assertEquals(listOf(Nav.Main.Overview), restored.stack().toList())
            repeat(3) { restored.controller.setup(restored.stack()) }
            assertEquals(listOf(Nav.Main.Overview), restored.stack().toList())
            assertFalse(restored.controller.up())
        }
    }

    @Test fun `ordinary restored routes retain their order and normal Back behavior`() {
        val expected = listOf(Nav.Main.Overview, Nav.Main.DeviceSettings("owned"), Nav.Main.PressControls("owned"))
        val restored = restoreLegacyStack(*expected.toTypedArray())
        composeRule.runOnIdle {
            assertEquals(expected, restored.stack().toList())
            assertTrue(restored.controller.up())
            assertEquals(Nav.Main.DeviceSettings("owned"), restored.stack().last())
        }
    }

    private fun assertReturnsToSettings(retired: NavKey) {
        val restored = restoreLegacyStack(Nav.Main.Overview, Nav.Main.DeviceSettings("owned"), retired)
        composeRule.runOnIdle {
            assertEquals(listOf(Nav.Main.Overview, Nav.Main.DeviceSettings("owned")), restored.stack().toList())
            assertTrue(restored.controller.up())
            assertEquals(Nav.Main.Overview, restored.stack().last())
            assertFalse(restored.controller.up())
        }
    }

    private data class RestoredStack(val controller: NavigationController, val stack: () -> NavBackStack<NavKey>)

    /** Save a legacy stack without the release gate, then enable real controller setup on restore. */
    private fun restoreLegacyStack(vararg legacyKeys: NavKey): RestoredStack {
        val restoration = StateRestorationTester(composeRule)
        val controller = NavigationController()
        var publicRelease = false
        lateinit var current: NavBackStack<NavKey>
        restoration.setContent {
            current = rememberNavBackStack(Nav.Main.Overview)
            if (publicRelease) controller.setup(current)
            Text(current.last().toString())
        }
        composeRule.runOnIdle {
            current.clear()
            current.addAll(legacyKeys)
        }
        composeRule.waitForIdle()
        // A plain variable intentionally changes behavior only in the recreated composition.
        publicRelease = true
        restoration.emulateSavedInstanceStateRestore()
        return RestoredStack(controller) { current }
    }
}
