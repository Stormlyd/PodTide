package eu.darken.capod.pods.core.apple.aap.devices

import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.pods.core.apple.aap.protocol.DefaultAapDeviceProfile
import org.junit.jupiter.api.Test
import testhelpers.shouldBe

class SensorCommandSequenceTest {
    @Test fun `heart and head share sequence and stop the same selected service`() {
        fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }
        val profile = DefaultAapDeviceProfile()
        profile.encodeCommand(AapCommand.SetHeartRateTracking(true, 84)).toList() shouldBe
            hex("04 00 04 00 17 00 00 00 10 00 0D 00 42 0B 08 54 10 02 1A 05 01 40 42 0F 00")
        profile.encodeCommand(AapCommand.SetHeadTracking(true, 16)).toList() shouldBe
            hex("04 00 04 00 17 00 00 00 10 00 0F 00 08 01 42 0B 08 10 10 02 1A 05 01 40 9C 00 00")
        profile.encodeCommand(AapCommand.SetHeartRateTracking(false, 84)).toList() shouldBe
            hex("04 00 04 00 17 00 00 00 10 00 0F 00 08 02 42 0B 08 54 10 02 1A 05 01 00 00 00 00")
        profile.encodeCommand(AapCommand.SetHeadTracking(false, 16)).toList() shouldBe
            hex("04 00 04 00 17 00 00 00 10 00 0F 00 08 03 42 0B 08 10 10 02 1A 05 01 00 00 00 00")
    }
}
