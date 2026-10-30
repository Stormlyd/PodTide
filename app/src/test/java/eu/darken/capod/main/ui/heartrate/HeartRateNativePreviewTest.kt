package eu.darken.capod.main.ui.heartrate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import eu.darken.capod.common.theming.CapodTheme
import eu.darken.capod.common.theming.ThemeMode
import eu.darken.capod.common.theming.ThemeState
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.profiles.ui.DeviceManagerScreen
import eu.darken.capod.profiles.ui.DeviceManagerViewModel
import eu.darken.capod.reaction.core.heartrate.HeartRateReading
import eu.darken.capod.reaction.core.heartrate.HeartRateSample
import eu.darken.capod.reaction.core.heartrate.HeartRateStatus
import eu.darken.capod.reaction.core.heartrate.HeartRateUiState
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import testhelpers.compose.BaseComposeRobolectricTest
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
class HeartRateNativePreviewTest : BaseComposeRobolectricTest() {
    private fun render(name: String, state: HeartRateUiState, dark: Boolean = false, fontScale: Float = 1f) {
        val output = System.getenv("PODBRIDGE_PREVIEW_DIR")
        assumeNotNull(output)
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            CapodTheme(ThemeState(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    HeartRateScreen(state, {}, {}, {}, {})
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(output!!, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val samples = listOf(72, 76, 75, 88, 84, 79, 82).mapIndexed { i, bpm -> HeartRateSample(1_729_000_000_000 + i * 5_000L, bpm) }
    private val live = HeartRateUiState(true, HeartRateReading(HeartRateStatus.ACTIVE, 82), samples)
    @Test fun liveLight() = render("PodTide-heart-light.png", live)
    @Test fun liveDarkLarge() = render("PodTide-heart-dark-large.png", live, dark = true, fontScale = 1.5f)
    @Test fun measuring() = render("PodTide-heart-waiting.png", HeartRateUiState(requested = true, reading = HeartRateReading(HeartRateStatus.WAITING)))

    @Test fun deviceManagerArtwork() {
        val output = System.getenv("PODBRIDGE_PREVIEW_DIR")
        assumeNotNull(output)
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            CapodTheme(ThemeState(mode = ThemeMode.LIGHT)) {
                DeviceManagerScreen(
                    DeviceManagerViewModel.State(profiles = listOf(MockPodDataProvider.profile("My AirPods Pro 3", PodModel.AIRPODS_PRO3))),
                    onBack = {}, onAddDevice = {}, onEditProfile = {},
                )
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.runOnIdle {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(output!!, "PodTide-device-manager.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
