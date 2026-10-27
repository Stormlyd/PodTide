package eu.darken.capod.main.ui.headpreview

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.capod.reaction.core.headgesture.HeadGestureController
import javax.inject.Inject

@HiltViewModel
class HeadPreviewViewModel @Inject constructor(val controller: HeadGestureController) : ViewModel() {
    private var token: Long? = null
    fun start(profileId: String) { if (token == null) token = controller.startPreview(profileId) }
    fun stop() { token?.let(controller::stopPreview); token = null }
    fun recalibrate() { token?.let(controller::recalibratePreview) }
    fun resetCounts() { token?.let(controller::resetPreviewCounts) }
    fun setSound(enabled: Boolean) { token?.let { controller.setPreviewSound(it, enabled) } }
    override fun onCleared() { stop(); super.onCleared() }
}
