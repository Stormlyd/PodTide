package eu.darken.capod.profiles.core

import eu.darken.capod.reaction.core.charged.ChargedSlotScope
import eu.darken.capod.reaction.core.conversation.ConversationVolumeMode
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class AppleDeviceProfileSerializationTest : BaseTest() {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy conversation profile retains relative reduction and defaults absolute target`() {
        val profile = json.decodeFromString<AppleDeviceProfile>(
            """{"id":"old","label":"Pods","reactionConversationVolumeReduction":65}"""
        )

        profile.conversationVolumeReduction shouldBe 65
        profile.conversationVolumeMode shouldBe ConversationVolumeMode.RELATIVE_REDUCTION
        profile.conversationVolumeTarget shouldBe 20
        profile.reactionConfig.conversationVolumeMode shouldBe ConversationVolumeMode.RELATIVE_REDUCTION
        profile.reactionConfig.conversationVolumeTarget shouldBe 20
    }

    @Test
    fun `conversation mode and independent values round trip through profile and reaction config`() {
        val profile = AppleDeviceProfile(
            label = "Pods",
            conversationVolumeReduction = 65,
            conversationVolumeMode = ConversationVolumeMode.ABSOLUTE_TARGET,
            conversationVolumeTarget = 23,
        )

        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString<AppleDeviceProfile>(encoded)

        encoded.contains("\"reactionConversationVolumeMode\":\"ABSOLUTE_TARGET\"") shouldBe true
        encoded.contains("\"reactionConversationVolumeTarget\":23") shouldBe true
        decoded.conversationVolumeReduction shouldBe 65
        decoded.conversationVolumeMode shouldBe ConversationVolumeMode.ABSOLUTE_TARGET
        decoded.conversationVolumeTarget shouldBe 23
        decoded.reactionConfig.conversationVolumeReduction shouldBe 65
        decoded.reactionConfig.conversationVolumeMode shouldBe ConversationVolumeMode.ABSOLUTE_TARGET
        decoded.reactionConfig.conversationVolumeTarget shouldBe 23
    }

    @Test
    fun `profiles stored before the charged reaction decode with defaults`() {
        val legacyJson = """
            {
                "id": "test-id",
                "label": "My Pods"
            }
        """.trimIndent()

        val profile = json.decodeFromString<AppleDeviceProfile>(legacyJson)

        profile.notifyWhenCharged shouldBe false
        profile.chargedThreshold shouldBe ReactionConfig.DEFAULT_CHARGED_THRESHOLD
        profile.chargedSlotScope shouldBe ChargedSlotScope.PODS_AND_CASE
        profile.reactionConfig.chargedSlotScope shouldBe ChargedSlotScope.PODS_AND_CASE
    }

    @Test
    fun `profiles stored before the battery estimate toggle default to enabled`() {
        val legacyJson = """
            {
                "id": "test-id",
                "label": "My Pods"
            }
        """.trimIndent()

        val profile = json.decodeFromString<AppleDeviceProfile>(legacyJson)

        profile.batteryEstimateEnabled shouldBe true
    }

    @Test
    fun `battery estimate toggle round-trips`() {
        val profile = AppleDeviceProfile(label = "My Pods", batteryEstimateEnabled = false)

        val decoded = json.decodeFromString<AppleDeviceProfile>(json.encodeToString(profile))

        decoded.batteryEstimateEnabled shouldBe false
    }

    @Test
    fun `charged reaction settings round-trip`() {
        val profile = AppleDeviceProfile(
            label = "My Pods",
            notifyWhenCharged = true,
            chargedThreshold = 80,
            chargedSlotScope = ChargedSlotScope.PODS,
        )

        val decoded = json.decodeFromString<AppleDeviceProfile>(json.encodeToString(profile))

        decoded.notifyWhenCharged shouldBe true
        decoded.chargedThreshold shouldBe 80
        decoded.chargedSlotScope shouldBe ChargedSlotScope.PODS
        decoded.reactionConfig shouldBe profile.reactionConfig
    }

    @Test
    fun `profiles stored before the low case reminder decode with defaults`() {
        val legacyJson = """
            {
                "id": "test-id",
                "label": "My Pods"
            }
        """.trimIndent()

        val profile = json.decodeFromString<AppleDeviceProfile>(legacyJson)

        profile.notifyWhenCaseLow shouldBe false
        profile.caseLowThreshold shouldBe 20
        profile.reactionConfig.notifyWhenCaseLow shouldBe false
        profile.reactionConfig.caseLowThreshold shouldBe 20
    }

    @Test
    fun `low case reminder settings round-trip`() {
        val profile = AppleDeviceProfile(
            label = "My Pods",
            notifyWhenCaseLow = true,
            caseLowThreshold = 40,
        )

        val decoded = json.decodeFromString<AppleDeviceProfile>(json.encodeToString(profile))

        decoded.notifyWhenCaseLow shouldBe true
        decoded.caseLowThreshold shouldBe 40
        decoded.reactionConfig shouldBe profile.reactionConfig
    }
}
