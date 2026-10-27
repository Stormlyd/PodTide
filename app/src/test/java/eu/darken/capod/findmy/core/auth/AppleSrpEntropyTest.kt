package eu.darken.capod.findmy.core.auth

import eu.darken.capod.findmy.core.auth.SrpVectors.bytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.SecureRandom

internal class AppleSrpEntropyTest {
    private val reference = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/findmy/srp-boundary-vectors.json"))
        .bufferedReader().use { it.readText() }).jsonObject

    @Test
    fun `production entropy path requests new 256 bytes sets high bit and clears source`() {
        val random = object : SecureRandom() {
            val buffers = mutableListOf<ByteArray>()
            override fun nextBytes(bytes: ByteArray) {
                assertEquals(256, bytes.size)
                bytes.fill(0)
                bytes[bytes.lastIndex] = buffers.size.toByte()
                buffers.add(bytes)
            }
        }
        repeat(2) { index ->
            AppleSrpClient.forTestingWithEntropy("synthetic", charArrayOf('x'), random).use {
                assertArrayEquals(reference.bytes("entropy_$index"), it.publicA)
            }
        }
        assertEquals(2, random.buffers.size)
        random.buffers.forEach { assertArrayEquals(ByteArray(256), it) }
    }

    @Test
    fun `both iteration endpoints and maximum salt match Python hashlib`() {
        listOf(1, 1_000_000).forEach { iterations ->
            AppleSrpPassword(charArrayOf('x')).use {
                assertArrayEquals(reference.bytes("iterations_$iterations"),
                    it.derive(ByteArray(1024), iterations, AppleSrpProtocol.S2K))
            }
        }
    }
}
