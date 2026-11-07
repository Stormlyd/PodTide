package eu.darken.capod.pods.core.apple

import eu.darken.capod.R
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Guards the P2-06 promise: a model either has its own artwork, or the reuse is explicitly listed
 * in [ArtworkSource]. Adding a model to [PodModel] without art must fail here instead of silently
 * rendering another product's picture.
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
    fun `borrowed artwork is documented and complete`() {
        val nearest = PodModel.entries
            .filter { it.artwork.source == ArtworkSource.NEAREST }
            .toSet()

        nearest shouldBe setOf(
            PodModel.AIRPODS_GEN4,
            PodModel.AIRPODS_GEN5,
            PodModel.AIRPODS_GEN5_WIRELESS,
            PodModel.AIRPODS_PRO,
            PodModel.AIRPODS_PRO3,
        )
    }

    @Test
    fun `same shell models are documented`() {
        val shared = PodModel.entries
            .filter { it.artwork.source == ArtworkSource.SHARED_SHELL }
            .toSet()

        shared shouldBe setOf(
            PodModel.AIRPODS_GEN2,
            PodModel.AIRPODS_MAX_USBC,
            PodModel.AIRPODS_MAX2,
            PodModel.AIRPODS_PRO2_USBC,
            PodModel.FAKE_AIRPODS_GEN1,
            PodModel.FAKE_AIRPODS_GEN2,
            PodModel.FAKE_AIRPODS_GEN3,
        )
    }

    @Test
    fun `pro hero art is used only by pro models`() {
        PodModel.entries.forEach { model ->
            val usesProHero = model.artwork.hero == R.drawable.popup_pro_hero
            usesProHero shouldBe model.usesProHeroArt
        }

        val proHero = PodModel.entries.filter { it.usesProHeroArt }.toSet()
        proHero shouldBe setOf(
            PodModel.AIRPODS_PRO,
            PodModel.AIRPODS_PRO2,
            PodModel.AIRPODS_PRO2_USBC,
            PodModel.AIRPODS_PRO3,
            PodModel.FAKE_AIRPODS_PRO,
            PodModel.FAKE_AIRPODS_PRO2,
        )
    }

    @Test
    fun `only pro models may render the hero as a banner`() {
        // A square 96dp line icon stretched into the card banner is what made the standard series
        // look like "a giant green logo"; only models with a real product illustration may do so.
        PodModel.entries.forEach { model ->
            model.artwork.hasIllustration shouldBe model.usesProHeroArt
            model.artwork.heroKind shouldBe
                    if (model.usesProHeroArt) HeroKind.PHOTO else HeroKind.ICON
        }
        PodModel.AIRPODS_GEN5.artwork.heroKind shouldBe HeroKind.ICON
        PodModel.AIRPODS_GEN5_WIRELESS.artwork.heroKind shouldBe HeroKind.ICON
    }

    @Test
    fun `unknown model uses the generic placeholder`() {
        PodModel.UNKNOWN.artwork.source shouldBe ArtworkSource.GENERIC
        PodModel.UNKNOWN.artwork.hero shouldBe R.drawable.device_earbuds_generic_both
        PodModel.UNKNOWN.iconRes shouldNotBe 0
    }
}