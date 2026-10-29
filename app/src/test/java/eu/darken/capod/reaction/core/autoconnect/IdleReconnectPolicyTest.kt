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
        limiter.tryAcquire("pods", 17_000) shouldBe false
        limiter.tryAcquire("pods", 22_000) shouldBe true
    }
}
