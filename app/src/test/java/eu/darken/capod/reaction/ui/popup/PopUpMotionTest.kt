package eu.darken.capod.reaction.ui.popup

import android.animation.ValueAnimator
import androidx.activity.ComponentActivity
import android.app.AppOpsManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import eu.darken.capod.common.theming.ThemeState
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.main.core.currentThemeState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowChoreographer
import testhelpers.TestApplication
import java.time.Duration

/** Production Compose content and property animation frames, with window attachment delayed by a real host. */
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class PopUpMotionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java)
    private lateinit var host: FrameLayout
    private val manager = mockk<WindowManager>(relaxed = true)
    private val settings = mockk<GeneralSettings>()
    private val attached = mutableListOf<View>()
    private val device = MockPodDataProvider.dualPodMonitoredMixed()
    private lateinit var controller: PopUpOverlayController
    private var alphaAtAdd = -1f
    private var flags = 0
    private val originalFramePause = ShadowChoreographer.isPaused()
    private val originalFrameDelay = ShadowChoreographer.getFrameDelay()

    @Before fun setup() {
        ShadowSettings.setCanDrawOverlays(true)
        // Prevent Robolectric auto-draining every animation during a single idleFor call.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        setScale(1f)
        mockkStatic("eu.darken.capod.main.core.GeneralSettingsExtensionsKt")
        every { settings.currentThemeState } returns ThemeState()
        activity.setup()
        host = FrameLayout(activity.get())
        activity.get().setContentView(host)
        tick(32)
        assertTrue(host.isAttachedToWindow)
        every { manager.currentWindowMetrics } answers {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        }
        every { manager.addView(any(), any()) } answers {
            attached += firstArg<View>()
            alphaAtAdd = firstArg<View>().alpha
            flags = secondArg<WindowManager.LayoutParams>().flags
            Unit
        }
        every { manager.updateViewLayout(any(), any()) } answers {
            flags = secondArg<WindowManager.LayoutParams>().flags
            Unit
        }
        every { manager.removeViewImmediate(any()) } answers {
            val view = firstArg<View>()
            attached -= view
            host.removeView(view)
            Unit
        }
        every { manager.removeView(any()) } answers {
            attached -= firstArg<View>()
            host.removeView(firstArg<View>())
            Unit
        }
        controller = createController(PopUpOverlayController.Style.BOTTOM)
    }

    private fun createController(style: PopUpOverlayController.Style) = PopUpOverlayController(
        object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = when (name) {
                Context.WINDOW_SERVICE -> manager
                Context.APP_OPS_SERVICE -> mockk<AppOpsManager>(relaxed = true)
                Context.DISPLAY_SERVICE -> mockk<DisplayManager>(relaxed = true)
                else -> super.getSystemService(name)
            }
        }, settings, style,
    )

    private fun setScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        // Android normally synchronizes this process-wide animator value from the system setting.
        ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun tick(ms: Long) {
        // Paused Choreographer releases at most one requested vsync per clock advance.
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    private fun render(view: View) {
        host.addView(view, FrameLayout.LayoutParams(400, FrameLayout.LayoutParams.WRAP_CONTENT))
        tick(32)
        assertTrue("Fixture must attach the real ComposeView", view.isAttachedToWindow)
        assertTrue("Fixture must lay out actual content", view.width > 0 && view.height > 0)
        assertTrue((view as ComposeView).childCount > 0)
    }

    @After fun cleanup() {
        controller.dismissImmediately()
        activity.pause().stop().destroy()
        setScale(1f)
        ShadowChoreographer.setPaused(originalFramePause)
        ShadowChoreographer.setFrameDelay(originalFrameDelay)
        unmockkAll()
    }

    @Test fun `attachment starts transparent before the window can draw`() {
        controller.show(device)
        assertEquals("The window must not flash before its first composition", 0f, alphaAtAdd, .001f)
    }

    @Test fun `delayed first content still receives a full visible entrance`() {
        controller.show(device)
        val view = attached.single()
        tick(900)
        assertEquals("Entrance must wait for the first content frame", 0f, view.alpha, .001f)
        render(view)
        assertTrue("First rendered frame must begin the fade", view.alpha < .2f)
        tick(240)
        assertTrue("Entrance should remain visibly in progress", view.alpha > .1f && view.alpha < .95f)
        assertTrue(view.translationY > 0f)
        tick(400)
        assertEquals(1f, view.alpha, .001f)
        assertEquals(0f, view.translationY, .001f)
    }
    @Test fun `waiting window is untouchable and never ready window is removed`() {
        controller.show(device)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        tick(2100)
        assertTrue(attached.isEmpty())
    }

    @Test fun `close before first frame retires immediately and stale draw cannot revive it`() {
        controller.show(device)
        val retired = attached.single()
        val staleObserver = retired.viewTreeObserver
        val staleReadiness = PopUpOverlayController::class.java.getDeclaredField("readiness")
            .apply { isAccessible = true }.get(controller)
        controller.close()
        assertTrue(attached.isEmpty())
        controller.show(device.copy(label = "Reopened"))
        val replacement = attached.single()
        staleObserver.dispatchOnPreDraw()
        // Simulate callbacks already copied into an in-flight dispatch before cancellation.
        (staleReadiness as android.view.ViewTreeObserver.OnPreDrawListener).onPreDraw()
        (staleReadiness as Runnable).run()
        tick(100)
        assertEquals(listOf(replacement), attached)
        assertEquals(0f, replacement.alpha, .001f)
        render(replacement)
        tick(700)
        assertEquals(1f, replacement.alpha, .001f)
    }

    @Test fun `detaching while waiting removes window ownership`() {
        controller.show(device)
        val view = attached.single()
        host.addView(view)
        host.removeView(view)
        tick(32) // Allow the queued cleanup past the pending traversal barrier.
        assertTrue(attached.isEmpty())
    }

    @Test fun `data updates neither restart readiness nor replay visible entrance`() {
        controller.show(device)
        val view = attached.single()
        tick(900)
        controller.show(device.copy(label = "Updated"))
        render(view)
        tick(240)
        val progress = view.alpha
        controller.show(device)
        assertEquals(progress, view.alpha, .001f)
        tick(400)
        assertEquals(1f, view.alpha, .001f)
    }

    @Test fun `top lifetime gives four readable seconds after entrance and motion is upward`() {
        controller = createController(PopUpOverlayController.Style.TOP)
        controller.show(device)
        val view = attached.single()
        tick(900)
        render(view)
        assertTrue(view.translationY < 0f)
        tick(4200)
        assertEquals(listOf(view), attached)
        assertEquals(1f, view.alpha, .001f)
        tick(600)
        assertTrue(view.translationY < 0f)
        tick(400)
        assertTrue(attached.isEmpty())
    }

    @Test fun `exit fades over visible frames and releases the window`() {
        controller.show(device)
        val view = attached.single()
        render(view)
        tick(700)
        controller.close()
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        tick(160)
        assertEquals(listOf(view), attached)
        assertTrue(view.alpha > .1f && view.alpha < .99f)
        assertTrue(view.translationY > 0f)
        tick(220)
        assertTrue(attached.isEmpty())
    }

    @Test fun `double animation scale extends entrance and exit without double scaling`() {
        setScale(2f)
        controller.show(device)
        val view = attached.single()
        render(view)
        tick(600)
        assertTrue(view.alpha > .1f && view.alpha < .95f)
        tick(650)
        assertEquals(1f, view.alpha, .001f)
        controller.close()
        tick(350)
        assertEquals(listOf(view), attached)
        assertTrue(view.alpha > .1f && view.alpha < .99f)
        tick(400)
        assertTrue(attached.isEmpty())
    }

    @Test fun `scale zero is visible immediately and closes synchronously`() {
        setScale(0f)
        controller.show(device)
        val view = attached.single()
        assertEquals(1f, alphaAtAdd, .001f)
        assertEquals(0f, view.translationY, .001f)
        assertEquals(1f, view.scaleX, .001f)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        controller.close()
        assertTrue(attached.isEmpty())
    }
    @Test fun `show after detach before cleanup creates a replacement owned window`() {
        controller.show(device)
        val old = attached.single()
        host.addView(old)
        host.removeView(old)
        controller.show(device.copy(label = "Reopened"))
        val replacement = attached.last()
        assertNotSame(old, replacement)
        tick(1)
        assertEquals(listOf(replacement), attached)
        render(replacement)
        tick(700)
        assertEquals(1f, replacement.alpha, .001f)
    }
}
