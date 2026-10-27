package eu.darken.capod.pods.core.apple.aap.protocol

import eu.darken.capod.reaction.core.headgesture.HeadMotion
import testhelpers.shouldBe
import org.junit.jupiter.api.Test

class HeadMotionCodecTest {
    @Test
    fun `signed motion fields are relative to the typed sensor payload`() {
        val raw = ByteArray(78)
        byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, 0x42, 0, 8, 1, 0x3A, 0x3E, 8, 0x0E, 0x1A, 0x3A).copyInto(raw)
        raw[48] = 0x18; raw[49] = 0xFC.toByte()
        raw[50] = 0xD0.toByte(); raw[51] = 0x07
        HeadMotionCodec.decode(raw) shouldBe HeadMotion(-1000, 2000)
        HeadMotionCodec.decode(raw.copyOf(54)) shouldBe null
        raw[4] = 0x28
        HeadMotionCodec.decode(raw) shouldBe null
    }

    @Test
    fun `tracking command uses upstream buddy channel not gyro information opcode`() {
        fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }
        BuddySensorCodec.configure(16, 40_000, 0).toList() shouldBe hex("04 00 04 00 17 00 00 00 10 00 0D 00 42 0B 08 10 10 02 1A 05 01 40 9C 00 00")
        BuddySensorCodec.configure(16, 0, 1).toList() shouldBe hex("04 00 04 00 17 00 00 00 10 00 0F 00 08 01 42 0B 08 10 10 02 1A 05 01 00 00 00 00")
    }
}
