package eu.darken.capod.reaction.ui.popup

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.debug.logging.Logging.Priority.ERROR
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.main.core.PopUpStylePolicy
import eu.darken.capod.monitor.core.PodDevice
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates independent overlays; lifecycle and animation ownership stay within each controller. */
@Singleton
class PopUpWindow @Inject constructor(
    @ApplicationContext appContext: Context,
    generalSettings: GeneralSettings,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val bottom = PopUpOverlayController(appContext, generalSettings, PopUpOverlayController.Style.BOTTOM)
    private val top = PopUpOverlayController(appContext, generalSettings, PopUpOverlayController.Style.TOP)
    private var policy = PopUpStylePolicy()

    @Volatile var isMainActivityVisible: Boolean = false
        set(value) {
            field = value
            onMain { each { it.isMainActivityVisible = value } }
        }

    fun applyStylePolicy(policy: PopUpStylePolicy): Unit = onMain {
        this.policy = policy
        if (!policy.showBottomPopup) safely(bottom) { dismissImmediately() }
        if (!policy.showTopPopup) safely(top) { dismissImmediately() }
    }

    fun show(device: PodDevice): Unit = onMain {
        if (isMainActivityVisible) { each { it.dismissImmediately() }; return@onMain }
        if (policy.showBottomPopup) safely(bottom) { show(device) }
        if (policy.showTopPopup) safely(top) { show(device) }
    }

    fun close(): Unit = onMain { each { it.close() } }

    fun dismissImmediately(): Unit = onMain { each { it.dismissImmediately() } }

    private inline fun each(action: (PopUpOverlayController) -> Unit) {
        safely(bottom) { action(this) }
        safely(top) { action(this) }
    }

    private inline fun safely(controller: PopUpOverlayController, action: PopUpOverlayController.() -> Unit) {
        try { controller.action() } catch (error: Exception) {
            log(TAG, ERROR) { "Popup style operation failed: $error" }
            runCatching { controller.dismissImmediately() }
        }
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    companion object { private val TAG = logTag("Reaction", "PopUp", "Window") }
}
