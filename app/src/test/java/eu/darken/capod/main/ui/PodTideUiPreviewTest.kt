package eu.darken.capod.main.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import eu.darken.capod.common.theming.CapodTheme
import eu.darken.capod.common.theming.ThemeMode
import eu.darken.capod.common.theming.ThemeState
import eu.darken.capod.main.ui.overview.cards.DualPodsCard
import eu.darken.capod.monitor.core.battery.BatteryEstimate
import eu.darken.capod.main.ui.settings.SettingsScreen
import eu.darken.capod.main.ui.settings.SettingsViewModel
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import testhelpers.compose.BaseComposeRobolectricTest
import java.io.File
import java.time.Instant

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w400dp-h720dp-mdpi")
class PodTideUiPreviewTest : BaseComposeRobolectricTest() {
    private fun render(name: String, dark: Boolean, home: Boolean) {
        val output = System.getenv("PODBRIDGE_PREVIEW_DIR")
        assumeNotNull(output)
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            CapodTheme(ThemeState(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (home) DualPodsCard(device = MockPodDataProvider.dualPodFullyLoaded(), showDebug = false,
                            now = Instant.parse("2026-10-29T10:00:00Z"),
                            batteryEstimate = BatteryEstimate(
                                left = BatteryEstimate.Pod(392, .1f, BatteryEstimate.Source.SPEC),
                                right = BatteryEstimate.Pod(392, .1f, BatteryEstimate.Source.SPEC),
                            ), onDeviceSettings = {})
                        else SettingsScreen(state = SettingsViewModel.State(isPro = true, sponsorUrl = null),
                            onNavigateUp = {}, onGeneralSettings = {}, onDeviceManager = {}, onUpgradeStatus = {},
                            onSupport = {}, onWiki = {}, onChangelog = {}, onAcknowledgements = {},
                            onPrivacyPolicy = {}, onSponsor = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.runOnIdle { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) } }
        File(output!!, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun homeLight() = render("PodTide-home-light.png", dark = false, home = true)
    @Test fun settingsLight() = render("PodTide-settings-light.png", dark = false, home = false)
    @Test fun settingsDark() = render("PodTide-settings-dark.png", dark = true, home = false)
}
