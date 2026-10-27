package eu.darken.capod.reaction.core.conversation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConversationVolumeMode {
    @SerialName("RELATIVE_REDUCTION") RELATIVE_REDUCTION,
    @SerialName("ABSOLUTE_TARGET") ABSOLUTE_TARGET,
}
