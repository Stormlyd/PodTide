package eu.darken.capod.main.ui.overview

import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class OverviewDeviceUpdatesTest {

    private fun device(
        reading: Int = 0,
        current: AapSetting.AncMode.Value = AapSetting.AncMode.Value.ON,
        pending: AapSetting.AncMode.Value? = null,
    ) = PodDevice(
        profileId = "same-headset",
        profileAddress = "AA:BB:CC:DD:EE:FF",
        profileModel = PodModel.AIRPODS_PRO2,
        ble = null,
        aap = AapPodState(
            connectionState = AapPodState.ConnectionState.READY,
            settings = mapOf(AapSetting.AncMode::class to AapSetting.AncMode(current, AapSetting.AncMode.Value.entries)),
            pendingAncMode = pending,
            lastMessageAt = Instant.EPOCH.plusMillis(reading.toLong()),
            batteries = mapOf(AapPodState.BatteryType.LEFT to AapPodState.Battery(
                type = AapPodState.BatteryType.LEFT,
                percent = reading / 100f,
                charging = AapPodState.ChargingState.NOT_CHARGING,
            )),
        ),
    )

    @Test
    fun `passive battery and message bursts deliver latest at one second then resume promptly after idle`() = runTest {
        val upstream = MutableSharedFlow<List<PodDevice>>()
        val received = mutableListOf<List<PodDevice>>()
        backgroundScope.launch { upstream.overviewDeviceUpdates().collect { received += it } }
        runCurrent()
        upstream.emit(listOf(device(0)))
        runCurrent()
        received.size shouldBe 1
        for (reading in 1..9) {
            advanceTimeBy(100)
            upstream.emit(listOf(device(reading)))
            runCurrent()
        }
        received.size shouldBe 1
        advanceTimeBy(100)
        runCurrent()
        received.map { it.single().batteryLeft } shouldBe listOf(0f, .09f)

        advanceTimeBy(1500)
        upstream.emit(listOf(device(10)))
        runCurrent()
        received.map { it.single().batteryLeft } shouldBe listOf(0f, .09f, .10f)
    }

    @Test
    fun `queued older telemetry never rolls back a prompt control update at timer expiry`() = runTest {
        val upstream = MutableSharedFlow<List<PodDevice>>()
        val received = mutableListOf<List<PodDevice>>()
        backgroundScope.launch { upstream.overviewDeviceUpdates().collect { received += it } }
        runCurrent()
        upstream.emit(listOf(device(0)))
        advanceTimeBy(100)
        upstream.emit(listOf(device(10)))
        advanceTimeBy(100)
        upstream.emit(listOf(device(20, pending = AapSetting.AncMode.Value.TRANSPARENCY)))
        runCurrent()
        received.size shouldBe 2
        received.last().single().pendingAncMode shouldBe AapSetting.AncMode.Value.TRANSPARENCY
        received.last().single().batteryLeft shouldBe .20f

        advanceTimeBy(1800)
        runCurrent()
        received.size shouldBe 2
        received.last().single().pendingAncMode shouldBe AapSetting.AncMode.Value.TRANSPARENCY
    }

    @Test
    fun `passive data after immediate control retains cadence and newest control at expiry`() = runTest {
        val upstream = MutableSharedFlow<List<PodDevice>>()
        val received = mutableListOf<Pair<Long, List<PodDevice>>>()
        backgroundScope.launch { upstream.overviewDeviceUpdates().collect { received += testScheduler.currentTime to it } }
        runCurrent()
        upstream.emit(listOf(device()))
        advanceTimeBy(100)
        upstream.emit(listOf(device(10)))
        advanceTimeBy(100)
        upstream.emit(listOf(device(20, pending = AapSetting.AncMode.Value.TRANSPARENCY)))
        advanceTimeBy(100)
        upstream.emit(listOf(device(30, pending = AapSetting.AncMode.Value.TRANSPARENCY)))
        runCurrent()
        received.map { it.first } shouldBe listOf(0L, 200L)

        advanceTimeBy(700)
        runCurrent()
        received.map { it.first } shouldBe listOf(0L, 200L, 1000L)
        received.last().second.single().batteryLeft shouldBe .30f
        received.last().second.single().pendingAncMode shouldBe AapSetting.AncMode.Value.TRANSPARENCY
    }

    @Test
    fun `rapid pending confirmed rejected and disconnected snapshots preserve upstream order`() = runTest {
        val upstream = MutableSharedFlow<List<PodDevice>>(extraBufferCapacity = 8)
        val received = mutableListOf<List<PodDevice>>()
        backgroundScope.launch { upstream.overviewDeviceUpdates().collect { received += it } }
        runCurrent()
        upstream.emit(listOf(device()))
        advanceTimeBy(100)
        upstream.emit(listOf(device(pending = AapSetting.AncMode.Value.TRANSPARENCY)))
        upstream.emit(listOf(device(current = AapSetting.AncMode.Value.TRANSPARENCY)))
        upstream.emit(listOf(device(current = AapSetting.AncMode.Value.TRANSPARENCY, pending = AapSetting.AncMode.Value.ON)))
        upstream.emit(listOf(device(current = AapSetting.AncMode.Value.TRANSPARENCY)))
        upstream.emit(listOf(device().copy(aap = null)))
        runCurrent()

        received.map { it.single().ancMode?.current } shouldBe listOf(
            AapSetting.AncMode.Value.ON, AapSetting.AncMode.Value.ON,
            AapSetting.AncMode.Value.TRANSPARENCY, AapSetting.AncMode.Value.TRANSPARENCY,
            AapSetting.AncMode.Value.TRANSPARENCY, null,
        )
        received.map { it.single().pendingAncMode } shouldBe listOf(
            null, AapSetting.AncMode.Value.TRANSPARENCY, null, AapSetting.AncMode.Value.ON, null, null,
        )
    }

    @Test
    fun `first empty and device removal are immediate and discard queued passive snapshot`() = runTest {
        val upstream = MutableSharedFlow<List<PodDevice>>()
        val received = mutableListOf<List<PodDevice>>()
        backgroundScope.launch { upstream.overviewDeviceUpdates().collect { received += it } }
        runCurrent()
        upstream.emit(emptyList())
        runCurrent()
        received shouldBe listOf(emptyList())
        advanceTimeBy(100)
        upstream.emit(listOf(device()))
        runCurrent()
        received.last().size shouldBe 1
        advanceTimeBy(100)
        upstream.emit(listOf(device(10)))
        advanceTimeBy(100)
        upstream.emit(emptyList())
        runCurrent()
        received.map { it.size } shouldBe listOf(0, 1, 0)
        advanceTimeBy(1000)
        runCurrent()
        received.map { it.size } shouldBe listOf(0, 1, 0)
    }

    @Test
    fun `one upstream owner is cancelled together with pending passive delivery`() = runTest {
        var started = 0
        var stopped = 0
        val upstream = flow {
            started++
            try {
                emit(listOf(device()))
                emit(listOf(device(10)))
                awaitCancellation()
            } finally {
                stopped++
            }
        }
        val received = mutableListOf<List<PodDevice>>()
        val collection = launch { upstream.overviewDeviceUpdates().collect { received += it } }
        runCurrent()
        started shouldBe 1
        received.size shouldBe 1
        advanceTimeBy(100)
        collection.cancelAndJoin()
        stopped shouldBe 1
        collection.children.count() shouldBe 0
        advanceTimeBy(2000)
        runCurrent()
        received.size shouldBe 1
    }

    @Test
    fun `finite upstream flushes latest passive reading before completing`() = runTest {
        val upstream = flow {
            emit(listOf(device()))
            emit(listOf(device(10)))
            emit(listOf(device(20)))
        }
        val received = mutableListOf<List<PodDevice>>()
        val collection = launch { upstream.overviewDeviceUpdates().collect { received += it } }
        runCurrent()
        received.map { it.single().batteryLeft } shouldBe listOf(0f)
        advanceTimeBy(1000)
        runCurrent()
        received.map { it.single().batteryLeft } shouldBe listOf(0f, .20f)
        collection.isCompleted shouldBe true
    }
}
