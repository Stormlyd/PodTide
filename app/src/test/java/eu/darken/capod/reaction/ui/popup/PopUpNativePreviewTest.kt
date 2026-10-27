package eu.darken.capod.reaction.ui.popup

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.common.theming.CapodTheme
import eu.darken.capod.common.theming.ThemeMode
import eu.darken.capod.common.theming.ThemeState
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.onNodeWithText
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import testhelpers.compose.BaseComposeRobolectricTest
import java.io.File

/** Opt-in actual production Compose rendering; output folder is supplied by the local preview command. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w400dp-h720dp-mdpi")
class PopUpNativePreviewTest : BaseComposeRobolectricTest() {
    private fun render(name: String, dark: Boolean = false, fontScale: Float = 1f) {
        val output = System.getenv("PODBRIDGE_PREVIEW_DIR")
        assumeNotNull(output)
        lateinit var renderedView: View
        composeRule.setContent {
            renderedView = LocalView.current
            CapodTheme(ThemeState(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
                        PopUpContent(pro3Preview(), {}, Modifier.widthIn(max = 420.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.runOnIdle {
            Bitmap.createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888).also {
                renderedView.draw(Canvas(it))
            }
        }
        if (dark) {
            val title = composeRule.onNodeWithText("AirPods Pro 3").fetchSemanticsNode().boundsInRoot
            var readablePixels = 0
            for (y in title.top.toInt() until title.bottom.toInt()) for (x in title.left.toInt() until title.right.toInt()) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) > 150) readablePixels++
            }
            assertTrue("Dark title must have contrasting light text", readablePixels > 20)
        }
        File(output!!, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun light() = render("PodBridge-popup-zh-light.png")
    @Test fun dark() = render("PodBridge-popup-zh-dark.png", dark = true)
    @Test fun largeFont() = render("PodBridge-popup-zh-large-font.png", fontScale = 1.8f)
    private fun pro3Preview() = PodDevice(
        profileId = "native-preview-pro3", label = "AirPods Pro 3", ble = null,
        profileModel = PodModel.AIRPODS_PRO3, isSystemConnected = true,
        aap = AapPodState(connectionState = AapPodState.ConnectionState.READY, batteries = mapOf(
            AapPodState.BatteryType.LEFT to AapPodState.Battery(AapPodState.BatteryType.LEFT, .8f, AapPodState.ChargingState.CHARGING),
            AapPodState.BatteryType.CASE to AapPodState.Battery(AapPodState.BatteryType.CASE, .6f, AapPodState.ChargingState.NOT_CHARGING),
            AapPodState.BatteryType.RIGHT to AapPodState.Battery(AapPodState.BatteryType.RIGHT, .45f, AapPodState.ChargingState.NOT_CHARGING),
        )),
    )}
