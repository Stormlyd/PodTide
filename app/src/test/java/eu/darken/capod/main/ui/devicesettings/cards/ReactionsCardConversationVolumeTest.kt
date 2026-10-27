package eu.darken.capod.main.ui.devicesettings.cards

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.R
import eu.darken.capod.common.compose.PreviewWrapper
import eu.darken.capod.main.ui.devicesettings.previewFullState
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.profiles.core.ReactionConfig
import eu.darken.capod.reaction.core.conversation.ConversationAction
import eu.darken.capod.reaction.core.conversation.ConversationVolumeMode
import org.junit.Test
import testhelpers.compose.BaseComposeRobolectricTest

class ReactionsCardConversationVolumeTest : BaseComposeRobolectricTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun label(id: Int) = context.getString(id)

    @Test
    fun `selector preserves saved mode and exposes only its slider without writing on render`() {
        val modes = mutableListOf<ConversationVolumeMode>()
        val reductions = mutableListOf<Int>()
        val targets = mutableListOf<Int>()
        composeRule.setContent {
            var config by remember { mutableStateOf(ReactionConfig(
                conversationAction = ConversationAction.LOWER_VOLUME,
                conversationVolumeReduction = 65,
                conversationVolumeMode = ConversationVolumeMode.RELATIVE_REDUCTION,
                conversationVolumeTarget = 23,
            )) }
            val device = previewFullState(isPro = false).device!!.copy(reactions = config)
            PreviewWrapper {
                ReactionsCard(
                    device = device,
                    features = PodModel.Features(hasConversationAwareness = true),
                    isPro = false,
                    onConversationVolumeModeChange = { modes += it; config = config.copy(conversationVolumeMode = it) },
                    onConversationVolumeReductionChange = { reductions += it },
                    onConversationVolumeTargetChange = { targets += it },
                )
            }
        }

        composeRule.onAllNodesWithText(label(R.string.settings_conversation_volume_reduction_label)).assertCountEquals(1)
        composeRule.onAllNodesWithText(label(R.string.settings_conversation_volume_target_label)).assertCountEquals(0)
        composeRule.runOnIdle { check(modes.isEmpty() && reductions.isEmpty() && targets.isEmpty()) }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsEnabled().performTouchInput { click() }
        composeRule.runOnIdle { check(reductions.size == 1 && targets.isEmpty()) { "reductions=$reductions targets=$targets" } }

        composeRule.onNodeWithText(label(R.string.settings_conversation_volume_mode_label)).performClick()
        composeRule.onNodeWithText(label(R.string.settings_conversation_volume_mode_absolute)).performClick()
        composeRule.onAllNodesWithText(label(R.string.settings_conversation_volume_reduction_label)).assertCountEquals(0)
        composeRule.onAllNodesWithText(label(R.string.settings_conversation_volume_target_label)).assertCountEquals(1)
        composeRule.runOnIdle { check(modes == listOf(ConversationVolumeMode.ABSOLUTE_TARGET) && targets.isEmpty()) }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertIsEnabled().performTouchInput { click() }
        composeRule.runOnIdle { check(targets.size == 1) { "targets=$targets reductions=$reductions" } }

        composeRule.onNodeWithText(label(R.string.settings_conversation_volume_mode_label)).performClick()
        composeRule.onNodeWithText(label(R.string.settings_conversation_volume_mode_relative)).performClick()
        composeRule.onAllNodesWithText(label(R.string.settings_conversation_volume_reduction_label)).assertCountEquals(1)
        composeRule.runOnIdle { check(modes.last() == ConversationVolumeMode.RELATIVE_REDUCTION) }
    }
}
