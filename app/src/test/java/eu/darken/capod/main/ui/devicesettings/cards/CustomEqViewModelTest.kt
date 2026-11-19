package eu.darken.capod.main.ui.devicesettings.cards

import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CustomEqViewModelTest {
    @Test
    fun `unrelated readback cannot overwrite an edited draft and reset only changes bands`() {
        val model = CustomEqViewModel(AapSetting.CustomEq(2, 20, 30, 40))
        model.setLow(77)
        model.observe(AapSetting.CustomEq(1, 1, 2, 3))
        model.draft shouldBe AapSetting.CustomEq(2, 77, 30, 40)
        model.reset()
        model.draft shouldBe AapSetting.CustomEq(2, 50, 50, 50)
        model.selectCustom(false)
        model.draft shouldBe AapSetting.CustomEq(1, 50, 50, 50)
    }

    @Test
    fun `untouched draft follows the first firmware readback`() {
        val model = CustomEqViewModel(null)
        model.observe(AapSetting.CustomEq(2, 0, 50, 100))
        model.draft shouldBe AapSetting.CustomEq(2, 0, 50, 100)
    }

    @Test
    fun `confirmed apply releases draft and later external change updates sliders`() {
        val original = AapSetting.CustomEq(2, 20, 30, 40)
        val applied = original.copy(low = 77)
        val model = CustomEqViewModel(original)
        model.setLow(77)
        model.markApplying(confirmationRevision = 4)

        // An equal report without a fresh confirmation revision must keep the draft locked.
        model.observe(applied, confirmationRevision = 4)
        model.edited shouldBe true
        model.observe(applied, confirmationRevision = 5)
        model.edited shouldBe false

        val external = AapSetting.CustomEq(1, 1, 2, 3)
        model.observe(external, confirmationRevision = 5)
        model.draft shouldBe external
    }

    @Test
    fun `new unsent edit survives confirmation of an earlier apply`() {
        val original = AapSetting.CustomEq(2, 20, 30, 40)
        val model = CustomEqViewModel(original)
        model.setLow(77)
        model.markApplying(confirmationRevision = 0)
        model.setMid(88)

        model.observe(original.copy(low = 77), confirmationRevision = 1)
        model.edited shouldBe true
        model.draft shouldBe AapSetting.CustomEq(2, 77, 88, 40)
        model.observe(AapSetting.CustomEq(1, 1, 2, 3), confirmationRevision = 1)
        model.draft shouldBe AapSetting.CustomEq(2, 77, 88, 40)

        model.markApplying(confirmationRevision = 1)
        model.observe(model.draft, confirmationRevision = 2)
        model.edited shouldBe false
    }

    @Test
    fun `late report after timeout is surfaced without claiming a fresh confirmation`() {
        val original = AapSetting.CustomEq(2, 20, 30, 40)
        val applied = original.copy(low = 77)
        val model = CustomEqViewModel(original)
        model.setLow(77)
        model.markApplying(confirmationRevision = 0)

        // The request window timed out; an older identical report alone must not clear the draft.
        model.observe(original, confirmationRevision = 0, unconfirmed = true)
        model.edited shouldBe true
        model.lateReport shouldBe false

        // A later matching report while the unconfirmed target is still tracked is useful
        // evidence, but it must not be treated as confirmation of a newer request.
        model.observe(applied, confirmationRevision = 0, unconfirmed = true)
        model.edited shouldBe false
        model.lateReport shouldBe true
        model.awaitingReport shouldBe false
    }
}
