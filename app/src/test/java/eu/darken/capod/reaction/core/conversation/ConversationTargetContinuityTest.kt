package eu.darken.capod.reaction.core.conversation

import eu.darken.capod.common.MediaControl
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting
import eu.darken.capod.pods.core.apple.aap.protocol.ConversationAwarenessEvent
import eu.darken.capod.pods.core.apple.ble.BlePodSnapshot
import eu.darken.capod.profiles.core.AppleDeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesContainer
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.TestTimeSource
import testhelpers.datastore.FakeDataStoreValue

class ConversationTargetContinuityTest : BaseTest() {
    private val oldAddress = "AA:BB:CC:DD:EE:01"
    private val savedAddress = "AA:BB:CC:DD:EE:02"
    private val thirdAddress = "AA:BB:CC:DD:EE:03"
    private val original = AppleDeviceProfile(id = "p", label = "Pods", address = savedAddress,
        model = PodModel.AIRPODS_PRO3, conversationAction = ConversationAction.LOWER_VOLUME)
    private val ready = AapPodState(connectionState = AapPodState.ConnectionState.READY,
        settings = mapOf(AapSetting.EarDetection::class to AapSetting.EarDetection(
            AapSetting.EarDetection.PodPlacement.IN_EAR, AapSetting.EarDetection.PodPlacement.IN_EAR)))

    private fun pod(profile: AppleDeviceProfile = original, bleAddress: String = oldAddress): PodDevice {
        val retained = profile.copy(address = bleAddress)
        val ble = mockk<BlePodSnapshot> {
            every { model } returns PodModel.AIRPODS_PRO3
            every { meta } returns object : BlePodSnapshot.Meta { override val profile = retained }
        }
        return PodDevice(profileId = profile.id, profileAddress = profile.address,
            label = profile.label, ble = ble, aap = ready, reactions = profile.reactionConfig)
    }

    private inner class Fixture(val scope: TestScope, profile: AppleDeviceProfile = original,
                                bleAddress: String = oldAddress) {
        val store = FakeDataStoreValue(DeviceProfilesContainer(listOf(profile)))
        val repo = conversationTestProfiles(listOf(profile), scope.backgroundScope, store)
        val events = MutableSharedFlow<Pair<String, ConversationAwarenessEvent>>(extraBufferCapacity = 16)
        val states = MutableStateFlow(mapOf(oldAddress to ready, savedAddress to ready, thirdAddress to ready))
        val devices = MutableStateFlow(listOf(pod(profile, bleAddress)))
        val media = mockk<MediaControl>(relaxed = true) {
            coEvery { sendPause(any()) } returns true
            every { isPlaying } returns false
            every { duckMusicVolume(any()) } returns MediaControl.DuckOutcome.Ducked(10, 5)
            every { duckMusicVolumeToTarget(any()) } returns MediaControl.DuckOutcome.Ducked(10, 3)
            every { currentMusicVolume() } returns 5
            every { requestDuckFocus() } returns true
            every { isDuckFocusHeld } returns true
        }
        val reaction = ConversationReaction(
            aapManager = mockk<AapConnectionManager> {
                every { conversationalAwarenessEvents } returns events
                every { allStates } returns states
            },
            deviceMonitor = mockk<DeviceMonitor> { every { devices } returns this@Fixture.devices },
            mediaControl = media, appScope = scope.backgroundScope, timeSource = TestTimeSource(),
            profilesRepo = repo,
        )
        val job = reaction.monitor().launchIn(scope.backgroundScope)
        suspend fun event(event: ConversationAwarenessEvent, address: String = savedAddress) {
            events.emit(address to event)
            scope.runCurrent()
        }
        fun startCollectors() = scope.runCurrent()
    }

    @Test
    fun `current saved target starts and resumes PAUSE with retained old BLE metadata`() = runTest {
        val f = Fixture(this, original.copy(conversationAction = ConversationAction.PAUSE))
        f.devices.value.single().address shouldBe oldAddress
        f.devices.value.single().profileAddress shouldBe savedAddress
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        coVerify(exactly = 1) { f.media.sendPause(false) }
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.STOP)
        coVerify(exactly = 1) { f.media.sendPlay() }
    }

    @Test
    fun `current saved target ducks without a fresh BLE packet`() = runTest {
        val f = Fixture(this)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        verify(exactly = 1) { f.media.duckMusicVolume(50) }
    }

    @Test
    fun `old BLE target cannot duck even when old AAP state remains READY`() = runTest {
        val f = Fixture(this)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START, oldAddress)
        verify(exactly = 0) { f.media.duckMusicVolume(any()) }
    }

    @Test
    fun `late START after manager removes target cannot strand a new duck`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.states.value = emptyMap()
        runCurrent()
        f.event(ConversationAwarenessEvent.START)
        verify(exactly = 0) { f.media.duckMusicVolume(any()) }
    }

    @Test
    fun `non-READY target cannot pause`() = runTest {
        val f = Fixture(this, original.copy(conversationAction = ConversationAction.PAUSE), savedAddress)
        f.startCollectors()
        for (state in listOf(AapPodState.ConnectionState.DISCONNECTED,
            AapPodState.ConnectionState.CONNECTING, AapPodState.ConnectionState.HANDSHAKING)) {
            f.states.value = mapOf(savedAddress to ready.copy(connectionState = state))
            f.event(ConversationAwarenessEvent.START)
        }
        coVerify(exactly = 0) { f.media.sendPause(any()) }
    }

    @Test
    fun `removed or blank saved target never falls back to retained monitor or BLE address`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.repo.updateProfile(original.copy(address = " "))
        f.event(ConversationAwarenessEvent.START)
        f.repo.removeProfile(original.id)
        f.event(ConversationAwarenessEvent.START)
        verify(exactly = 0) { f.media.duckMusicVolume(any()) }
    }

    @Test
    fun `saved target change retires duck even while monitor retains old primary snapshot`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.repo.updateProfile(original.copy(address = thirdAddress))
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.RESUME)
        f.event(ConversationAwarenessEvent.STOP)
        advanceTimeBy(301_000)
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
        verify(exactly = 1) { f.media.duckMusicVolume(50) }
    }

    @Test
    fun `coalesced same-address profile removal and re-add retires original duck`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.repo.removeProfile(original.id)
        f.repo.addProfile(original)
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
        f.event(ConversationAwarenessEvent.RESUME)
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.STOP)
        advanceTimeBy(7_000)
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
        f.event(ConversationAwarenessEvent.START)
        verify(exactly = 2) { f.media.duckMusicVolume(50) }
    }

    @Test
    fun `selection retirement releases focus and restores a late volume write`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        every { f.media.duckMusicVolume(any()) } returns MediaControl.DuckOutcome.Unchanged(10)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.repo.removeProfile(original.id)
        runCurrent()
        verify(exactly = 1) { f.media.abandonDuckFocus() }
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
    }

    @Test
    fun `same-address removal and re-add retires pause without automatic playback`() = runTest {
        val f = Fixture(this, original.copy(conversationAction = ConversationAction.PAUSE), savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.repo.removeProfile(original.id)
        f.repo.addProfile(original.copy(conversationAction = ConversationAction.PAUSE))
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.RESUME)
        f.event(ConversationAwarenessEvent.STOP)
        advanceTimeBy(301_000)
        runCurrent()
        coVerify(exactly = 0) { f.media.sendPlay() }
    }

    @Test
    fun `primary profile switch retires duck even if address belongs to same READY target`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.devices.value = listOf(pod(original.copy(id = "replacement"), savedAddress))
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
    }

    @Test
    fun `primary profile switch retires focus and does not restore an unchanged stream`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        every { f.media.duckMusicVolume(any()) } returns MediaControl.DuckOutcome.Unchanged(10)
        every { f.media.currentMusicVolume() } returns 10
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.devices.value = emptyList()
        runCurrent()
        verify(exactly = 1) { f.media.abandonDuckFocus() }
        verify(exactly = 0) { f.media.restoreMusicVolume(any()) }
    }

    @Test
    fun `primary switch cannot resume pause at old STOP or timer`() = runTest {
        val f = Fixture(this, original.copy(conversationAction = ConversationAction.PAUSE), savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.devices.value = listOf(pod(original.copy(id = "replacement"), savedAddress))
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.STOP)
        advanceTimeBy(301_000)
        runCurrent()
        coVerify(exactly = 0) { f.media.sendPlay() }
    }

    @Test
    fun `mode and label changes keep current duck and apply new mode on next START`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        val updated = original.copy(label = "Renamed", conversationVolumeMode = ConversationVolumeMode.ABSOLUTE_TARGET,
            conversationVolumeTarget = 20)
        f.repo.updateProfile(updated)
        f.devices.value = listOf(pod(updated, savedAddress))
        runCurrent()
        verify(exactly = 0) { f.media.restoreMusicVolume(any()) }
        f.event(ConversationAwarenessEvent.START)
        verify(exactly = 1) { f.media.duckMusicVolume(50) }
        verify(exactly = 0) { f.media.duckMusicVolumeToTarget(any()) }
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.STOP)
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
        f.event(ConversationAwarenessEvent.START)
        verify(exactly = 1) { f.media.duckMusicVolumeToTarget(20) }
    }

    // A suspended real timer holds the reaction mutex. The event collector has already received
    // the next START before the saved target changes, exercising the second authority check.
    private suspend fun TestScope.pendingStart(change: suspend (Fixture) -> Unit) {
        val f = Fixture(this, original.copy(conversationAction = ConversationAction.PAUSE), savedAddress)
        val playing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { f.media.sendPlay() } coAnswers { playing.complete(Unit); release.await() }
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.event(ConversationAwarenessEvent.HOLD)
        advanceTimeBy(6_001)
        runCurrent()
        playing.isCompleted shouldBe true
        f.event(ConversationAwarenessEvent.START)
        change(f)
        release.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { f.media.sendPause(false) }
    }

    @Test
    fun `START waiting on mutex cannot pause after saved target changes`() = runTest {
        pendingStart { it.repo.updateProfile(original.copy(address = thirdAddress)) }
    }

    @Test
    fun `START waiting on mutex cannot pause after same address is removed and re-added`() = runTest {
        pendingStart { f ->
            f.repo.removeProfile(original.id)
            f.repo.addProfile(original.copy(conversationAction = ConversationAction.PAUSE))
        }
    }

    @Test
    fun `START waiting on mutex cannot pause after primary switches`() = runTest {
        pendingStart { it.devices.value = listOf(pod(original.copy(id = "replacement"), savedAddress)) }
    }

    @Test
    fun `START waiting on mutex cannot pause after live target loses READY`() = runTest {
        pendingStart { it.states.value = mapOf(savedAddress to ready.copy(connectionState = AapPodState.ConnectionState.CONNECTING)) }
    }

    @Test
    fun `START rechecks primary after suspended authoritative repository lookup`() = runTest {
        val f = Fixture(this, original.copy(conversationAction = ConversationAction.PAUSE), savedAddress)
        f.startCollectors()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storedFlow = f.store.mock.flow
        var reads = 0
        // Delay the second repository read (inside the reaction mutex), keeping the real store
        // and repository mutex/revision implementation. This models a contended DataStore read.
        every { f.store.mock.flow } returns flow {
            if (++reads == 2) { entered.complete(Unit); release.await() }
            emitAll(storedFlow)
        }
        f.event(ConversationAwarenessEvent.START)
        entered.isCompleted shouldBe true
        f.devices.value = listOf(pod(original.copy(id = "replacement"), savedAddress))
        release.complete(Unit)
        runCurrent()
        coVerify(exactly = 0) { f.media.sendPause(any()) }
    }

    @Test
    fun `old owner events cannot wind down or stop the new saved target duck`() = runTest {
        val f = Fixture(this)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.event(ConversationAwarenessEvent.HOLD, oldAddress)
        f.event(ConversationAwarenessEvent.RESUME, oldAddress)
        f.event(ConversationAwarenessEvent.STOP, oldAddress)
        advanceTimeBy(7_000)
        runCurrent()
        verify(exactly = 0) { f.media.restoreMusicVolume(any()) }
        f.event(ConversationAwarenessEvent.HOLD)
        f.event(ConversationAwarenessEvent.STOP)
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
    }

    @Test
    fun `primary address switch with same profile retires current duck`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.devices.value = listOf(pod(original.copy(address = thirdAddress), savedAddress))
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
    }

    @Test
    fun `live target leaving READY retires current duck without map removal`() = runTest {
        val f = Fixture(this, bleAddress = savedAddress)
        f.startCollectors()
        f.event(ConversationAwarenessEvent.START)
        f.states.value = mapOf(savedAddress to ready.copy(connectionState = AapPodState.ConnectionState.HANDSHAKING))
        runCurrent()
        verify(exactly = 1) { f.media.restoreMusicVolume(10) }
    }
}
