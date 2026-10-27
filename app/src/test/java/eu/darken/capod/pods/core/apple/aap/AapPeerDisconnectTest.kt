package eu.darken.capod.pods.core.apple.aap

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import eu.darken.capod.common.bluetooth.l2cap.L2capSocketFactory
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.engine.AapConnection
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.pods.core.apple.aap.protocol.AapDeviceProfile
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.TestTimeSource
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** The Android socket is substituted; framing, profile, engine and manager are real. */
class AapPeerDisconnectTest : BaseTest() {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val device = mockk<BluetoothDevice> { every { address } returns this@AapPeerDisconnectTest.address }
    private val profile = AapDeviceProfile.forModel(PodModel.AIRPODS_PRO3)

    @Test
    fun `peer service disconnect clears READY and pending even when input stays open`() = runBlocking {
        val wire = Wire()
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val factory = mockk<L2capSocketFactory> { every { createSocket(any(), any()) } returns wire.socket }
        val connection = AapConnection(device, profile, factory, TestTimeSource())
        val rejections = CopyOnWriteArrayList<AapCommand>()
        val offRejections = CopyOnWriteArrayList<Unit>()
        scope.launch { connection.settingRejected.collect { rejections += it } }
        scope.launch { connection.offRejected.collect { offRejections += it } }
        try {
            connection.connect(scope)
            wire.ready()
            awaitCondition { connection.state.value.setting<AapSetting.AncMode>()?.current == AapSetting.AncMode.Value.ON }
            connection.send(AapCommand.SetAncMode(AapSetting.AncMode.Value.OFF))
            connection.state.value.pendingAncMode shouldBe AapSetting.AncMode.Value.OFF
            val writesBefore = wire.writes.size

            // Synthetic complete type2/service4/status0 frame; no EOF is sent by the peer.
            wire.receive(byteArrayOf(2, 0, 4, 0, 0, 0))
            awaitCondition { connection.state.value.connectionState == AapPodState.ConnectionState.DISCONNECTED }
            assertSoftly {
                connection.state.value.connectionState shouldBe AapPodState.ConnectionState.DISCONNECTED
                connection.state.value.pendingAncMode shouldBe null
                wire.closeCount.get() shouldBe 1
                shouldThrow<IllegalStateException> { connection.send(AapCommand.SetAncMode(AapSetting.AncMode.Value.ON)) }
                wire.writes.size shouldBe writesBefore
            }
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            wire.writes.size shouldBe writesBefore
            rejections.size shouldBe 0
            offRejections.size shouldBe 0
            connection.state.value.setting<AapSetting.AllowOffOption>() shouldBe null
            connection.wasEverReady shouldBe true
            connection.disconnect()
            wire.closeCount.get() shouldBe 1
        } finally {
            connection.disconnect()
            wire.end()
            scope.cancel()
        }
    }

    @Test
    fun `same address gets a fresh session and only a matching response confirms replacement ANC`() = runBlocking {
        val old = Wire()
        val replacement = Wire()
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
        val factory = mockk<L2capSocketFactory> {
            every { createSocket(any(), any()) } returnsMany listOf(old.socket, replacement.socket)
        }
        val manager = AapConnectionManager(factory, scope, TestTimeSource())
        val drops = CopyOnWriteArrayList<AapDisconnectEvent>()
        val rejections = CopyOnWriteArrayList<Pair<String, AapCommand>>()
        scope.launch { manager.disconnectEvents.collect { drops += it } }
        scope.launch { manager.settingRejectedEvents.collect { rejections += it } }
        try {
            manager.connect(address, device, PodModel.AIRPODS_PRO3)
            old.ready()
            awaitCondition { manager.allStates.value[address]?.setting<AapSetting.AncMode>()?.current == AapSetting.AncMode.Value.ON }
            val request = AapCommand.SetAncMode(AapSetting.AncMode.Value.TRANSPARENCY)
            manager.sendCommand(address, request)
            val oldWrites = old.writes.size
            old.receive(byteArrayOf(2, 0, 4, 0, 0, 0))
            awaitCondition { drops.isNotEmpty() }
            drops.toList() shouldBe listOf(AapDisconnectEvent(address, wasEverReady = true))
            manager.allStates.value shouldBe emptyMap()
            old.closeCount.get() shouldBe 1
            scheduler.advanceTimeBy(1500)

            manager.connect(address, device, PodModel.AIRPODS_PRO3)
            replacement.ready()
            awaitCondition { manager.allStates.value[address]?.setting<AapSetting.AncMode>()?.current == AapSetting.AncMode.Value.ON }
            manager.sendCommand(address, request)
            replacement.writes.last() shouldBe byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 3, 0, 0, 0).toList()
            val replacementWrites = replacement.writes.size
            manager.allStates.value[address]?.pendingAncMode shouldBe AapSetting.AncMode.Value.TRANSPARENCY
            manager.allStates.value[address]?.setting<AapSetting.AncMode>()?.current shouldBe AapSetting.AncMode.Value.ON

            // Cross the retired command's deadline while the new command is still pending.
            scheduler.advanceTimeBy(600)
            scheduler.runCurrent()
            manager.allStates.value[address]?.pendingAncMode shouldBe AapSetting.AncMode.Value.TRANSPARENCY
            rejections.size shouldBe 0
            old.writes.size shouldBe oldWrites
            replacement.writes.size shouldBe replacementWrites
            replacement.receive(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 3, 0, 0, 0))
            awaitCondition { manager.allStates.value[address]?.pendingAncMode == null }
            manager.allStates.value[address]?.setting<AapSetting.AncMode>()?.current shouldBe AapSetting.AncMode.Value.TRANSPARENCY
            scheduler.advanceTimeBy(10_000)
            scheduler.runCurrent()
            rejections.size shouldBe 0
            replacement.writes.size shouldBe replacementWrites
            manager.disconnect(address)
            drops.size shouldBe 1 // Intentional disconnect never triggers recovery.
        } finally {
            manager.disconnect(address)
            old.end()
            replacement.end()
            scope.cancel()
        }
    }

    @Test
    fun `unrelated service and truncated disconnect frames do not retire our session`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val factory = mockk<L2capSocketFactory> { every { createSocket(any(), any()) } returns wire.socket }
        val connection = AapConnection(device, profile, factory, TestTimeSource())
        try {
            connection.connect(scope)
            wire.ready()
            wire.receive(byteArrayOf(2, 0, 7, 0, 0, 0))
            wire.receive(byteArrayOf(1, 0, 7, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            wire.receive(byteArrayOf(2, 0, 4, 0))
            wire.receive(byteArrayOf(2, 0, 4, 0, 0))
            wire.receive(byteArrayOf(3, 0, 4, 0)) // A response is not a peer termination request.
            wire.receive(byteArrayOf(4, 0, 4, 0, 6, 0, 1, 1)) // Ordered marker after ignored frames.
            awaitCondition { connection.state.value.setting<AapSetting.EarDetection>()?.isEitherPodInEar == false }
            connection.state.value.setting<AapSetting.EarDetection>()?.isEitherPodInEar shouldBe false
            connection.state.value.connectionState shouldBe AapPodState.ConnectionState.READY
            wire.closeCount.get() shouldBe 0
        } finally {
            connection.disconnect()
            wire.end()
            scope.cancel()
        }
    }

    @Test
    fun `rejected connect response closes transport before later messages can revive it`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val factory = mockk<L2capSocketFactory> { every { createSocket(any(), any()) } returns wire.socket }
        val connection = AapConnection(device, profile, factory, TestTimeSource())
        try {
            connection.connect(scope)
            wire.receive(byteArrayOf(1, 0, 4, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            wire.ready()
            awaitCondition { wire.closeCount.get() > 0 }
            connection.state.value.connectionState shouldBe AapPodState.ConnectionState.DISCONNECTED
            connection.state.value.settings shouldBe emptyMap()
            wire.closeCount.get() shouldBe 1
            connection.wasEverReady shouldBe false
        } finally {
            connection.disconnect()
            wire.end()
            scope.cancel()
        }
    }

    @Test
    fun `EOF emits one unintentional drop and closes once`() = runBlocking {
        val wire = Wire()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val factory = mockk<L2capSocketFactory> { every { createSocket(any(), any()) } returns wire.socket }
        val manager = AapConnectionManager(factory, scope, TestTimeSource())
        val drops = CopyOnWriteArrayList<AapDisconnectEvent>()
        scope.launch { manager.disconnectEvents.collect { drops += it } }
        try {
            manager.connect(address, device, PodModel.AIRPODS_PRO3)
            wire.ready()
            awaitCondition { manager.allStates.value[address]?.connectionState == AapPodState.ConnectionState.READY }
            wire.end()
            awaitCondition { drops.isNotEmpty() }
            drops.toList() shouldBe listOf(AapDisconnectEvent(address, true))
            manager.allStates.value shouldBe emptyMap()
            wire.closeCount.get() shouldBe 1
        } finally {
            manager.disconnect(address)
            wire.end()
            scope.cancel()
        }
    }

    @Test
    fun `late old reader finalizer cannot remove the same address replacement`() = runBlocking {
        val old = Wire(holdEof = true)
        val replacement = Wire()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val factory = mockk<L2capSocketFactory> {
            every { createSocket(any(), any()) } returnsMany listOf(old.socket, replacement.socket)
        }
        val manager = AapConnectionManager(factory, scope, TestTimeSource())
        val drops = CopyOnWriteArrayList<AapDisconnectEvent>()
        val eventJob = scope.launch { manager.disconnectEvents.collect { drops += it } }
        try {
            manager.connect(address, device, PodModel.AIRPODS_PRO3)
            old.ready()
            awaitCondition { manager.allStates.value[address]?.setting<AapSetting.AncMode>()?.current == AapSetting.AncMode.Value.ON }
            val retiredJobs = scope.coroutineContext[Job]!!.children.filter { it !== eventJob }.toList()
            manager.disconnect(address)
            manager.connect(address, device, PodModel.AIRPODS_PRO3)
            replacement.ready()
            awaitCondition { manager.allStates.value[address]?.setting<AapSetting.AncMode>()?.current == AapSetting.AncMode.Value.ON }
            old.releaseReader()
            withTimeout(1500) { retiredJobs.forEach { it.join() } }
            manager.sendCommand(address, AapCommand.SetAncMode(AapSetting.AncMode.Value.TRANSPARENCY))
            replacement.writes.last() shouldBe byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 3, 0, 0, 0).toList()
            manager.allStates.value[address]?.connectionState shouldBe AapPodState.ConnectionState.READY
            drops.size shouldBe 0
            old.closeCount.get() shouldBe 1
        } finally {
            old.releaseReader()
            manager.disconnect(address)
            old.end()
            replacement.end()
            scope.cancel()
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeoutOrNull(1500) { while (!condition()) delay(5) }
    }

    private class Wire(holdEof: Boolean = false) {
        private val frames = LinkedBlockingQueue<ByteArray>()
        private val eofGate = CountDownLatch(if (holdEof) 1 else 0)
        val writes = CopyOnWriteArrayList<List<Byte>>()
        val closeCount = AtomicInteger()
        private val input = object : InputStream() {
            override fun read(): Int = error("frame reads only")
            override fun read(b: ByteArray): Int {
                val frame = frames.take()
                if (frame.isEmpty()) {
                    eofGate.await()
                    return -1
                }
                frame.copyInto(b)
                return frame.size
            }
        }
        private val output = object : OutputStream() {
            override fun write(b: Int) = error("frame writes only")
            override fun write(b: ByteArray) { writes += b.toList() }
        }
        val socket = mockk<BluetoothSocket>(relaxed = true) {
            every { inputStream } returns input
            every { outputStream } returns output
            every { close() } answers { closeCount.incrementAndGet(); end() }
        }
        fun receive(frame: ByteArray) { frames.put(frame) }
        fun releaseReader() { eofGate.countDown() }
        fun end() { receive(byteArrayOf()) }
        fun ready() {
            receive(byteArrayOf(1, 0, 4, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            receive(byteArrayOf(4, 0, 4, 0, 6, 0, 0, 0)) // Both in ear, READY.
            receive(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 2, 0, 0, 0)) // Current ANC ON.
        }
    }
}
