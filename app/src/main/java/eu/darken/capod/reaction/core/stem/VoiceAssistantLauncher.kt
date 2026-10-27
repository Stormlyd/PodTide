package eu.darken.capod.reaction.core.stem

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.coroutine.DispatcherProvider
import eu.darken.capod.R
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceAssistantResult { LAUNCH_REQUESTED, NOT_CONFIGURED, BLOCKED }

@Singleton
class VoiceAssistantLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    /** Request a normal system activity; Android can still restrict background/lock-screen UI. */
    suspend fun launch(): VoiceAssistantResult = withContext(dispatchers.MainImmediate) {
        var blocked = false
        for (action in listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(context.packageManager) == null) continue
                context.startActivity(intent)
                return@withContext VoiceAssistantResult.LAUNCH_REQUESTED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ActivityNotFoundException) { /* Try the other public assistant action. */ }
            catch (error: SecurityException) {
                blocked = true
                log(TAG, WARN) { "Assistant launch denied: $error" }
            }
        }
        val result = if (blocked) VoiceAssistantResult.BLOCKED else VoiceAssistantResult.NOT_CONFIGURED
        Toast.makeText(context, if (blocked) R.string.voice_assistant_blocked else R.string.voice_assistant_not_configured, Toast.LENGTH_LONG).show()
        result
    }

    companion object { private val TAG = logTag("PodBridge", "VoiceAssistant") }
}
