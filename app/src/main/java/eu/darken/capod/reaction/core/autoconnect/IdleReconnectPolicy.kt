package eu.darken.capod.reaction.core.autoconnect

enum class RemoteUse { DISCONNECTED, IDLE, MUSIC, CALL, RINGING, HANGING_UP, UNKNOWN }

object IdleReconnectPolicy {
    fun allows(state: RemoteUse, ageMillis: Long, connected: Boolean): Boolean =
        !connected && ageMillis in 0..5_000 && (state == RemoteUse.IDLE || state == RemoteUse.DISCONNECTED)
}

class ReconnectAttemptLimiter {
    private data class Attempts(val count: Int, val lastAt: Long)
    private val attempts = mutableMapOf<String, Attempts>()

    /**
     * The ladder is 2 s → 5 s → 2 s → 5 s… The old third rung (15 s) was removed on request: a pod
     * that keeps advertising while still disconnected should be retried within a few seconds, not
     * after a quarter minute. Blind retry storms are already prevented one layer up by
     * [IdleReconnectPolicy], which only lets fresh (≤5 s) IDLE/DISCONNECTED broadcasts through.
     */
    private val delaysMillis = longArrayOf(2_000L, 5_000L)

    fun tryAcquire(address: String, nowMillis: Long): Boolean {
        val previous = attempts[address]
        if (previous == null) {
            attempts[address] = Attempts(0, nowMillis)
            return true
        }
        val delay = delaysMillis[previous.count % delaysMillis.size]
        if (nowMillis - previous.lastAt < delay) return false
        attempts[address] = Attempts((previous.count + 1) % delaysMillis.size, nowMillis)
        return true
    }

    fun reset(address: String) { attempts.remove(address) }
}
