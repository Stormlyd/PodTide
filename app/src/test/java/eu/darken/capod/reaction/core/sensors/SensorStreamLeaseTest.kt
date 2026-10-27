package eu.darken.capod.reaction.core.sensors

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import testhelpers.shouldBe

class SensorStreamLeaseTest {
    @Test fun `start still compensates after concurrent bounded stop timed out behind its write`() = runBlocking {
        val wroteStart = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val writeLock = Mutex()
        val calls = mutableListOf<Boolean>()
        val lease = SensorStreamLease { _, enabled ->
            writeLock.withLock {
                calls += enabled
                if (enabled) { wroteStart.complete(Unit); withContext(NonCancellable) { releaseStart.await() } }
            }
        }
        val task = launch { lease.start(SensorTarget("pods", 84)) }
        wroteStart.await()
        task.cancel()
        lease.stop() // Times out waiting for the still-running START's socket write lock.
        releaseStart.complete(Unit)
        task.join()
        calls shouldBe listOf(true, false)
        lease.target shouldBe null
        lease.isReady shouldBe false
    }

    @Test fun `cancel after start write still sends matching stop`() = runBlocking {
        val wroteStart = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<SensorTarget, Boolean>>()
        val target = SensorTarget("pods", 84)
        val lease = SensorStreamLease { destination, enabled ->
            calls += destination to enabled
            if (enabled) { wroteStart.complete(Unit); awaitCancellation() }
        }
        val task = launch { lease.start(target) }
        wroteStart.await()
        task.cancelAndJoin()
        calls shouldBe listOf(target to true, target to false)
        lease.target shouldBe null
        lease.isReady shouldBe false
    }

    @Test fun `parent cancellation during stop preserves remote cleanup until write finishes`() = runBlocking {
        val stopping = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var stopped = false
        val lease = SensorStreamLease { _, enabled ->
            if (!enabled) { stopping.complete(Unit); finish.await(); stopped = true }
        }
        lease.start(SensorTarget("pods", 16))
        val task = launch { lease.stop() }
        stopping.await()
        task.cancel()
        finish.complete(Unit)
        task.join()
        stopped shouldBe true
        lease.isReady shouldBe false
        lease.target shouldBe null
    }
}
