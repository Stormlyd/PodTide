package eu.darken.capod.reaction.ui.popup

import android.app.AppOpsManager
import android.hardware.display.DisplayManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import eu.darken.capod.main.core.PopUpStylePolicy
import eu.darken.capod.main.core.GeneralSettings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import testhelpers.TestApplication
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class PopUpWindowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = mockk<WindowManager>(relaxed = true)
    private val attached = mutableListOf<View>()
    private val appOps = mockk<AppOpsManager>(relaxed = true)
    private val displays = mockk<DisplayManager>(relaxed = true)
    private var permissionCallback: AppOpsManager.OnOpChangedListener? = null
    private var displayCallback: DisplayManager.DisplayListener? = null
    private val permissions = mutableSetOf<AppOpsManager.OnOpChangedListener>()
    private val displayListeners = mutableSetOf<DisplayManager.DisplayListener>()
    private var adds = 0
    private lateinit var window: PopUpWindow
    private val device = MockPodDataProvider.dualPodMonitoredMixed()


    private fun policy(bottom: Boolean, top: Boolean) = window.applyStylePolicy(PopUpStylePolicy(bottom, top))
    @Test fun `all four style combinations attach the requested windows`() {
        for (bottom in listOf(false, true)) for (top in listOf(false, true)) {
            window.dismissImmediately()
            policy(bottom, top)
            window.show(device)
            assertEquals((if (bottom) 1 else 0) + (if (top) 1 else 0), attached.size)
        }
        window.dismissImmediately()
    }
    @Test fun `disabling retires only its style enabling waits for a real event`() {
        policy(true, true)
        window.show(device)
        val bottom = attached.first()
        val top = attached.last()
        policy(false, true)
        assertEquals(listOf(top), attached)
        policy(true, true)
        assertEquals(listOf(top), attached)
        window.show(device)
        assertEquals(2, attached.size)
        assertFalse(attached.contains(bottom))
        window.close()
        assertTrue(attached.isEmpty())
    }
    @Test fun `top show refreshes four second lifetime without entrance replay`() {
        policy(false, true)
        window.show(device)
        val view = attached.single()
        view.alpha = .73f
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3000))
        window.show(device.copy(label = "Updated"))
        assertSame(view, attached.single())
        assertEquals(.73f, view.alpha, .001f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        assertEquals(listOf(view), attached)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2900))
        assertTrue(attached.isEmpty())
    }
    @Test fun `obsolete lifetime cannot dismiss a replacement`() {
        policy(false, true)
        window.show(device)
        val controller = PopUpWindow::class.java.getDeclaredField("top").apply { isAccessible = true }.get(window)
        val staleTimeout = PopUpOverlayController::class.java.getDeclaredField("lifetimeTimeout").apply { isAccessible = true }.get(controller) as Runnable
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        window.dismissImmediately()
        window.show(device)
        val replacement = attached.single()
        staleTimeout.run()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf(replacement), attached)
        window.dismissImmediately()
    }
    @Test fun `failed bottom attachment still allows top and foreground retires both`() {
        policy(true, true)
        every { manager.addView(any(), any()) } answers {
            if (secondArg<WindowManager.LayoutParams>().gravity and android.view.Gravity.BOTTOM == android.view.Gravity.BOTTOM) throw SecurityException("bottom failed")
            attached += firstArg<View>(); Unit
        }
        window.show(device)
        assertEquals(1, attached.size)
        window.isMainActivityVisible = true
        assertTrue(attached.isEmpty())
    }
    @Test fun `top bounds fit safe insets once with eight dp offset and max 360 dp`() {
        policy(false, true)
        var params: WindowManager.LayoutParams? = null
        every { manager.currentWindowMetrics } returns android.view.WindowMetrics(android.graphics.Rect(0, 0, 600, 480),
            android.view.WindowInsets.Builder().setInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(10, 40, 20, 24)).build())
        every { manager.addView(any(), any()) } answers { attached += firstArg<View>(); params = secondArg(); Unit }
        window.show(device)
        val density = context.resources.displayMetrics.density
        assertEquals(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, params!!.gravity)
        assertEquals((8 * density).toInt(), params!!.y)
        assertEquals(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(), params!!.fitInsetsTypes)
        assertEquals(minOf((570 - 16 * density).toInt(), (360 * density).toInt()), params!!.width)
        window.dismissImmediately()
    }

    @Test fun `local close retires only its own style then global hide retires the other`() {
        policy(true, true)
        window.show(device)
        val bottom = attached.first()
        val controller = PopUpWindow::class.java.getDeclaredField("top").apply { isAccessible = true }.get(window) as PopUpOverlayController
        controller.close()
        assertEquals(listOf(bottom), attached)
        assertEquals(1, permissions.size)
        assertEquals(1, displayListeners.size)
        window.close()
        assertTrue(attached.isEmpty())
        assertTrue(permissions.isEmpty())
        assertTrue(displayListeners.isEmpty())
    }
    @Test fun `top starts above and has bounded upward exit cleanup`() {
        policy(false, true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        window.show(device)
        assertTrue(attached.single().translationY < 0f)
        window.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(attached.isEmpty())
    }

    @Test fun `partial listener registration failure cleans first style and still attaches second`() {
        policy(true, true)
        var watches = 0
        every { appOps.startWatchingMode(any<String>(), any(), any<AppOpsManager.OnOpChangedListener>()) } answers {
            permissionCallback = thirdArg(); permissions += permissionCallback!!
            if (++watches == 1) throw IllegalStateException("partial listener registration")
            Unit
        }
        window.show(device)
        assertEquals(1, attached.size)
        assertEquals(1, permissions.size)
        assertEquals(1, displayListeners.size)
        window.dismissImmediately()
        assertTrue(attached.isEmpty())
        assertTrue(permissions.isEmpty())
        assertTrue(displayListeners.isEmpty())
    }
    @Test fun `permission callbacks retire both styles and all observers without another show`() {
        policy(true, true)
        window.show(device)
        assertEquals(2, permissions.size)
        assertEquals(2, displayListeners.size)
        ShadowSettings.setCanDrawOverlays(false)
        permissions.toList().forEach { it.onOpChanged(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName) }
        assertTrue(attached.isEmpty())
        assertTrue(permissions.isEmpty())
        assertTrue(displayListeners.isEmpty())
    }

    @Before fun setup() {
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        every { manager.currentWindowMetrics } answers { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics }
        every { manager.addView(any(), any()) } answers { attached += firstArg<View>(); adds++; Unit }
        every { manager.removeViewImmediate(any()) } answers { attached -= firstArg<View>(); Unit }
        every { manager.removeView(any()) } answers { attached -= firstArg<View>(); Unit }
        every { appOps.startWatchingMode(any<String>(), any(), any<AppOpsManager.OnOpChangedListener>()) } answers {
            permissionCallback = thirdArg(); permissions += permissionCallback!!; Unit
        }
        every { appOps.stopWatchingMode(any()) } answers { assertTrue(permissions.remove(firstArg())); if (permissionCallback === firstArg<AppOpsManager.OnOpChangedListener>()) permissionCallback = null; Unit }
        every { displays.registerDisplayListener(any(), any()) } answers { displayCallback = firstArg(); displayListeners += displayCallback!!; Unit }
        every { displays.unregisterDisplayListener(any()) } answers { assertTrue(displayListeners.remove(firstArg())); if (displayCallback === firstArg<DisplayManager.DisplayListener>()) displayCallback = null; Unit }
        window = PopUpWindow(object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = when (name) {
                Context.WINDOW_SERVICE -> manager
                Context.APP_OPS_SERVICE -> appOps
                Context.DISPLAY_SERVICE -> displays
                else -> super.getSystemService(name)
            }
        }, mockk<GeneralSettings>(relaxed = true))
    }

    @Test fun `updates reuse the overlay without replaying entrance`() {
        window.show(device)
        val first = attached.single()
        first.alpha = .73f
        window.show(device.copy(label = "Updated"))
        assertEquals(.73f, first.alpha, .001f)
        assertEquals(1, adds)
        assertSame(first, attached.single())
        window.close()
        assertTrue(attached.isEmpty())
    }

    @Test fun `main activity visibility releases overlay immediately`() {
        window.show(device)
        window.isMainActivityVisible = true
        assertTrue(attached.isEmpty())
        window.show(device)
        assertTrue(attached.isEmpty())
    }

    @Test fun `failed add releases composition and lifecycle references`() {
        every { manager.addView(any(), any()) } throws SecurityException("permission revoked")
        window.show(device)
        val field = PopUpOverlayController::class.java.getDeclaredField("composeView").apply { isAccessible = true }
        assertNull(field.get(PopUpWindow::class.java.getDeclaredField("bottom").apply { isAccessible = true }.get(window)))
    }

    @Test fun `permission loss cleans up an existing overlay`() {
        window.show(device)
        ShadowSettings.setCanDrawOverlays(false)
        window.show(device)
        assertTrue(attached.isEmpty())
    }

    @Test fun `disabled animations close immediately`() {
        window.show(device)
        window.close()
        assertTrue(attached.isEmpty())
    }

    @Test fun `reopen during exit cannot be removed by obsolete close completion`() {
        window.show(device) // Establish already-visible content before enabling the exit animation.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        window.close()
        window.show(device.copy(label = "Reopened"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, attached.size)
        window.isMainActivityVisible = true
    }

    @Test fun `exit has bounded cleanup even without animation frames`() {
        window.show(device) // Establish already-visible content before enabling the exit animation.
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        window.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(attached.isEmpty())
    }
    @Test fun `service termination synchronously releases the production popup`() {
        window.show(device)
        val service = org.robolectric.Robolectric.buildService(eu.darken.capod.monitor.core.worker.MonitorService::class.java).create().get()
        service.popUpWindow = window
        service.notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        eu.darken.capod.monitor.core.worker.MonitorService::class.java.getDeclaredField("injectionComplete").apply { isAccessible = true }.set(service, true)
        service.onDestroy()
        assertTrue(attached.isEmpty())
    }

    @Test fun `display update failure releases the owned view`() {
        window.show(device)
        every { manager.updateViewLayout(any(), any()) } throws IllegalArgumentException("display removed")
        window.show(device)
        assertTrue(attached.isEmpty())
    }
    @Test fun `immediate removal failure falls back and still releases lifecycle`() {
        window.show(device)
        every { manager.removeViewImmediate(any()) } throws IllegalStateException("transient removal")
        window.close()
        assertTrue(attached.isEmpty())
        val field = PopUpOverlayController::class.java.getDeclaredField("lifecycleOwner").apply { isAccessible = true }
        assertNull(field.get(PopUpWindow::class.java.getDeclaredField("bottom").apply { isAccessible = true }.get(window)))
    }
    @Test fun `rotation refreshes width from current bounds and insets`() {
        var width = 0
        every { manager.updateViewLayout(any(), any()) } answers { width = secondArg<WindowManager.LayoutParams>().width; Unit }
        window.show(device)
        every { manager.currentWindowMetrics } returns android.view.WindowMetrics(android.graphics.Rect(0, 0, 300, 240),
            android.view.WindowInsets.Builder().setInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars(), android.graphics.Insets.of(10, 24, 10, 24)).build())
        displayCallback!!.onDisplayChanged(android.view.Display.DEFAULT_DISPLAY)
        assertTrue(width in 1..280)
        window.close()
    }

    @Test fun `failed removal becomes inert and retry cannot remove a replacement popup`() {
        window.show(device)
        val retiring = attached.single()
        var inert = false
        every { manager.updateViewLayout(any(), any()) } answers {
            if (firstArg<View>() === retiring) inert = secondArg<WindowManager.LayoutParams>().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            Unit
        }
        every { manager.removeViewImmediate(retiring) } throws IllegalStateException("temporary immediate failure")
        every { manager.removeView(retiring) } throws IllegalStateException("temporary removal failure")
        window.close()
        assertTrue("A failed removal must not leave an invisible touch window", inert)
        every { manager.removeViewImmediate(retiring) } answers { attached -= retiring; Unit }
        window.show(device.copy(label = "Replacement"))
        val replacement = attached.last()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(replacement), attached)
        window.close()
    }

    @Test fun `permission callback dismisses without another device event and unregisters observers`() {
        window.show(device)
        assertNotNull(permissionCallback)
        assertNotNull(displayCallback)
        ShadowSettings.setCanDrawOverlays(false)
        permissionCallback!!.onOpChanged(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName)
        assertTrue(attached.isEmpty())
        assertNull(permissionCallback)
        assertNull(displayCallback)
    }
    @Test fun `display removal callback dismisses and unregisters observers`() {
        window.show(device)
        displayCallback!!.onDisplayRemoved(android.view.Display.DEFAULT_DISPLAY)
        assertTrue(attached.isEmpty())
        assertNull(permissionCallback)
        assertNull(displayCallback)
    }
}
