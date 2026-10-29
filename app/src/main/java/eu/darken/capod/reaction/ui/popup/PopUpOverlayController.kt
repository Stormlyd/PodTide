package eu.darken.capod.reaction.ui.popup

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import eu.darken.capod.main.ui.MainActivity
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import eu.darken.capod.common.debug.logging.Logging.Priority.ERROR
import eu.darken.capod.common.debug.logging.asLog
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.theming.CapodTheme
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.main.core.currentThemeState
import eu.darken.capod.monitor.core.PodDevice

/** One lifecycle implementation shared by independently owned popup styles. */
internal class PopUpOverlayController(
    private val appContext: Context,
    private val generalSettings: GeneralSettings,
    private val style: Style,
) {
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var deviceState: MutableState<PodDevice?>? = null
    private var heightState: MutableState<Float>? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var added = false
    private var exiting = false
    private var generation = 0L
    private var exitTimeout: Runnable? = null
    private var readiness: EntranceReadiness? = null
    private var entranceTimeout: Runnable? = null
    private var entering = false
    private var lifetimeTimeout: Runnable? = null
    private var lifetimeGeneration = 0L
    private var watching = false
    private val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val displays = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        onMain { if (!Settings.canDrawOverlays(appContext)) dismissImmediately() }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { dismissImmediately() }
        override fun onDisplayChanged(displayId: Int): Unit = onMain {
            if (composeView != null) try { updateBounds() } catch (e: Exception) { fail(e) }
        }
    }

    @Volatile var isMainActivityVisible: Boolean = false
        set(value) {
            field = value
            if (value) dismissImmediately()
        }

    fun show(device: PodDevice): Unit = onMain {
        if (isMainActivityVisible || !Settings.canDrawOverlays(appContext)) {
            dismissImmediately()
            return@onMain
        }
        try {
            val current = composeView
            if (current != null && added && readiness?.detached != true) {
                deviceState?.value = device
                updateBounds()
                if (exiting) {
                    invalidateExit()
                    layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    windowManager.updateViewLayout(current, layoutParams)
                    enter(current)
                }
                if (readiness == null && !entering) refreshLifetime(current)
                return@onMain
            }
            dismissImmediately()
            val state = mutableStateOf<PodDevice?>(device)
            val height = mutableStateOf(600f)
            deviceState = state
            heightState = height
            val owner = OverlayLifecycleOwner()
            lifecycleOwner = owner
            val view = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    state.value?.let { currentDevice ->
                        CapodTheme(state = generalSettings.currentThemeState) {
                            val onClose = { if (deviceState === state) close() }
                            val onOpen = { close(); appContext.startActivity(Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
                            val modifier = Modifier.heightIn(max = height.value.dp)
                            when (style) {
                                Style.BOTTOM -> PopUpContent(currentDevice, onClose, modifier, onOpen)
                                Style.TOP -> TopPopUpContent(currentDevice, onClose, modifier, onOpen)
                            }
                        }
                    }
                }
            }
            composeView = view
            owner.start()
            updateBounds()
            prepareEntrance(view)
            if (animationScale() != 0f) {
                layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            // Record the attempted attachment too: addView can fail after partially attaching.
            added = true
            windowManager.addView(view, layoutParams)
            watching = true
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, appContext.packageName, permissionListener)
            displays.registerDisplayListener(displayListener, handler)
            if (animationScale() == 0f) enter(view)
            else EntranceReadiness(view).also { readiness = it; it.start() }
        } catch (e: Exception) { fail(e) }
    }

    fun close(): Unit = onMain {
        invalidateLifetime()
        val view = composeView ?: return@onMain
        if (readiness != null || isMainActivityVisible || !Settings.canDrawOverlays(appContext) || animationScale() == 0f) {
            dismissImmediately()
            return@onMain
        }
        if (exiting) return@onMain
        invalidateEntrance()
        invalidateExit()
        exiting = true
        val token = generation
        try {
            // The fading window must never become an invisible touch blocker.
            layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(view, layoutParams)
            val finish = Runnable { if (token == generation && composeView === view && exiting) dismissImmediately() }
            exitTimeout = finish
            handler.postDelayed(finish, (EXIT_MS * animationScale()).toLong().coerceAtMost(5_000) + 100)
            view.animate().cancel()
            view.animate().alpha(0f).translationY(style.direction * 18 * appContext.resources.displayMetrics.density)
                .scaleX(.98f).scaleY(.98f).setDuration(EXIT_MS).setInterpolator(AccelerateInterpolator())
                .withEndAction(finish).start()
        } catch (e: Exception) { fail(e) }
    }

    /** Synchronous on the main thread; used by foreground and service termination paths. */
    fun dismissImmediately(): Unit = onMain {
        invalidateLifetime()
        invalidateExit()
        invalidateEntrance()
        val view = composeView
        val owner = lifecycleOwner
        val wasAdded = added
        val retiringParams = layoutParams
        // Clear ownership before cancellation/removal, whose callbacks can be reentrant.
        composeView = null
        lifecycleOwner = null
        deviceState = null
        heightState = null
        layoutParams = null
        added = false
        if (watching) {
            watching = false
            runCatching { appOps.stopWatchingMode(permissionListener) }
            runCatching { displays.unregisterDisplayListener(displayListener) }
        }
        if (view != null) {
            runCatching { view.animate().withEndAction(null).cancel() }
            view.visibility = View.GONE
            if (wasAdded) removeRetiredView(view, retiringParams, attemptsLeft = 3)
            runCatching { view.disposeComposition() }.onFailure { log(TAG, ERROR) { "Composition disposal failed: $it" } }
        }
        runCatching { owner?.destroy() }.onFailure { log(TAG, ERROR) { "Overlay lifecycle disposal failed: $it" } }
    }

    private fun removeRetiredView(view: View, params: WindowManager.LayoutParams?, attemptsLeft: Int) {
        // A platform removal failure must not leave a transparent input window. Each retry
        // owns only the retired view/params, never the newer singleton overlay's fields.
        params?.let {
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(view, it) }
        }
        try { windowManager.removeViewImmediate(view); return } catch (_: Exception) { }
        try { windowManager.removeView(view); return } catch (error: Exception) {
            if (attemptsLeft > 1) handler.postDelayed({ removeRetiredView(view, params, attemptsLeft - 1) }, 50)
            else log(TAG, ERROR) { "Overlay removal failed after bounded retries: ${error.asLog()}" }
        }
    }
    private fun invalidateLifetime() {
        lifetimeGeneration++
        lifetimeTimeout?.let(handler::removeCallbacks)
        lifetimeTimeout = null
    }

    private fun refreshLifetime(view: ComposeView) {
        if (style != Style.TOP) return
        invalidateLifetime()
        val token = lifetimeGeneration
        val expire = Runnable {
            if (token == lifetimeGeneration && composeView === view) close()
        }
        lifetimeTimeout = expire
        handler.postDelayed(expire, 4_000)
    }

    private fun invalidateExit() {
        generation++
        exiting = false
        exitTimeout?.let(handler::removeCallbacks)
        exitTimeout = null
    }

    private fun prepareEntrance(view: ComposeView) {
        if (animationScale() == 0f) {
            view.alpha = 1f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.translationY = style.direction * 24 * appContext.resources.displayMetrics.density
        view.scaleX = .96f
        view.scaleY = .96f
    }

    private fun enter(view: ComposeView) {
        invalidateEntrance()
        view.animate().withEndAction(null).cancel()
        prepareEntrance(view)
        if (animationScale() == 0f) {
            refreshLifetime(view)
            return
        }
        entering = true
        val token = generation
        val finish = Runnable {
            if (token == generation && composeView === view && entering) {
                entranceTimeout?.let(handler::removeCallbacks)
                entranceTimeout = null
                entering = false
                view.animate().withEndAction(null).cancel()
                view.alpha = 1f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
                // Preserve the complete readable dwell, also with slower system animations.
                refreshLifetime(view)
            }
        }
        entranceTimeout = finish
        handler.postDelayed(finish, (ENTER_MS * animationScale()).toLong().coerceAtMost(30_000) + 100)
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(ENTER_MS).setInterpolator(DecelerateInterpolator(1.5f)).withEndAction(finish).start()
    }

    private fun invalidateEntrance() {
        readiness?.cancel()
        readiness = null
        entranceTimeout?.let(handler::removeCallbacks)
        entranceTimeout = null
        entering = false
    }

    /** Compose must finish its first measure/layout before any entrance time is consumed. */
    private inner class EntranceReadiness(private val view: ComposeView) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, Runnable {
        var detached = false
            private set
        private val observer = view.viewTreeObserver
        private fun ownsView() = readiness === this && composeView === view

        fun start() {
            observer.addOnPreDrawListener(this)
            view.addOnAttachStateChangeListener(this)
            handler.postDelayed(this, 2_000)
        }

        fun cancel() {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            // An unattached observer is merged into the window's observer on attachment.
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
            view.removeOnAttachStateChangeListener(this)
            handler.removeCallbacks(this)
        }

        override fun onPreDraw(): Boolean {
            if (!ownsView() || !view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested) return true
            try {
                cancel()
                readiness = null
                layoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager.updateViewLayout(view, layoutParams)
                enter(view)
            } catch (e: Exception) { fail(e) }
            return true
        }

        override fun run() { if (ownsView()) dismissImmediately() }
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            detached = true
            // Let the platform finish detaching before removal/disposal can reenter its traversal.
            handler.post { if (ownsView()) dismissImmediately() }
        }
    }

    private fun animationScale(): Float = Settings.Global.getFloat(appContext.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f).takeIf { it.isFinite() && it >= 0f } ?: 1f

    private fun updateBounds() {
        val density = appContext.resources.displayMetrics.density
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (metrics.bounds.width() - insets.left - insets.right) to (metrics.bounds.height() - insets.top - insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
            metrics.widthPixels to metrics.heightPixels
        }
        heightState?.value = (height / density - 24f).coerceAtLeast(120f)
        val params = layoutParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = style.gravity or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        }.also { layoutParams = it }
        params.width = minOf((width - 16 * density).toInt().coerceAtLeast(1), (style.maxWidthDp * density).toInt())
        if (added) windowManager.updateViewLayout(composeView, params)
    }

    private fun fail(error: Exception) {
        log(TAG, ERROR) { "Overlay operation failed: ${error.asLog()}" }
        dismissImmediately()
    }
    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    private class OverlayLifecycleOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        init { savedState.performRestore(null) }
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
        fun start() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }
    internal enum class Style(val gravity: Int, val maxWidthDp: Int, val direction: Int) {
        BOTTOM(Gravity.BOTTOM, 420, 1), TOP(Gravity.TOP, 360, -1),
    }
    companion object {
        private val TAG = logTag("Reaction", "PopUp", "Window")
        private const val ENTER_MS = 600L
        private const val EXIT_MS = 320L
    }
}
