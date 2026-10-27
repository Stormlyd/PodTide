package eu.darken.capod.reaction.core.sensors

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SensorTarget(val address: String, val service: Int)

class SensorStreamLease(private val send: suspend (SensorTarget, Boolean) -> Unit) {
    var target: SensorTarget? = null; private set
    var isReady: Boolean = false; private set
    suspend fun start(next: SensorTarget) {
        currentCoroutineContext().ensureActive()
        check(target == null)
        target = next // Own a potentially written START before entering a suspending socket write.
        try {
            send(next, true)
            currentCoroutineContext().ensureActive()
            isReady = true
        } catch (failure: Exception) {
            // A concurrent bounded STOP may already have cleared shared ownership. This START
            // still owns its local target and compensates after its write has finally finished.
            isReady = false
            stopTarget(next)
            if (target == next) target = null
            throw failure
        }
    }
    suspend fun stop() {
        val old = target ?: return
        isReady = false // Stop local actions immediately, retaining the remote cleanup target.
        try { stopTarget(old) }
        finally { if (target == old) target = null }
    }

    private suspend fun stopTarget(old: SensorTarget) {
        try {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { send(old, false) } }
        } catch (_: Exception) {
            // A disconnected socket cannot accept STOP. The attempt is bounded and best effort.
        }
    }
}
