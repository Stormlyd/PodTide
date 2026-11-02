package eu.darken.capod.reaction.core.autoconnect

import testhelpers.shouldBe
import org.junit.jupiter.api.Test

class IdleReconnectPolicyTest {
    @Test
    fun `remote playback calls ringing and unknown never trigger a connection`() {
        for (state in listOf(RemoteUse.MUSIC, RemoteUse.CALL, RemoteUse.RINGING, RemoteUse.HANGING_UP, RemoteUse.UNKNOWN)) {
            IdleReconnectPolicy.allows(state, 100, false) shouldBe false
        }
    }

    @Test
    fun `only fresh idle or disconnected broadcasts allow a connection`() {
        IdleReconnectPolicy.allows(RemoteUse.IDLE, 500, false) shouldBe true
        IdleReconnectPolicy.allows(RemoteUse.DISCONNECTED, 500, false) shouldBe true
        IdleReconnectPolicy.allows(RemoteUse.IDLE, 12_000, false) shouldBe false
        IdleReconnectPolicy.allows(RemoteUse.DISCONNECTED, -100, false) shouldBe false
        IdleReconnectPolicy.allows(RemoteUse.IDLE, 100, true) shouldBe false
    }

    @Test
    fun `frequent advertisements do not cause a reconnection storm`() {
        val limiter = ReconnectAttemptLimiter()
        limiter.tryAcquire("pods", 0) shouldBe true
        limiter.tryAcquire("pods", 100) shouldBe false
        limiter.tryAcquire("pods", 2_000) shouldBe true
        limiter.tryAcquire("pods", 2_100) shouldBe false
        limiter.tryAcquire("pods", 7_000) shouldBe true
        // The ladder is 2 s → 5 s and repeats; there is no 15 s rung anymore.
        limiter.tryAcquire("pods", 8_000) shouldBe false
        limiter.tryAcquire("pods", 9_000) shouldBe true
        limiter.tryAcquire("pods", 10_000) shouldBe false
        limiter.tryAcquire("pods", 14_000) shouldBe true
        limiter.tryAcquire("pods", 15_000) shouldBe false
        limiter.tryAcquire("pods", 16_000) shouldBe true
    }

    @Test
    fun `the removed 15s rung never delays a retry that long`() {
        val limiter = ReconnectAttemptLimiter()
        limiter.tryAcquire("pods", 0) shouldBe true
        limiter.tryAcquire("pods", 2_000) shouldBe true
        limiter.tryAcquire("pods", 7_000) shouldBe true
        // Previously this needed 15 s (t=22_000); the cycle is 2 s → 5 s now.
        limiter.tryAcquire("pods", 9_000) shouldBe true
        limiter.tryAcquire("pods", 14_000) shouldBe true
    }
}
