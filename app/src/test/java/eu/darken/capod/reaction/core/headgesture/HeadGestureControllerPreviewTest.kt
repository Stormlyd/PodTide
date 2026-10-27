package eu.darken.capod.reaction.core.headgesture

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import eu.darken.capod.common.MediaControl
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.monitor.core.worker.MonitorControl
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
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
@Suppress("DEPRECATION")
class HeadGestureControllerPreviewTest {
    private val samples = MutableSharedFlow<Pair<String, HeadTrackingSample>>(extraBufferCapacity = 64)
    private val devices = MutableStateFlow(listOf(mockk<PodDevice>(relaxed = true) {
        every { profileId } returns "p"
        every { address } returns "AA:BB:CC:DD:EE:FF"
        every { isAapReady } returns true
        every { isSystemConnected } returns true
        every { isEitherPodInEar } returns true
    }))
    private val monitor = mockk<DeviceMonitor> { every { devices } returns this@HeadGestureControllerPreviewTest.devices }
    private val manager = mockk<AapConnectionManager>(relaxed = true) {
        every { headSamples } returns samples
        coEvery { sendCommand(any(), any()) } returns Unit
    }
    private var config = HeadGestureConfig(enabled = true, media = true, calls = true)
    private val settings = mockk<HeadGestureSettings> {
        every { changes } returns MutableStateFlow(0L)
        every { read("p") } answers { config }
    }
    private val media = mockk<MediaControl>(relaxed = true)
    private val monitorControl = mockk<MonitorControl>(relaxed = true)
    private val feedback = mockk<HeadPreviewTone>(relaxed = true)
    private var phone = TelephonyManager.CALL_STATE_IDLE
    private var phoneReceiver: BroadcastReceiver? = null
    private val telephony = mockk<TelephonyManager> { every { callState } answers { phone } }
    private val telecom = mockk<TelecomManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { checkSelfPermission(Manifest.permission.READ_PHONE_STATE) } returns PackageManager.PERMISSION_GRANTED
        every { checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) } returns PackageManager.PERMISSION_GRANTED
        every { getSystemService(TelephonyManager::class.java) } returns telephony
        every { getSystemService(TelecomManager::class.java) } returns telecom
        every { registerReceiver(any<BroadcastReceiver>(), any<IntentFilter>(), any<Int>()) } answers {
            phoneReceiver = firstArg(); null
        }
    }
    private val controller = HeadGestureController(context, monitor, manager, settings, media, monitorControl, feedback)

    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun send(v: Int, receivedAtMs: Long = SystemClock.elapsedRealtime()) {
        samples.emit("AA:BB:CC:DD:EE:FF" to HeadTrackingSample(HeadMotion(0, v), 0, 0, 0, receivedAtMs))
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
    }

    private fun queued(v: Int, receivedAtMs: Long = SystemClock.elapsedRealtime()) {
        org.junit.Assert.assertTrue(samples.tryEmit("AA:BB:CC:DD:EE:FF" to
            HeadTrackingSample(HeadMotion(0, v), 0, 0, 0, receivedAtMs)))
    }

    @Test fun `queued pre-recalibration report cannot restore pose or finish recognition`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        val token = controller.startPreview("p")
        runCurrent()
        repeat(10) { ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(0); runCurrent() }
        org.junit.Assert.assertNotNull(controller.preview.value.state.pose)
        controller.setPreviewSound(token, true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(1400); runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(-1400); runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(1400)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        controller.recalibratePreview(token)
        org.junit.Assert.assertNull(controller.preview.value.state.pose)
        runCurrent()
        org.junit.Assert.assertNull(controller.preview.value.state.pose)
        org.junit.Assert.assertEquals(0, controller.preview.value.nods)
        verify(exactly = 0) { feedback.play() }
        org.junit.Assert.assertEquals(0, controller.preview.value.state.calibrationCount)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(0); runCurrent()
        org.junit.Assert.assertEquals(1, controller.preview.value.state.calibrationCount)
        controller.stopPreview(token)
        job.cancelAndJoin()
    }

    @Test fun `pre-ring queued nods cannot answer but fresh call reports can`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        queued(1400); queued(-1400); queued(1400)
        phone = TelephonyManager.CALL_STATE_RINGING
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        phoneReceiver?.onReceive(context, Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        runCurrent()
        verify(exactly = 0) { telecom.acceptRingingCall() }
        repeat(3) { i ->
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            queued(if (i % 2 == 0) 1400 else -1400)
            runCurrent()
        }
        verify(exactly = 1) { telecom.acceptRingingCall() }
        job.cancelAndJoin()
    }

    @Test fun `same address reconnect rejects reports received before new stream`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        val beforeDisconnect = SystemClock.elapsedRealtime()
        devices.value = listOf(pod(ready = false))
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        devices.value = listOf(pod(ready = true))
        runCurrent()
        repeat(3) { i -> queued(if (i % 2 == 0) 1400 else -1400, beforeDisconnect); runCurrent() }
        coVerify(exactly = 0) { media.sendPlayPause() }
        repeat(3) { i ->
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            queued(if (i % 2 == 0) 1400 else -1400)
            runCurrent()
        }
        coVerify(exactly = 1) { media.sendPlayPause() }
        job.cancelAndJoin()
    }

    @Test fun `buffered reversals keep their receive time instead of processing as a rapid gesture`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        val firstAt = SystemClock.elapsedRealtime() + 20
        queued(1400, firstAt)
        queued(-1400, firstAt + 700)
        queued(1400, firstAt + 1400)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1450))
        runCurrent()
        coVerify(exactly = 0) { media.sendPlayPause() }
        job.cancelAndJoin()
    }

    @Test fun `preview stop during ringing rejects prior reports and allows fresh call reports`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        val token = controller.startPreview("p")
        runCurrent()
        phone = TelephonyManager.CALL_STATE_RINGING
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        phoneReceiver?.onReceive(context, Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        runCurrent()
        val oldAt = SystemClock.elapsedRealtime()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        controller.stopPreview(token)
        runCurrent()
        repeat(3) { i -> queued(if (i % 2 == 0) 1400 else -1400, oldAt); runCurrent() }
        verify(exactly = 0) { telecom.acceptRingingCall() }
        repeat(3) { i ->
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            queued(if (i % 2 == 0) 1400 else -1400)
            runCurrent()
        }
        verify(exactly = 1) { telecom.acceptRingingCall() }
        job.cancelAndJoin()
    }

    @Test fun `monitor cancellation hides live preview and restarted monitor can receive again`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        val token = controller.startPreview("p")
        runCurrent()
        repeat(10) { ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(0); runCurrent() }
        org.junit.Assert.assertNotNull(controller.preview.value.state.pose)
        first.cancelAndJoin()
        org.junit.Assert.assertNull(controller.preview.value.state.pose)
        org.junit.Assert.assertNull(controller.preview.value.state.motion)
        org.junit.Assert.assertNull(controller.preview.value.lastSampleAgeMs)
        org.junit.Assert.assertEquals(eu.darken.capod.R.string.head_preview_waiting_monitor, controller.preview.value.messageRes)
        val second = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(0); runCurrent()
        org.junit.Assert.assertEquals(1, controller.preview.value.state.calibrationCount)
        controller.stopPreview(token)
        second.cancelAndJoin()
    }

    @Test fun `old monitor teardown cannot overwrite a newer preview owner`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val oldMonitor = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        val oldToken = controller.startPreview("p")
        runCurrent()
        repeat(10) { ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(0); runCurrent() }
        org.junit.Assert.assertNotNull(controller.preview.value.state.pose)
        val newerToken = controller.startPreview("p")
        oldMonitor.cancelAndJoin()
        org.junit.Assert.assertEquals(newerToken, controller.preview.value.token)
        org.junit.Assert.assertTrue(controller.preview.value.requested)
        org.junit.Assert.assertNull(controller.preview.value.state.pose)
        val replacement = controller.monitor().launchIn(backgroundScope)
        runCurrent()
        ShadowSystemClock.advanceBy(Duration.ofMillis(20)); queued(0); runCurrent()
        org.junit.Assert.assertEquals(1, controller.preview.value.state.calibrationCount)
        controller.stopPreview(oldToken)
        org.junit.Assert.assertEquals(newerToken, controller.preview.value.token)
        controller.stopPreview(newerToken)
        replacement.cancelAndJoin()
    }

    private fun pod(ready: Boolean): PodDevice = mockk(relaxed = true) {
        every { profileId } returns "p"
        every { address } returns "AA:BB:CC:DD:EE:FF"
        every { isAapReady } returns ready
        every { isSystemConnected } returns true
        every { isEitherPodInEar } returns true
    }

    @Test fun `older page stop cannot clear replacement preview state`() {
        val old = controller.startPreview("p")
        val current = controller.startPreview("p")
        controller.stopPreview(old)
        org.junit.Assert.assertEquals(current, controller.preview.value.token)
        org.junit.Assert.assertTrue(controller.preview.value.requested)
        controller.stopPreview(current)
        org.junit.Assert.assertFalse(controller.preview.value.requested)
    }

    @Test fun `preview suppresses music immediately and queued old reports cannot fire after stop`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        val token = controller.startPreview("p")
        send(1400); send(-1400); send(1400)
        coVerify(exactly = 0) { media.sendPlayPause() }
        val oldTimestamp = SystemClock.elapsedRealtime()
        controller.stopPreview(token)
        send(1400, oldTimestamp)
        send(-1400, oldTimestamp)
        send(1400, oldTimestamp)
        coVerify(exactly = 0) { media.sendPlayPause() }
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        send(1400); send(-1400); send(1400)
        coVerify(exactly = 1) { media.sendPlayPause() }
        job.cancel()
    }

    @Test fun `ringing call retains enabled call gesture while preview is paused`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val job = controller.monitor().launchIn(backgroundScope)
        val token = controller.startPreview("p")
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        repeat(10) { send(0) }
        org.junit.Assert.assertNotNull(controller.preview.value.state.pose)
        phone = TelephonyManager.CALL_STATE_RINGING
        phoneReceiver?.onReceive(context, Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        org.junit.Assert.assertNull(controller.preview.value.state.pose)
        org.junit.Assert.assertEquals(eu.darken.capod.R.string.head_preview_paused_call, controller.preview.value.messageRes)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        send(1400); send(-1400); send(1400)
        verify(exactly = 1) { telecom.acceptRingingCall() }
        coVerify(exactly = 0) { media.sendPlayPause() }
        controller.stopPreview(token)
        job.cancel()
    }

    @Test fun `selected profile address overrides stale BLE snapshot for stream and report guard`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val current = "11:22:33:44:55:66"
        devices.value = listOf(mockk<PodDevice>(relaxed = true) {
            every { profileId } returns "p"
            every { address } returns "AA:BB:CC:DD:EE:FF"
            every { profileAddress } returns current
            every { isAapReady } returns true
            every { isSystemConnected } returns true
            every { isEitherPodInEar } returns true
        })
        val job = controller.monitor().launchIn(backgroundScope)
        val token = controller.startPreview("p")
        coVerify(exactly = 1) { manager.sendCommand(current, match { it is AapCommand.SetHeadTracking && it.enabled }) }
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        samples.emit("AA:BB:CC:DD:EE:FF" to HeadTrackingSample(HeadMotion(0, 0), 0, 0, 0, SystemClock.elapsedRealtime()))
        org.junit.Assert.assertEquals(0, controller.preview.value.state.calibrationCount)
        samples.emit(current to HeadTrackingSample(HeadMotion(0, 0), 0, 0, 0, SystemClock.elapsedRealtime()))
        org.junit.Assert.assertEquals(1, controller.preview.value.state.calibrationCount)
        controller.stopPreview(token)
        job.cancel()
    }

    @Test fun `explicit preview starts sensor with master gesture switch off and releases it on stop`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        config = HeadGestureConfig(enabled = false)
        val job = controller.monitor().launchIn(backgroundScope)
        val token = controller.startPreview("p")
        coVerify(exactly = 1) { manager.sendCommand("AA:BB:CC:DD:EE:FF", match { it is AapCommand.SetHeadTracking && it.enabled }) }
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        send(0)
        org.junit.Assert.assertEquals(1, controller.preview.value.state.calibrationCount)
        controller.stopPreview(token)
        coVerify(exactly = 1) { manager.sendCommand("AA:BB:CC:DD:EE:FF", match { it is AapCommand.SetHeadTracking && !it.enabled }) }
        job.cancel()
    }

    @Test fun `unknown phone state may show real sensor progress but never dispatch actions or tones`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        phone = -1
        val job = controller.monitor().launchIn(backgroundScope)
        val token = controller.startPreview("p")
        controller.setPreviewSound(token, true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(20))
        send(1400); send(-1400); send(1400)
        org.junit.Assert.assertEquals(3, controller.preview.value.state.calibrationCount)
        org.junit.Assert.assertEquals(0, controller.preview.value.nods)
        coVerify(exactly = 0) { media.sendPlayPause() }
        verify(exactly = 0) { telecom.acceptRingingCall() }
        controller.stopPreview(token)
        job.cancel()
    }

    @Test fun `replacement monitor waits for old stream stop before its start`() = runTest(UnconfinedTestDispatcher()) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val writes = mutableListOf<Boolean>()
        coEvery { manager.sendCommand(any(), any()) } answers {
            writes += (secondArg<AapCommand>() as AapCommand.SetHeadTracking).enabled
            Unit
        }
        val first = controller.monitor().launchIn(backgroundScope)
        val second = controller.monitor().launchIn(backgroundScope)
        org.junit.Assert.assertEquals(listOf(true), writes)
        first.cancelAndJoin()
        org.junit.Assert.assertEquals(listOf(true, false, true), writes)
        second.cancelAndJoin()
        org.junit.Assert.assertEquals(listOf(true, false, true, false), writes)
    }
}
