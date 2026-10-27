package eu.darken.capod.findmy.core.auth

import java.math.BigInteger
import java.security.SecureRandom

/** One Apple web sign-in attempt. Produces BOTH signin/complete proofs, not authenticated account state.
 * Synchronous and intentionally network-free; call off Main when integrating authentication.
 * Pinned pysrp/pyicloud adaptation (MIT): see THIRD_PARTY_NOTICES.md.
 * Controllable byte buffers are cleared best-effort; BigInteger/JCA/JVM copies cannot be erased reliably.
 */
internal class AppleSrpClient private constructor(
    private val usernameHash: ByteArray,
    private val password: AppleSrpPassword,
    private val privateA: ByteArray,
    private val startA: ByteArray,
) : AutoCloseable {
    private var closed = false

    val publicA: ByteArray
        @Synchronized get() {
            checkOpen()
            return startA.copyOf()
        }

    /** Any challenge, including an invalid challenge, consumes this attempt. Returned proof owns its buffers. */
    @Synchronized
    fun processChallenge(salt: ByteArray, publicB: ByteArray, iterations: Int, protocol: AppleSrpProtocol): AppleSrpProof {
        checkOpen()
        val temporary = mutableListOf<ByteArray>()
        fun ByteArray.owned() = also { temporary.add(it) }
        try {
            validatePasswordChallenge(salt, iterations)
            require(publicB.size in 1..257 && (publicB.size != 257 || publicB[0] == 0.toByte())) {
                "Invalid SRP public value length"
            }
            val saltCopy = salt.copyOf().owned()
            val bCopy = publicB.copyOf().owned()
            val b = BigInteger(1, bCopy)
            require(b.mod(N) != BigInteger.ZERO) { "Invalid SRP public value" }
            val bMinimal = unsigned(b).owned()
            val paddedA = padded(startA).owned()
            val paddedB = padded(bMinimal).owned()
            val u = BigInteger(1, sha256(paddedA, paddedB).owned())
            require(u != BigInteger.ZERO) { "Invalid SRP scrambling parameter" }
            val derived = password.derive(saltCopy, iterations, protocol).owned()
            // Apple's x omits username, but retains the colon. M1 below still hashes the exact username.
            val innerX = sha256(byteArrayOf(':'.code.toByte()), derived).owned()
            val x = BigInteger(1, sha256(saltCopy, innerX).owned())
            val verifier = G.modPow(x, N)
            val exponent = BigInteger(1, privateA).add(u.multiply(x))
            val shared = b.subtract(K.multiply(verifier)).mod(N).modPow(exponent, N)
            val sessionKey = sha256(unsigned(shared).owned()).owned()
            // Reference proofs encode A/B/S minimally, unlike the fixed-width k/u hashes.
            val m1 = sha256(N_XOR_G, usernameHash, saltCopy, startA, bMinimal, sessionKey).owned()
            val m2 = sha256(startA, m1, sessionKey).owned()
            return AppleSrpProof(m1, m2)
        } finally {
            temporary.forEach { it.fill(0) }
            close()
        }
    }

    private fun checkOpen() = check(!closed) { "SRP attempt is closed" }

    @Synchronized
    override fun close() {
        closed = true
        usernameHash.fill(0)
        password.close()
        privateA.fill(0)
        startA.fill(0)
    }

    override fun toString() = "AppleSrpClient(redacted)"

    companion object {
        // RFC5054 2048-bit group, fixed locally; never negotiated with a server.
        private val N = BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4" +
                "A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60" +
                "95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF" +
                "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907" +
                "8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861" +
                "60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB" +
                "FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16
        )
        private val G = BigInteger.valueOf(2)
        private val K = BigInteger(1, sha256(unsigned(N), padded(unsigned(G))))
        private val N_XOR_G = sha256(unsigned(N)).let { nHash ->
            val gHash = sha256(padded(unsigned(G)))
            ByteArray(nHash.size) { (nHash[it].toInt() xor gHash[it].toInt()).toByte() }
        }

        fun create(username: String, password: CharArray): AppleSrpClient =
            fromEntropy(username, password, SecureRandom())

        private fun fromEntropy(username: String, password: CharArray, random: SecureRandom): AppleSrpClient {
            val entropy = ByteArray(256)
            try {
                random.nextBytes(entropy)
                entropy[0] = (entropy[0].toInt() or 0x80).toByte()
                return fromPrivate(username, password, entropy)
            } finally {
                entropy.fill(0)
            }
        }

        /** Fixed secret injection for synthetic tests only; bytes are never adjusted or seeded. */
        internal fun forTesting(username: String, password: CharArray, privateA: ByteArray): AppleSrpClient =
            fromPrivate(username, password, privateA)

        /** Entropy source substitution for tests only. Production callers always use create. */
        internal fun forTestingWithEntropy(username: String, password: CharArray, random: SecureRandom): AppleSrpClient =
            fromEntropy(username, password, random)

        private fun fromPrivate(username: String, password: CharArray, privateA: ByteArray): AppleSrpClient {
            require(privateA.size == 256 && privateA.any { it != 0.toByte() }) { "Invalid SRP private value" }
            val ownedA = privateA.copyOf()
            val usernameChars = username.toCharArray()
            var nameHash: ByteArray? = null
            var ownedPassword: AppleSrpPassword? = null
            var start: ByteArray? = null
            var completed = false
            try {
                nameHash = strictUtf8(usernameChars).let { bytes ->
                    try { sha256(bytes) } finally { bytes.fill(0) }
                }
                ownedPassword = AppleSrpPassword(password)
                start = unsigned(G.modPow(BigInteger(1, ownedA), N))
                return AppleSrpClient(nameHash, ownedPassword, ownedA, start).also { completed = true }
            } finally {
                usernameChars.fill('\u0000')
                if (!completed) {
                    ownedA.fill(0)
                    nameHash?.fill(0)
                    ownedPassword?.close()
                    start?.fill(0)
                }
            }
        }

        private fun unsigned(value: BigInteger): ByteArray {
            val signed = value.toByteArray()
            if (signed[0] != 0.toByte()) return signed
            return try { signed.copyOfRange(1, signed.size) } finally { signed.fill(0) }
        }

        private fun padded(bytes: ByteArray) = ByteArray(256).also { bytes.copyInto(it, 256 - bytes.size) }
    }
}

/** Copy-returning signin payload. Calculating m2 is not proof of a server response or an authenticated account. */
internal class AppleSrpProof(m1: ByteArray, m2: ByteArray) : AutoCloseable {
    private var first: ByteArray? = m1.copyOf()
    private var second: ByteArray? = m2.copyOf()
    val m1: ByteArray
        @Synchronized get() = checkNotNull(first) { "SRP proof is closed" }.copyOf()
    val m2: ByteArray
        @Synchronized get() = checkNotNull(second) { "SRP proof is closed" }.copyOf()

    @Synchronized
    override fun close() {
        first?.fill(0)
        second?.fill(0)
        first = null
        second = null
    }

    override fun toString() = "AppleSrpProof(redacted)"
}
