package eu.darken.capod.pods.core.apple.aap.protocol

import eu.darken.capod.reaction.core.headgesture.HeadMotion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeadTrackingSampleTest {
    private fun packet(service: Int = 16, extraTop: ByteArray = byteArrayOf(), size: Int = 58, extraTail: ByteArray = byteArrayOf()): ByteArray {
        val payload = ByteArray(size)
        if (size == 58) {
            // Pinned main's raw 43/45/47 and 51/53 become payload 20/22/24 and 28/30.
            byteArrayOf(0x18, 0xFC.toByte(), 0xD0.toByte(), 0x07, 0xB8.toByte(), 0x0B).copyInto(payload, 20)
            byteArrayOf(0x18, 0xFC.toByte(), 0xD0.toByte(), 0x07).copyInto(payload, 28)
        }
        val nested = byteArrayOf(8, service.toByte(), 0x1A, size.toByte()) + payload
        val top = extraTop + byteArrayOf(0x3A, nested.size.toByte()) + nested + extraTail
        return byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, top.size.toByte(), 0) + top
    }

    @Test fun `services 14 15 16 and reordered multi-byte sequence keep payload offsets`() {
        for (service in 14..16) {
            val sample = HeadMotionCodec.decodeSample(packet(service = service,
                extraTail = byteArrayOf(8, 0xAC.toByte(), 0x02)), 77)
            assertEquals(-1000, sample?.rotationA)
            assertEquals(HeadMotion(-1000, 2000), sample?.motion)
        }
    }

    @Test fun `typed rotation and motion survive variable protobuf envelope`() {
        val sample = HeadMotionCodec.decodeSample(packet(extraTop = byteArrayOf(8, 0x7F)), 1234)
        assertEquals(HeadMotion(-1000, 2000), sample?.motion)
        assertEquals(-1000, sample?.rotationA)
        assertEquals(2000, sample?.rotationB)
        assertEquals(3000, sample?.rotationC)
        assertEquals(1234L, sample?.receivedAtMs)
    }

    @Test fun `other report families and malformed lengths do not become head samples`() {
        listOf(13, 19, 20, 21).forEach { assertNull(HeadMotionCodec.decodeSample(packet(service = it), 1)) }
        assertNull(HeadMotionCodec.decodeSample(packet(size = 57), 1))
        assertNull(HeadMotionCodec.decodeSample(packet(size = 59), 1))
        assertNull(HeadMotionCodec.decodeSample(BuddySensorCodec.configure(16, 40_000, 0), 1))
        val descriptor = byteArrayOf(4, 0, 4, 0, 0x17, 0, 0, 0, 0x10, 0, 6, 0, 0x3A, 4, 8, 16, 0x20, 2)
        assertNull(HeadMotionCodec.decodeSample(descriptor, 1))
        assertNull(HeadMotionCodec.decodeSample(packet().also { it[4] = 0x28 }, 1))
    }
}
