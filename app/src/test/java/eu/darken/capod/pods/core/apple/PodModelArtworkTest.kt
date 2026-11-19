package eu.darken.capod.pods.core.apple

import eu.darken.capod.R
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the v0.4.2 display unification: every model renders the same Pro 3 presentation set.
 * This is an appearance contract, not a claim that each model has a dedicated photo.
 */
class PodModelArtworkTest {

    private val knownModels = PodModel.entries.filter { it != PodModel.UNKNOWN }

    @Test
    fun `every known model has an icon of its own`() {
        val generic = knownModels.filter { it.iconRes == R.drawable.device_earbuds_generic_both }
        generic.shouldBeEmpty()
        knownModels.forEach { it.iconRes shouldBeGreaterThan 0 }
    }

    @Test
    fun `dual pod models have all body parts`() {
        knownModels.filter { it.features.hasDualPods }.forEach { model ->
            val art = model.artwork
            art.left shouldBeGreaterThan 0
            art.right shouldBeGreaterThan 0
            art.case shouldBeGreaterThan 0
        }
    }

    @Test
    fun `all models use the unified pro 3 artwork`() {
        PodModel.entries.forEach { model ->
            val art = model.artwork
            art.hero shouldBe R.drawable.popup_pro_hero
            art.popupLeft shouldBe R.drawable.popup_pro_earbud
            art.popupRight shouldBe R.drawable.popup_pro_earbud
            art.popupCase shouldBe R.drawable.popup_pro_case
            art.left shouldBe R.drawable.popup_pro_earbud
            art.right shouldBe R.drawable.popup_pro_earbud
            art.case shouldBe R.drawable.popup_pro_case
            model.usesUnifiedArtwork shouldBe true
        }
    }

    @Test
    fun `artwork source records unified reuse instead of claiming dedicated art`() {
        knownModels.forEach { model ->
            model.artwork.source shouldBe ArtworkSource.UNIFIED
        }
    }

    @Test
    fun `unknown model still renders the unified art but is marked generic`() {
        PodModel.UNKNOWN.artwork.source shouldBe ArtworkSource.GENERIC
        PodModel.UNKNOWN.artwork.hero shouldBe R.drawable.popup_pro_hero
        PodModel.UNKNOWN.iconRes shouldBeGreaterThan 0
    }
}