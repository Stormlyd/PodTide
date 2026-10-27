package eu.darken.capod.reaction.core.heartrate

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.R
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class HeartRateOfflineControlsTest {
    private val manager = mockk<AapConnectionManager>(relaxed = true)
    private fun controller() = HeartRateController(
        ApplicationProvider.getApplicationContext<Application>(), mockk<DeviceMonitor>(), manager, mockk<DeviceProfilesRepo>(),
    )

    @Test fun `start provides immediate waiting feedback without a running monitor or socket request`() {
        val controller = controller()
        controller.start("offline")
        controller.state("offline").requested shouldBe true
        controller.state("offline").reading.bpm shouldBe null
        controller.state("offline").messageRes shouldBe R.string.heart_rate_waiting_connection
        coVerify(exactly = 0) { manager.sendCommand(any(), any()) }
    }

    @Test fun `offline pending request can be stopped immediately`() {
        val controller = controller()
        controller.start("offline")
        controller.stop("offline")
        controller.state("offline").requested shouldBe false
        controller.state("offline").reading shouldBe HeartRateReading()
    }
}
