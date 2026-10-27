package eu.darken.capod.reaction.core.heartrate

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import eu.darken.capod.R
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import eu.darken.capod.profiles.core.SelectedDeviceTarget
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import testhelpers.TestApplication
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class HeartRateControllerRoutingTest {
    private val oldAddress = "AA:BB:CC:DD:EE:FF"
    private val selectedAddress = "11:22:33:44:55:66"
    private val profileId = "primary"
    private val devices = MutableStateFlow<List<PodDevice>>(emptyList())
    private val profiles = MutableStateFlow<List<DeviceProfile>>(emptyList())
    private val selectionChanges = MutableStateFlow(0L)
    private var selectionRevision = 0L
    private val reports = MutableSharedFlow<Pair<String, HeartRateReport>>(extraBufferCapacity = 16)
    private val monitor = mockk<DeviceMonitor> { every { this@mockk.devices } returns this@HeartRateControllerRoutingTest.devices }
    private val manager = mockk<AapConnectionManager>(relaxed = true) {
        every { heartRate } returns reports
        coEvery { sendCommand(any(), any()) } returns Unit
    }
    private val profilesRepo = mockk<DeviceProfilesRepo> {
        every { this@mockk.selectionChanges } returns this@HeartRateControllerRoutingTest.selectionChanges
        coEvery { selectedTarget(any()) } coAnswers {
            val id = firstArg<String>()
            SelectedDeviceTarget(this@HeartRateControllerRoutingTest.profiles.value.firstOrNull { it.id == id }?.address, selectionRevision)
        }
    }
    private val controller = HeartRateController(ApplicationProvider.getApplicationContext<Application>(), monitor, manager, profilesRepo)

    private fun select(address: String?) {
        val old = profiles.value.firstOrNull { it.id == profileId }
        profiles.value = listOf(AppleDeviceProfile(id = profileId, label = "Primary", address = address))
        if (old == null || old.address != address) selectionChanges.value = ++selectionRevision
    }

    private fun removeProfile() {
        if (profiles.value.any { it.id == profileId }) selectionChanges.value = ++selectionRevision
        profiles.value = emptyList()
    }

    private fun lagMonitorSelectionUpdates(release: CompletableDeferred<Unit>) {
        var subscriptions = 0
        every { profilesRepo.selectionChanges } answers {
            if (subscriptions++ == 0) flow {
                emit(selectionChanges.value)
                release.await()
                emitAll(selectionChanges)
            } else selectionChanges
        }
    }

    private fun readyDevice(selected: String?, displayed: String = oldAddress) = mockk<PodDevice>(relaxed = true) {
        every { profileId } returns this@HeartRateControllerRoutingTest.profileId
        every { profileAddress } returns selected
        every { address } returns displayed
        every { model } returns PodModel.AIRPODS_PRO3
        every { isSystemConnected } returns true
        every { isAapReady } returns true
        every { isEitherPodInEar } returns true
        every { aap } returns null
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `stale BLE address never receives heart start and selected device accepts only its reports`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        controller.clearHistory(profileId)
        select(selectedAddress)
        devices.value = listOf(readyDevice(selectedAddress))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()

        coVerify(exactly = 1) { manager.sendCommand(selectedAddress, AapCommand.SetHeartRateTracking(true, 19)) }
        coVerify(exactly = 0) { manager.sendCommand(oldAddress, any()) }

        reports.tryEmit(oldAddress to HeartRateReport(71, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe null
        controller.state(profileId).history.size shouldBe 0

        reports.tryEmit(selectedAddress to HeartRateReport(82, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.ACTIVE, 82)
        controller.state(profileId).history.map { it.bpm } shouldBe listOf(82)
        job.cancelAndJoin()
    }

    @Test fun `removed selection never starts despite ready AAP and old display address`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        select(null)
        devices.value = listOf(readyDevice(null))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()

        coVerify(exactly = 0) { manager.sendCommand(any(), any()) }
        controller.state(profileId).reading.bpm shouldBe null
        job.cancelAndJoin()
    }

    @Test fun `switching selected address stops old lease then starts new lease and resets reading`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        controller.clearHistory(profileId)
        select(oldAddress)
        val sends = mutableListOf<Pair<String, AapCommand>>()
        coEvery { manager.sendCommand(any(), any()) } coAnswers {
            sends += firstArg<String>() to secondArg<AapCommand>()
        }
        devices.value = listOf(readyDevice(oldAddress))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()
        reports.tryEmit(oldAddress to HeartRateReport(74, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe 74

        select(selectedAddress)
        devices.value = listOf(readyDevice(selectedAddress))
        runCurrent()
        sends.take(3) shouldBe listOf(
            oldAddress to AapCommand.SetHeartRateTracking(true, 19),
            oldAddress to AapCommand.SetHeartRateTracking(false, 19),
            selectedAddress to AapCommand.SetHeartRateTracking(true, 19),
        )
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        reports.tryEmit(oldAddress to HeartRateReport(90, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).history.map { it.bpm } shouldBe listOf(74)
        reports.tryEmit(selectedAddress to HeartRateReport(81, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe 81
        job.cancelAndJoin()
    }

    @Test fun `old report cannot update history while profile selection changed before monitor reconciliation`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        controller.clearHistory(profileId)
        select(oldAddress)
        val release = CompletableDeferred<Unit>()
        lagMonitorSelectionUpdates(release)
        devices.value = listOf(readyDevice(oldAddress))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()
        reports.tryEmit(oldAddress to HeartRateReport(74, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe 74

        select(selectedAddress)
        reports.tryEmit(oldAddress to HeartRateReport(92, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading()
        controller.state(profileId).history.map { it.bpm } shouldBe listOf(74)
        coVerify(exactly = 1) { manager.sendCommand(oldAddress, AapCommand.SetHeartRateTracking(false, 19)) }

        devices.value = listOf(readyDevice(selectedAddress))
        release.complete(Unit)
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        reports.tryEmit(selectedAddress to HeartRateReport(83, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe 83
        job.cancelAndJoin()
    }

    @Test fun `removal rejects old reports before reconciliation and restoring address starts fresh session`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        controller.clearHistory(profileId)
        select(oldAddress)
        val release = CompletableDeferred<Unit>()
        lagMonitorSelectionUpdates(release)
        devices.value = listOf(readyDevice(oldAddress))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()
        reports.tryEmit(oldAddress to HeartRateReport(72, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe 72

        removeProfile()
        reports.tryEmit(oldAddress to HeartRateReport(93, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading()
        controller.state(profileId).history.map { it.bpm } shouldBe listOf(72)

        release.complete(Unit)
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading()
        controller.state(profileId).messageRes shouldBe R.string.heart_rate_waiting_connection
        coVerify(exactly = 1) { manager.sendCommand(oldAddress, AapCommand.SetHeartRateTracking(false, 19)) }
        select(oldAddress)
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        coVerify(exactly = 2) { manager.sendCommand(oldAddress, AapCommand.SetHeartRateTracking(true, 19)) }
        job.cancelAndJoin()
    }

    @Test fun `remove and restore same address before observer resumes must stop old lease and reject prior timestamp`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        controller.clearHistory(profileId)
        select(oldAddress)
        val release = CompletableDeferred<Unit>()
        lagMonitorSelectionUpdates(release)
        val sends = mutableListOf<Pair<String, AapCommand>>()
        coEvery { manager.sendCommand(any(), any()) } coAnswers {
            sends += firstArg<String>() to secondArg<AapCommand>()
        }
        devices.value = listOf(readyDevice(oldAddress))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()
        val beforeRemoval = SystemClock.elapsedRealtime()

        select(null)
        reports.tryEmit(oldAddress to HeartRateReport(91, beforeRemoval)) shouldBe true
        runCurrent()
        controller.state(profileId).reading.bpm shouldBe null
        controller.state(profileId).history.size shouldBe 0

        select(oldAddress)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        release.complete(Unit)
        runCurrent()
        sends.take(3) shouldBe listOf(
            oldAddress to AapCommand.SetHeartRateTracking(true, 19),
            oldAddress to AapCommand.SetHeartRateTracking(false, 19),
            oldAddress to AapCommand.SetHeartRateTracking(true, 19),
        )
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        reports.tryEmit(oldAddress to HeartRateReport(92, beforeRemoval)) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        controller.state(profileId).history.size shouldBe 0
        reports.tryEmit(oldAddress to HeartRateReport(83, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.ACTIVE, 83)
        job.cancelAndJoin()
    }

    @Test fun `remove and restore same address without a report still starts a fresh session`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        controller.clearHistory(profileId)
        select(oldAddress)
        val release = CompletableDeferred<Unit>()
        lagMonitorSelectionUpdates(release)
        val sends = mutableListOf<Pair<String, AapCommand>>()
        coEvery { manager.sendCommand(any(), any()) } coAnswers {
            sends += firstArg<String>() to secondArg<AapCommand>()
        }
        devices.value = listOf(readyDevice(oldAddress))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        controller.start(profileId)
        runCurrent()
        val beforeRemoval = SystemClock.elapsedRealtime()

        select(null)
        select(oldAddress)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        release.complete(Unit)
        runCurrent()

        sends.take(3) shouldBe listOf(
            oldAddress to AapCommand.SetHeartRateTracking(true, 19),
            oldAddress to AapCommand.SetHeartRateTracking(false, 19),
            oldAddress to AapCommand.SetHeartRateTracking(true, 19),
        )
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        reports.tryEmit(oldAddress to HeartRateReport(94, beforeRemoval)) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        controller.state(profileId).history.size shouldBe 0
        reports.tryEmit(oldAddress to HeartRateReport(82, SystemClock.elapsedRealtime())) shouldBe true
        runCurrent()
        controller.state(profileId).reading shouldBe HeartRateReading(HeartRateStatus.ACTIVE, 82)
        job.cancelAndJoin()
    }
}
