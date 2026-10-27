package eu.darken.capod.main.ui.headpreview

import androidx.lifecycle.ViewModelStore
import eu.darken.capod.reaction.core.headgesture.HeadGestureController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class HeadPreviewViewModelTest {
    @Test fun `clearing page releases only its preview token`() {
        val controller = mockk<HeadGestureController>(relaxed = true) {
            every { startPreview("p") } returns 77L
        }
        val vm = HeadPreviewViewModel(controller)
        val store = ViewModelStore()
        store.put("preview", vm)
        vm.start("p")
        vm.start("p")
        verify(exactly = 1) { controller.startPreview("p") }
        store.clear()
        verify(exactly = 1) { controller.stopPreview(77L) }
    }
}
