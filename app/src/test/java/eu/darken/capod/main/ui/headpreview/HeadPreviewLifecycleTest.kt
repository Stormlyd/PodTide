package eu.darken.capod.main.ui.headpreview

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.reaction.core.headgesture.HeadGestureController
import eu.darken.capod.reaction.core.headgesture.HeadPreviewUiState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class HeadPreviewLifecycleTest : BaseComposeRobolectricTest() {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun `backgrounding page releases preview token`() {
        val owner = Owner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        val controller = mockk<HeadGestureController>(relaxed = true) {
            every { preview } returns MutableStateFlow(HeadPreviewUiState())
            every { startPreview("p") } returns 42L
        }
        val vm = HeadPreviewViewModel(controller)
        composeRule.setContent { PreviewWrapper {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { HeadPreviewScreenHost("p", vm) }
        } }
        composeRule.onNodeWithTag("head_preview_start").performClick()
        composeRule.runOnUiThread { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        composeRule.waitForIdle()
        verify(exactly = 1) { controller.stopPreview(42L) }
    }

    @Test fun `leaving page disposes and releases preview token`() {
        val shown = mutableStateOf(true)
        val controller = mockk<HeadGestureController>(relaxed = true) {
            every { preview } returns MutableStateFlow(HeadPreviewUiState())
            every { startPreview("p") } returns 43L
        }
        val vm = HeadPreviewViewModel(controller)
        composeRule.setContent { PreviewWrapper { if (shown.value) HeadPreviewScreenHost("p", vm) } }
        composeRule.onNodeWithTag("head_preview_start").performClick()
        composeRule.runOnUiThread { shown.value = false }
        composeRule.waitForIdle()
        verify(exactly = 1) { controller.stopPreview(43L) }
    }
}
