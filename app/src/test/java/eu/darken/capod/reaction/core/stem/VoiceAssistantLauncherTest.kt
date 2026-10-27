package eu.darken.capod.reaction.core.stem

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.R
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import testhelpers.TestApplication
import testhelpers.coroutine.TestDispatcherProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class VoiceAssistantLauncherTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun launcher(context: Context = app) = VoiceAssistantLauncher(context, TestDispatcherProvider())
    private fun register(action: String = Intent.ACTION_VOICE_COMMAND) {
        val activity = ActivityInfo().apply {
            packageName = "test.voice"
            name = "test.voice.AssistantActivity"
            applicationInfo = ApplicationInfo().apply { packageName = "test.voice"; enabled = true }
            exported = true
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(action), ResolveInfo().apply { activityInfo = activity; isDefault = true })
    }

    @Test fun `configured voice command dispatches a new-task request`() = runTest {
        register()
        launcher().launch() shouldBe VoiceAssistantResult.LAUNCH_REQUESTED
        val started = shadowOf(app).nextStartedActivity
        started.action shouldBe Intent.ACTION_VOICE_COMMAND
        (started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) shouldBe true
    }

    @Test fun `assist action is used when no voice-command handler exists`() = runTest {
        register(Intent.ACTION_ASSIST)
        launcher().launch() shouldBe VoiceAssistantResult.LAUNCH_REQUESTED
        shadowOf(app).nextStartedActivity.action shouldBe Intent.ACTION_ASSIST
    }

    @Test fun `missing assistant is visible and does not claim launch success`() = runTest {
        launcher().launch() shouldBe VoiceAssistantResult.NOT_CONFIGURED
        ShadowToast.getTextOfLatestToast() shouldBe app.getString(R.string.voice_assistant_not_configured)
        shadowOf(app).nextStartedActivity shouldBe null
    }

    @Test fun `security denial is visible and returns blocked`() = runTest {
        register()
        val denied = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw SecurityException("denied") }
        }
        launcher(denied).launch() shouldBe VoiceAssistantResult.BLOCKED
        ShadowToast.getTextOfLatestToast() shouldBe app.getString(R.string.voice_assistant_blocked)
    }

    @Test fun `handler disappearing during launch is handled as unavailable`() = runTest {
        register()
        val vanished = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException("removed") }
        }
        launcher(vanished).launch() shouldBe VoiceAssistantResult.NOT_CONFIGURED
        ShadowToast.getTextOfLatestToast() shouldBe app.getString(R.string.voice_assistant_not_configured)
    }
}
