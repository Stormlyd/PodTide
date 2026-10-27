package eu.darken.capod.findmy.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test

internal object SrpVectors {
    val root = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/findmy/srp-reference-vectors.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
    val all get() = root.getValue("vectors").jsonArray.map { it.jsonObject }
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    fun JsonObject.bytes(key: String) = text(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun JsonObject.client() = AppleSrpClient.forTesting(text("username"), text("synthetic_password").toCharArray(), bytes("private_a_hex"))
    fun JsonObject.proof(client: AppleSrpClient) = client.processChallenge(bytes("salt_hex"), bytes("public_B_hex"),
        text("iterations").toInt(), AppleSrpProtocol.fromWire(text("protocol")))
}

internal class AppleSrpVectorsTest {
    @Test
    fun `short sign-bit A uses reference padding for u and minimal encoding for proofs`() = with(SrpVectors) {
        val edge = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/findmy/srp-edge-reference.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val base = all.first()
        AppleSrpClient.forTesting(base.text("username"), base.text("synthetic_password").toCharArray(), edge.bytes("private_a_hex")).use { client ->
            assertArrayEquals(edge.bytes("public_A_hex"), client.publicA)
            base.proof(client).use {
                assertArrayEquals(edge.bytes("M1_hex"), it.m1)
                assertArrayEquals(edge.bytes("M2_hex"), it.m2)
            }
        }
    }

    @TestFactory
    fun `raw byte password derivation matches independent Python vectors`() = with(SrpVectors) {
        all.mapIndexed { index, v -> dynamicTest("password vector $index") {
            AppleSrpPassword(v.text("synthetic_password").toCharArray()).use { password ->
                assertArrayEquals(v.bytes("derived_password_hex"), password.derive(v.bytes("salt_hex"),
                    v.text("iterations").toInt(), AppleSrpProtocol.fromWire(v.text("protocol"))))
            }
        } }
    }

    @TestFactory
    fun `A M1 and M2 match independent Python transcripts`() = with(SrpVectors) {
        all.mapIndexed { index, v -> dynamicTest("proof vector $index") {
            v.client().use { client ->
                assertArrayEquals(v.bytes("public_A_hex"), client.publicA)
                v.proof(client).use { proof ->
                    assertArrayEquals(v.bytes("M1_hex"), proof.m1)
                    assertArrayEquals(v.bytes("M2_hex"), proof.m2)
                }
            }
        } }
    }
}
