package eu.darken.capod.pods.core.apple

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Bluetooth reports the same physical earbuds as different [PodModel] entries depending on the
 * charging case revision. Identity matching must treat those entries as one product, otherwise one
 * pair shows up as "configured but offline" plus a stranger in the unmatched section.
 */
class PodModelInterchangeTest : BaseTest() {

    @Test
    fun `gen5 case revisions are interchangeable`() {
        PodModel.AIRPODS_GEN5.isInterchangeableWith(PodModel.AIRPODS_GEN5_WIRELESS) shouldBe true
        PodModel.AIRPODS_GEN5_WIRELESS.isInterchangeableWith(PodModel.AIRPODS_GEN5) shouldBe true
        PodModel.AIRPODS_GEN5.interchangeableModels shouldBe
                setOf(PodModel.AIRPODS_GEN5, PodModel.AIRPODS_GEN5_WIRELESS)
    }

    @Test
    fun `every model is interchangeable with itself`() {
        PodModel.entries.forEach { model ->
            model.isInterchangeableWith(model) shouldBe true
        }
    }

    @Test
    fun `distinct product lines stay separate`() {
        // Same-shape or same-family-looking products must not silently share an identity: a profile
        // configured for one must never absorb the other's broadcasts.
        PodModel.AIRPODS_GEN4.isInterchangeableWith(PodModel.AIRPODS_GEN4_ANC) shouldBe false
        PodModel.AIRPODS_PRO.isInterchangeableWith(PodModel.AIRPODS_PRO2) shouldBe false
        PodModel.AIRPODS_GEN1.isInterchangeableWith(PodModel.AIRPODS_GEN2) shouldBe false
        PodModel.AIRPODS_MAX.isInterchangeableWith(PodModel.AIRPODS_MAX2) shouldBe false
        PodModel.AIRPODS_GEN5.isInterchangeableWith(PodModel.AIRPODS_GEN4) shouldBe false
        PodModel.AIRPODS_GEN5.isInterchangeableWith(PodModel.UNKNOWN) shouldBe false
    }

    @Test
    fun `singleton models declare no family`() {
        PodModel.AIRPODS_PRO3.interchangeableModels.shouldBeEmpty()
        PodModel.UNKNOWN.interchangeableModels.shouldBeEmpty()
    }
}
