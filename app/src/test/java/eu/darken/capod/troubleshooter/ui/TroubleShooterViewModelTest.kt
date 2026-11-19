package eu.darken.capod.troubleshooter.ui

import eu.darken.capod.monitor.core.ble.BlePodMonitor
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TroubleShooterViewModelTest : BaseTest() {

    private fun BlePodMonitor.CompatOverride.disabledCount() =
        listOf(offloadedFilteringDisabled, offloadedBatchingDisabled, indirectCallback).count { it }

    @Test
    fun `compat combos cover every combination exactly once`() {
        val combos = TroubleShooterViewModel.COMPAT_COMBOS
        combos shouldHaveSize 8
        combos.toSet() shouldHaveSize 8
    }

    @Test
    fun `compat combos start with the no-override baseline`() {
        TroubleShooterViewModel.COMPAT_COMBOS.first() shouldBe
            BlePodMonitor.CompatOverride(false, false, false)
    }

    @Test
    fun `compat combos are ordered fewest-disables-first`() {
        val counts = TroubleShooterViewModel.COMPAT_COMBOS.map { it.disabledCount() }
        counts shouldBe counts.sorted()
    }

    @Test
    fun `batching-only is probed before any combo that also disables filtering`() {
        // The #603 fix: a phone that only needs batching disabled must land on the minimal combo,
        // not on "all off", so we don't needlessly disable hardware filtering too.
        val combos = TroubleShooterViewModel.COMPAT_COMBOS
        val batchingOnly = combos.indexOf(BlePodMonitor.CompatOverride(false, true, false))
        val filteringAndBatching = combos.indexOf(BlePodMonitor.CompatOverride(true, true, false))
        val everything = combos.indexOf(BlePodMonitor.CompatOverride(true, true, true))

        batchingOnly shouldBeLessThan filteringAndBatching
        batchingOnly shouldBeLessThan everything
    }

    @Test
    fun `scan failure distinguishes registration errors from a silent window`() {
        val (rejected, rejectedType) = TroubleShooterViewModel.scanFailure("scan failed (code=3)")
        rejected shouldBe "BLE scan could not run on this phone: scan failed (code=3)"
        rejectedType shouldBe TroubleShooterViewModel.BleState.Result.Failure.Type.PHONE

        val (silent, silentType) = TroubleShooterViewModel.scanFailure(null)
        silent shouldBe "Scan registered but no BLE data arrived within the window."
        silentType shouldBe TroubleShooterViewModel.BleState.Result.Failure.Type.PHONE
        silent shouldNotBe rejected
    }
}
