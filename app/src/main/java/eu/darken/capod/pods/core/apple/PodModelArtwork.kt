package eu.darken.capod.pods.core.apple

import androidx.annotation.DrawableRes
import eu.darken.capod.R

/**
 * Single place that decides which artwork a [PodModel] renders.
 *
 * Per the 0.4.2 feedback, every model now uses the same Pro 3 presentation set
 * (`popup_pro_hero`, `popup_pro_earbud`, `popup_pro_case`). This is a deliberate *display*
 * unification requested by the user, not an identity change: the real model, protocol
 * capabilities, labels and device codes stay untouched.
 *
 * [ArtworkSource.UNIFIED] records that the art is shared display artwork rather than a
 * dedicated product photo, so nobody later mistakes it for per-model material.
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

    /** One shared presentation set (currently Pro 3) is used for every model. */
    UNIFIED,
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

val PodModel.artwork: ModelArtwork
    get() = ModelArtwork(
        hero = R.drawable.popup_pro_hero,
        left = R.drawable.popup_pro_earbud,
        right = R.drawable.popup_pro_earbud,
        case = R.drawable.popup_pro_case,
        popupLeft = R.drawable.popup_pro_earbud,
        popupRight = R.drawable.popup_pro_earbud,
        popupCase = R.drawable.popup_pro_case,
        source = if (this == PodModel.UNKNOWN) ArtworkSource.GENERIC else ArtworkSource.UNIFIED,
    )

/** Every model renders the unified Pro 3 hero illustration and banner layout. */
val PodModel.usesUnifiedArtwork: Boolean get() = true

@Deprecated("Use usesUnifiedArtwork", ReplaceWith("usesUnifiedArtwork"))
val PodModel.usesProHeroArt: Boolean get() = usesUnifiedArtwork