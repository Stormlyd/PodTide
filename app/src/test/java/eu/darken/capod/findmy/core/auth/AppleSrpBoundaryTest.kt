package eu.darken.capod.findmy.core.auth

import eu.darken.capod.findmy.core.auth.SrpVectors.bytes
import eu.darken.capod.findmy.core.auth.SrpVectors.client
import eu.darken.capod.findmy.core.auth.SrpVectors.proof
import eu.darken.capod.findmy.core.auth.SrpVectors.text
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

internal class AppleSrpBoundaryTest {
    private val v get() = SrpVectors.all.first()

    @TestFactory
    fun `invalid challenges fail and consume the attempt`() = listOf(
        Triple(byteArrayOf(), byteArrayOf(1), 1),
        Triple(ByteArray(1025), byteArrayOf(1), 1),
        Triple(byteArrayOf(1), byteArrayOf(), 1),
        Triple(byteArrayOf(1), byteArrayOf(0), 1),
        Triple(byteArrayOf(1), SrpVectors.root.getValue("settings").let { (it as kotlinx.serialization.json.JsonObject).bytes("N_hex") }, 1),
        Triple(byteArrayOf(1), ByteArray(258), 1),
        Triple(byteArrayOf(1), ByteArray(257) { 1 }, 1),
        Triple(byteArrayOf(1), byteArrayOf(1), 0),
        Triple(byteArrayOf(1), byteArrayOf(1), -1),
        Triple(byteArrayOf(1), byteArrayOf(1), 1_000_001),
        Triple(byteArrayOf(1), byteArrayOf(1), Int.MAX_VALUE),
    ).mapIndexed { index, (salt, b, iterations) -> dynamicTest("invalid challenge $index") {
        v.client().use { client ->
            assertThrows(IllegalArgumentException::class.java) { client.processChallenge(salt, b, iterations, AppleSrpProtocol.S2K) }
            assertThrows(IllegalStateException::class.java) { v.proof(client) }
            assertThrows(IllegalStateException::class.java) { client.publicA }
        }
    } }

    @Test
    fun `known protocols are exact and unknown wire values are redacted`() {
        assertEquals(AppleSrpProtocol.S2K, AppleSrpProtocol.fromWire("s2k"))
        assertEquals(AppleSrpProtocol.S2K_FO, AppleSrpProtocol.fromWire("s2k_fo"))
        listOf("", "S2K", " s2k", "secret-unsupported-protocol").forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) { AppleSrpProtocol.fromWire(value) }
            if (value.isNotEmpty()) assertFalse(error.toString().contains(value))
        }
    }

    @Test
    fun `leading zero B encodes the same integer in proofs`() {
        v.client().use { client ->
            client.processChallenge(v.bytes("salt_hex"), byteArrayOf(0) + v.bytes("public_B_hex"), 1000, AppleSrpProtocol.S2K).use {
                assertArrayEquals(v.bytes("M1_hex"), it.m1)
                assertArrayEquals(v.bytes("M2_hex"), it.m2)
            }
        }
    }

    @Test
    fun `public A is minimal unsigned including a high sign bit`() {
        val exponent = ByteArray(256).apply { this[lastIndex] = 7 }
        AppleSrpClient.forTesting("synthetic", charArrayOf('x'), exponent).use {
            assertArrayEquals(byteArrayOf(0x80.toByte()), it.publicA)
        }
    }

    @Test
    fun `private exponent must be nonzero and exactly 256 bytes`() {
        listOf(byteArrayOf(), byteArrayOf(1), ByteArray(255) { 1 }, ByteArray(257) { 1 }, ByteArray(256)).forEach {
            assertThrows(IllegalArgumentException::class.java) { AppleSrpClient.forTesting("synthetic", charArrayOf('x'), it) }
        }
    }

    @Test
    fun `caller mutation cannot alter retained password private exponent or public A`() {
        val password = v.text("synthetic_password").toCharArray()
        val exponent = v.bytes("private_a_hex")
        AppleSrpClient.forTesting(v.text("username"), password, exponent).use { client ->
            assertArrayEquals(v.text("synthetic_password").toCharArray(), password)
            assertArrayEquals(v.bytes("private_a_hex"), exponent)
            password.fill('!')
            exponent.fill(0)
            client.publicA.fill(0)
            assertArrayEquals(v.bytes("public_A_hex"), client.publicA)
            v.proof(client).use { assertArrayEquals(v.bytes("M1_hex"), it.m1) }
        }
    }

    @Test
    fun `challenge inputs and returned proof copies stay caller owned`() {
        val salt = v.bytes("salt_hex")
        val b = v.bytes("public_B_hex")
        v.client().use { client ->
            client.processChallenge(salt, b, 1000, AppleSrpProtocol.S2K).use { proof ->
                assertArrayEquals(v.bytes("salt_hex"), salt)
                assertArrayEquals(v.bytes("public_B_hex"), b)
                salt.fill(0)
                b.fill(0)
                proof.m1.fill(0)
                proof.m2.fill(0)
                assertArrayEquals(v.bytes("M1_hex"), proof.m1)
                assertArrayEquals(v.bytes("M2_hex"), proof.m2)
            }
        }
    }

    @Test
    fun `success consumes client and proof close revokes access but not exported copies`() {
        val client = v.client()
        val proof = v.proof(client)
        val exported = proof.m1
        assertThrows(IllegalStateException::class.java) { v.proof(client) }
        assertThrows(IllegalStateException::class.java) { client.publicA }
        client.close()
        assertArrayEquals(v.bytes("M2_hex"), proof.m2)
        proof.close()
        proof.close()
        assertThrows(IllegalStateException::class.java) { proof.m1 }
        assertThrows(IllegalStateException::class.java) { proof.m2 }
        assertArrayEquals(v.bytes("M1_hex"), exported)
    }

    @Test
    fun `explicit close consumes an unused attempt`() {
        val client = v.client()
        client.close()
        client.close()
        assertThrows(IllegalStateException::class.java) { client.publicA }
        assertThrows(IllegalStateException::class.java) { v.proof(client) }
    }

    @Test
    fun `diagnostics do not expose account password or proof material`() {
        v.client().use { client ->
            v.proof(client).use { proof ->
                val error = assertThrows(IllegalStateException::class.java) { v.proof(client) }
                val diagnostics = listOf(client.toString(), proof.toString(), error.toString()).joinToString()
                listOf("username", "synthetic_password", "private_a_hex", "derived_password_hex", "M1_hex", "M2_hex").forEach {
                    assertFalse(diagnostics.contains(v.text(it)))
                }
            }
        }
    }

    @TestFactory
    fun `unpaired UTF16 is rejected without replacement`() = listOf("\uD800", "\uDC00", "a\uD800b").mapIndexed { index, text ->
        dynamicTest("invalid UTF16 $index") {
            assertThrows(IllegalArgumentException::class.java) { AppleSrpClient.create(text, charArrayOf('x')) }
            assertThrows(IllegalArgumentException::class.java) { AppleSrpClient.create("synthetic", text.toCharArray()) }
        }
    }

    @Test
    fun `real username affects proof without normalization`() {
        val actual = AppleSrpClient.forTesting(v.text("username").uppercase(), v.text("synthetic_password").toCharArray(), v.bytes("private_a_hex")).use { client ->
            v.proof(client).use { it.m1 }
        }
        assertFalse(v.bytes("M1_hex").contentEquals(actual))
    }

    @Test
    fun `production creation gets fresh ephemeral entropy`() {
        AppleSrpClient.create("synthetic", charArrayOf('x')).use { first ->
            AppleSrpClient.create("synthetic", charArrayOf('x')).use { second ->
                assertTrue(first.publicA.isNotEmpty())
                assertFalse(first.publicA.contentEquals(second.publicA))
            }
        }
    }

    @Test
    fun `password holder derives once and keeps caller chars intact`() {
        val chars = v.text("synthetic_password").toCharArray()
        val password = AppleSrpPassword(chars)
        chars.fill('!')
        assertArrayEquals(v.bytes("derived_password_hex"), password.derive(v.bytes("salt_hex"), 1000, AppleSrpProtocol.S2K))
        assertThrows(IllegalStateException::class.java) { password.derive(v.bytes("salt_hex"), 1, AppleSrpProtocol.S2K) }
        password.close()
        assertFalse(password.toString().contains(v.text("synthetic_password")))
    }

    @Test
    fun `invalid password derivation consumes retained hash`() {
        AppleSrpPassword(charArrayOf('x')).use { password ->
            assertThrows(IllegalArgumentException::class.java) { password.derive(byteArrayOf(), 1, AppleSrpProtocol.S2K) }
            assertThrows(IllegalStateException::class.java) { password.derive(byteArrayOf(1), 1, AppleSrpProtocol.S2K) }
        }
    }
}
