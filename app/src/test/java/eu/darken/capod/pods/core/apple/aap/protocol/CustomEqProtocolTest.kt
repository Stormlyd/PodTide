package eu.darken.capod.pods.core.apple.aap.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CustomEqProtocolTest {
    private val profile = DefaultAapDeviceProfile()
    private val enabled = byteArrayOf(4, 0, 4, 0, 0x63, 0, 5, 0, 1, 2, 0, 50, 100)
    private fun decode(frame: ByteArray) = AapMessage.parse(frame)?.let(profile::decodeSetting)?.second

    @Test
    fun `literal upstream custom EQ opcode is decoded separately from PME`() {
        decode(enabled).shouldNotBeNull() shouldBe AapSetting.CustomEq(2, 0, 50, 100)
        AapMessageType.CUSTOM_EQ.value shouldBe 0x63
        AapMessageType.PME_CONFIG.value shouldBe 0x53
    }

    @Test
    fun `enabled and disabled commands have literal upstream wire bytes`() {
        profile.encodeCommand(AapCommand.SetCustomEq(AapSetting.CustomEq(2, 0, 50, 100))).toList() shouldBe enabled.toList()
        val recommended = byteArrayOf(4, 0, 4, 0, 0x63, 0, 5, 0, 1, 1, 0, 50, 100)
        profile.encodeCommand(AapCommand.SetCustomEq(AapSetting.CustomEq(1, 0, 50, 100))).toList() shouldBe recommended.toList()
        decode(recommended) shouldBe AapSetting.CustomEq(1, 0, 50, 100)
    }

    @Test
    fun `both states and band boundaries roundtrip through actual AAP message parsing`() {
        for (state in 1..2) for (band in listOf(0, 50, 100)) {
            val eq = AapSetting.CustomEq(state, band, 100 - band, 50)
            decode(profile.encodeCommand(AapCommand.SetCustomEq(eq))) shouldBe eq
        }
    }

    @Test
    fun `truncated frames and trailing bytes are rejected`() {
        for (length in 0 until enabled.size) decode(enabled.copyOf(length)).shouldBeNull()
        decode(enabled + byteArrayOf(0)).shouldBeNull()
        decode(enabled + enabled).shouldBeNull()
    }

    @Test
    fun `declared length version state and unsigned band violations are rejected`() {
        for ((index, values) in mapOf(6 to listOf(0, 4, 6, 255), 7 to listOf(1, 255),
            8 to listOf(0, 2, 255), 9 to listOf(0, 3, 255),
            10 to listOf(101, 128, 255), 11 to listOf(101, 128, 255), 12 to listOf(101, 128, 255))) {
            for (value in values) decode(enabled.copyOf().also { it[index] = value.toByte() }).shouldBeNull()
        }
    }

    @Test
    fun `typed EQ rejects invalid values before serialization can wrap them`() {
        for (state in listOf(-1, 0, 3, 258)) shouldThrow<IllegalArgumentException> { AapSetting.CustomEq(state, 50, 50, 50) }
        for (band in listOf(-1, 101, 256)) {
            shouldThrow<IllegalArgumentException> { AapSetting.CustomEq(2, band, 50, 50) }
            shouldThrow<IllegalArgumentException> { AapSetting.CustomEq(2, 50, band, 50) }
            shouldThrow<IllegalArgumentException> { AapSetting.CustomEq(2, 50, 50, band) }
        }
    }
}
