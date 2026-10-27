package eu.darken.capod.findmy.core.auth

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class AppleSrpProtocol(val wireValue: String) {
    S2K("s2k"), S2K_FO("s2k_fo");

    companion object {
        fun fromWire(value: String): AppleSrpProtocol = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported SRP protocol")
    }
}

/** Single-use password digest. Caller owns and clears its original characters.
 * Adapted from pyicloud SrpPassword (MIT); see THIRD_PARTY_NOTICES.md.
 */
internal class AppleSrpPassword(password: CharArray) : AutoCloseable {
    private var digest: ByteArray? = strictUtf8(password).let { encoded ->
        try { sha256(encoded) } finally { encoded.fill(0) }
    }

    /** Synchronous CPU work: future authentication callers must run this off the main thread. */
    @Synchronized
    fun derive(salt: ByteArray, iterations: Int, protocol: AppleSrpProtocol): ByteArray {
        val hash = checkNotNull(digest) { "SRP password is closed" }
        var key: ByteArray? = null
        var block: ByteArray? = null
        var u: ByteArray? = null
        var result: ByteArray? = null
        var completed = false
        try {
            validatePasswordChallenge(salt, iterations)
            key = when (protocol) {
                AppleSrpProtocol.S2K -> hash.copyOf()
                AppleSrpProtocol.S2K_FO -> ByteArray(hash.size * 2).also { hex ->
                    val digits = "0123456789abcdef"
                    hash.forEachIndexed { index, byte ->
                        hex[index * 2] = digits[(byte.toInt() and 255) ushr 4].code.toByte()
                        hex[index * 2 + 1] = digits[byte.toInt() and 15].code.toByte()
                    }
                }
            }
            // PBKDF2's single 32-byte block uses the raw digest bytes, never PBEKeySpec chars.
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            block = salt.copyOf(salt.size + 4).apply { this[lastIndex] = 1 }
            var current = mac.doFinal(block)
            u = current
            val output = current.copyOf()
            result = output
            repeat(iterations - 1) {
                val next = mac.doFinal(current)
                current.fill(0)
                current = next
                u = current
                for (i in output.indices) output[i] = (output[i].toInt() xor current[i].toInt()).toByte()
            }
            completed = true
            return output
        } finally {
            key?.fill(0)
            block?.fill(0)
            u?.fill(0)
            if (!completed) result?.fill(0)
            close()
        }
    }

    @Synchronized
    override fun close() {
        digest?.fill(0)
        digest = null
    }

    override fun toString() = "AppleSrpPassword(redacted)"
}

internal fun validatePasswordChallenge(salt: ByteArray, iterations: Int) {
    require(salt.size in 1..1024) { "Invalid SRP salt length" }
    require(iterations in 1..1_000_000) { "Invalid SRP iteration count" }
}

internal fun sha256(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    values.forEach { update(it) }
    digest()
}

internal fun strictUtf8(chars: CharArray): ByteArray {
    val encoded = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(chars))
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("Invalid UTF-16 input")
    }
    return try {
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } finally {
        if (encoded.hasArray()) encoded.array().fill(0)
    }
}
