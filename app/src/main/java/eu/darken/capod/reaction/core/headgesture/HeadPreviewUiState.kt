package eu.darken.capod.reaction.core.headgesture

import androidx.annotation.StringRes

data class HeadPreviewUiState(
    val token: Long? = null,
    val requested: Boolean = false,
    val state: HeadPreviewState = HeadPreviewState(),
    val lastSampleAgeMs: Long? = null,
    val nods: Int = 0,
    val shakes: Int = 0,
    val soundEnabled: Boolean = false,
    @StringRes val messageRes: Int? = null,
)
