package eu.darken.capod.pods.core.apple

import androidx.annotation.DrawableRes
import eu.darken.capod.R

/**
 * Single place that decides which artwork a [PodModel] renders, and — just as important — *says out
 * loud* when the art is not really that model's own.
 *
 * Previously every call site kept its own `setOf(PodModel.AIRPODS_PRO, ...)` to decide between the
 * Pro hero art and the per-model icons, and a model silently inherited another model's picture with
 * no record of it. New models added to the enum therefore looked "supported" while showing art that
 * belongs to a different product.
 *
 * The resource ids still live on [PodModel] (they are part of its identity, and the case icon is
 * used outside the points this file controls). This file only owns the *hero* decision and the
 * honesty metadata used by the UI and by PodModelArtworkTest.
 */
enum class ArtworkSource {
    /** The art was drawn for this model. */
    DEDICATED,

    /** Physically the same shell as the linked model (e.g. Gen 2 pods in a Gen 1 case). */
    SHARED_SHELL,

    /** No dedicated art yet: the closest sibling family is reused. Documented, not ideal. */
    NEAREST,

    /** No real art (unknown model): the generic placeholder is used on purpose. */
    GENERIC,
}

data class ModelArtwork(
    @DrawableRes val hero: Int,
    @DrawableRes val left: Int,
    @DrawableRes val right: Int,
    @DrawableRes val case: Int,
    /** Earbud image used next to the left battery gauge (hero art family when shown). */
    @DrawableRes val popupLeft: Int,
    /** Earbud image used next to the right battery gauge. */
    @DrawableRes val popupRight: Int,
    /** Case image used next to the case battery gauge. */
    @DrawableRes val popupCase: Int,
    val source: ArtworkSource,
)

/** Models whose rounded "stem" silhouette art exists and is used as the popup hero image. */
private val PRO_HERO_MODELS = setOf(
    PodModel.AIRPODS_PRO,
    PodModel.AIRPODS_PRO2,
    PodModel.AIRPODS_PRO2_USBC,
    PodModel.AIRPODS_PRO3,
    PodModel.FAKE_AIRPODS_PRO,
    PodModel.FAKE_AIRPODS_PRO2,
)

/**
 * Documented reuse of another model's art. Anything not listed here and not in [PRO_HERO_MODELS]
 * renders its own [PodModel.iconRes].
 */
private val NEAREST_ART: Map<PodModel, PodModel> = mapOf(
    // Gen 4 (open-ear) has no asset yet; Gen 3 is the closest open-ear shape.
    PodModel.AIRPODS_GEN4 to PodModel.AIRPODS_GEN3,
    // Gen 5 renders with the ANC Gen 4 art until dedicated assets exist.
    PodModel.AIRPODS_GEN5 to PodModel.AIRPODS_GEN4_ANC,
    PodModel.AIRPODS_GEN5_WIRELESS to PodModel.AIRPODS_GEN4_ANC,
    // Pro / Pro 3 only have the Pro 2 body art; the popup hero is the Pro hero (see below).
    PodModel.AIRPODS_PRO to PodModel.AIRPODS_PRO2,
    PodModel.AIRPODS_PRO3 to PodModel.AIRPODS_PRO2,
)

/** Documented same-shell reuse (the picture is correct, the model is just newer). */
private val SHARED_SHELL_ART: Map<PodModel, PodModel> = mapOf(
    PodModel.AIRPODS_GEN2 to PodModel.AIRPODS_GEN1,
    PodModel.AIRPODS_MAX_USBC to PodModel.AIRPODS_MAX,
    PodModel.AIRPODS_MAX2 to PodModel.AIRPODS_MAX,
    PodModel.AIRPODS_PRO2_USBC to PodModel.AIRPODS_PRO2,
    PodModel.FAKE_AIRPODS_GEN1 to PodModel.AIRPODS_GEN1,
    PodModel.FAKE_AIRPODS_GEN2 to PodModel.AIRPODS_GEN1,
    PodModel.FAKE_AIRPODS_GEN3 to PodModel.AIRPODS_GEN3,
)

val PodModel.artwork: ModelArtwork
    get() {
        val shared = SHARED_SHELL_ART[this]
        val nearest = NEAREST_ART[this]
        val sourceModel = shared ?: nearest ?: this

        val source = when {
            this == PodModel.UNKNOWN -> ArtworkSource.GENERIC
            shared != null -> ArtworkSource.SHARED_SHELL
            nearest != null -> ArtworkSource.NEAREST
            else -> ArtworkSource.DEDICATED
        }

        val proHero = this in PRO_HERO_MODELS

        val hero = when {
            proHero -> R.drawable.popup_pro_hero
            else -> sourceModel.iconRes
        }

        val left = sourceModel.leftPodIconRes ?: sourceModel.iconRes
        val right = sourceModel.rightPodIconRes ?: sourceModel.iconRes
        val case = sourceModel.caseIconRes ?: sourceModel.iconRes

        return ModelArtwork(
            hero = hero,
            left = left,
            right = right,
            case = case,
            popupLeft = if (proHero) R.drawable.popup_pro_earbud else left,
            popupRight = if (proHero) R.drawable.popup_pro_earbud else right,
            popupCase = if (proHero) R.drawable.popup_pro_case else case,
            source = source,
        )
    }

/** True when the popup should use the full-bleed Pro hero art instead of the model icon. */
val PodModel.usesProHeroArt: Boolean get() = this in PRO_HERO_MODELS