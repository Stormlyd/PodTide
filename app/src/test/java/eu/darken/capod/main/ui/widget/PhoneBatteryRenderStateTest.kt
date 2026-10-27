package eu.darken.capod.main.ui.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class PhoneBatteryRenderStateTest : BaseTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `no earbud still carries visible phone and original message`() {
        val state = WidgetRenderStateMapper.map(
            context = context,
            device = null,
            theme = WidgetTheme(),
            isPro = true,
            hasConfiguredProfile = false,
            profileLabel = null,
            phoneBattery = PhoneBatteryState(0, true),
        )
        (state is WidgetRenderState.Message) shouldBe true
        state.phoneBattery shouldBe PhoneBatteryState(0, true)
        (state as WidgetRenderState.Message).primaryText shouldBe context.getString(
            eu.darken.capod.R.string.overview_nomaindevice_label
        )
    }
}
