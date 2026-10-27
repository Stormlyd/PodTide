package eu.darken.capod.main.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Independent styles. Enabling a style never synthesizes a popup event. */
data class PopUpStylePolicy(val showBottomPopup: Boolean = true, val showTopPopup: Boolean = false)

val GeneralSettings.popUpStylePolicy: Flow<PopUpStylePolicy>
    get() = combine(showBottomPopup.flow, showTopPopup.flow, ::PopUpStylePolicy).distinctUntilChanged()
