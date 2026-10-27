package eu.darken.capod.reaction.core.autoconnect

enum class RemoteUse { DISCONNECTED, IDLE, MUSIC, CALL, RINGING, HANGING_UP, UNKNOWN }

object IdleReconnectPolicy {
    fun allows(state: RemoteUse, ageMillis: Long, connected: Boolean): Boolean =
        !connected && ageMillis in 0..5_000 && (state == RemoteUse.IDLE || state == RemoteUse.DISCONNECTED)
}

class ReconnectAttemptLimiter {
    private data class Attempts(val count: Int, val lastAt: Long)
    private val attempts = mutableMapOf<String, Attempts>()

    fun tryAcquire(address: String, nowMillis: Long): Boolean {
        val previous = attempts[address]
        if (previous == null) {
            attempts[address] = Attempts(1, nowMillis)
            return true
        }
        val delay = when (previous.count) { 1 -> 2_000; 2 -> 5_000; else -> 60_000 }
        if (nowMillis - previous.lastAt < delay) return false
        attempts[address] = Attempts(if (previous.count >= 3) 1 else previous.count + 1, nowMillis)
        return true
    }

    fun reset(address: String) { attempts.remove(address) }
}
