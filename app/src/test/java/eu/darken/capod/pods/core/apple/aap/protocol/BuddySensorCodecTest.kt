package eu.darken.capod.pods.core.apple.aap.protocol

import eu.darken.capod.reaction.core.headgesture.HeadMotion
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import testhelpers.shouldBe

class BuddySensorCodecTest {
    private fun hex(value: String) = value.split(Regex("\\s+")).filter(String::isNotEmpty).map { it.toInt(16).toByte() }.toByteArray()
    private val heart72 = hex("04 00 04 00 17 00 00 00 10 00 1A 00 08 01 3A 16 08 14 1A 12 00 48 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")

    @Test
    fun `heart interval commands match source derived golden frames for both firmware families`() {
        assertArrayEquals(hex("04 00 04 00 17 00 00 00 10 00 0D 00 42 0B 08 13 10 02 1A 05 01 40 42 0F 00"), BuddySensorCodec.configure(19, 1_000_000, 0))
        assertArrayEquals(hex("04 00 04 00 17 00 00 00 10 00 0D 00 42 0B 08 54 10 02 1A 05 01 40 42 0F 00"), BuddySensorCodec.configure(84, 1_000_000, 0))
        assertArrayEquals(hex("04 00 04 00 17 00 00 00 10 00 0F 00 08 01 42 0B 08 13 10 02 1A 05 01 00 00 00 00"), BuddySensorCodec.configure(19, 0, 1))
    }

    @Test
    fun `heart reports cannot be classified as head motion`() {
        BuddySensorCodec.decodeHeartRate(heart72) shouldBe 72
        BuddySensorCodec.decodeMotion(heart72) shouldBe null
        val oldService = heart72.copyOf().apply { this[17] = 19 }
        BuddySensorCodec.decodeHeartRate(oldService) shouldBe 72
    }

    @Test
    fun `invalid status truncated protobuf and request service are not readings`() {
        BuddySensorCodec.decodeHeartRate(heart72.copyOf().apply { this[37] = 1 }) shouldBe null
        BuddySensorCodec.decodeHeartRate(heart72.copyOf(37)) shouldBe null
        BuddySensorCodec.decodeHeartRate(heart72.copyOf().apply { this[17] = 84 }) shouldBe null
        BuddySensorCodec.decodeHeartRate(hex("04 00 04 00 17 00 00 00 10 00 06 00 08 01 4A 02 08 13")) shouldBe null
    }

    @Test
    fun `typed head report decodes signed axes and never produces heart rate`() {
        val raw = hex("04 00 04 00 17 00 00 00 10 00 42 00 08 01 3A 3E 08 0E 1A 3A " + "00 ".repeat(58))
        raw[48] = 0x34; raw[49] = 0x12
        raw[50] = 0xFE.toByte(); raw[51] = 0xFF.toByte()
        BuddySensorCodec.decodeMotion(raw) shouldBe HeadMotion(4660, -2)
        BuddySensorCodec.decodeHeartRate(raw) shouldBe null
    }

    @Test
    fun `unknown protobuf fields do not shift the sensor payload`() {
        val raw = heart72.copyOf(heart72.size + 2)
        raw[10] = 0x1C
        raw[38] = 0x70; raw[39] = 0x01
        BuddySensorCodec.decodeHeartRate(raw) shouldBe 72
    }

    @Test
    fun `a long heart packet never enters the older motion decoder`() {
        val raw = heart72 + byteArrayOf(0x7A, 0x22) + ByteArray(34)
        raw[10] = 0x3E
        HeadMotionCodec.decode(raw) shouldBe null
    }
}
