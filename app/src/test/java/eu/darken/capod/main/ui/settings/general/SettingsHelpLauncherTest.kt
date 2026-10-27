package eu.darken.capod.main.ui.settings.general

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class SettingsHelpLauncherTest : BaseTest() {
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `Find Devices opens the fixed official HTTPS page`() {
        SettingsHelpLauncher.openFindDevices(application) shouldBe true
        val launched = shadowOf(application).nextStartedActivity!!
        launched.action shouldBe Intent.ACTION_VIEW
        launched.data shouldBe "https://www.icloud.com/find".toUri()
    }

    @Test
    fun `app details opens only the current package`() {
        SettingsHelpLauncher.openAppDetails(application) shouldBe true
        val launched = shadowOf(application).nextStartedActivity!!
        launched.action shouldBe Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        launched.data shouldBe "package:${application.packageName}".toUri()
    }

    @Test
    fun `unavailable system destination reports failure`() {
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) = throw ActivityNotFoundException()
        }
        SettingsHelpLauncher.openAppDetails(context) shouldBe false
        SettingsHelpLauncher.openFindDevices(context) shouldBe false
    }
}
