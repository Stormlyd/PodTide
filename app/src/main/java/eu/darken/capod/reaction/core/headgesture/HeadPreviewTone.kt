package eu.darken.capod.reaction.core.headgesture

import android.media.AudioManager
import android.media.ToneGenerator
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import javax.inject.Inject

/** Platform-only, opt-in feedback. The controller owns its lifetime. */
class HeadPreviewTone @Inject constructor() {
    private val tag = logTag("PodBridge", "HeadPreviewTone")
    private var tone: ToneGenerator? = null

    fun play() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (failure: Exception) {
            log(tag, WARN) { "Preview tone failed: $failure" }
            release()
        }
    }

    fun release() {
        try { tone?.release() }
        catch (failure: Exception) { log(tag, WARN) { "Preview tone release failed: $failure" } }
        finally { tone = null }
    }
}
