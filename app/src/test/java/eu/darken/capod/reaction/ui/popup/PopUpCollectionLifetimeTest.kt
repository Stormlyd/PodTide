package eu.darken.capod.reaction.ui.popup

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.monitor.ui.MonitorNotifications
import eu.darken.capod.monitor.ui.MonitorNotificationViewFactory
import eu.darken.capod.R
import io.mockk.verify
import io.mockk.slot
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import eu.darken.capod.common.compose.preview.MockPodDataProvider
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.monitor.core.MonitorCoroutineScope
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.monitor.core.worker.MonitorService
import eu.darken.capod.reaction.core.popup.PopUpReaction
import eu.darken.capod.reaction.core.headgesture.HeadGestureConfig
import eu.darken.capod.reaction.core.headgesture.HeadGestureSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** Drives the real service startup/collection/replacement path; only external data sources/WM are faked. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class PopUpCollectionLifetimeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val device = MockPodDataProvider.dualPodMonitoredMixed()
    private val attached = mutableListOf<View>()
    private var nextEvents: Flow<PopUpReaction.Event> = emptyFlow()
    private var adds = 0
    private var headCollections = 0
    private var heartCollections = 0
    private var reconnectCollections = 0
    private val notificationDevice = MutableStateFlow<PodDevice?>(null)
    private val liveBattery = MutableStateFlow(false)
    private val extraNotification = MutableStateFlow(false)
    private val keepAfterDisconnect = MutableStateFlow(false)
    private val statusBarBattery = MutableStateFlow(false)
    private val estimates = MutableStateFlow(emptyMap<String, eu.darken.capod.monitor.core.battery.BatteryEstimate>())
    private val bottomPolicy = MutableStateFlow(true)
    private val topPolicy = MutableStateFlow(false)
    private var bottomFlow: Flow<Boolean> = bottomPolicy
    private var topFlow: Flow<Boolean> = topPolicy

    private fun TestScope.service(): MonitorService {
        val dispatcher = StandardTestDispatcher(testScheduler)
        ShadowSettings.setCanDrawOverlays(true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val manager = mockk<WindowManager>(relaxed = true)
        every { manager.currentWindowMetrics } answers { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics }
        every { manager.addView(any(), any()) } answers { attached += firstArg<View>(); adds++; Unit }
        every { manager.removeViewImmediate(any()) } answers { attached -= firstArg<View>(); Unit }
        every { manager.removeView(any()) } answers { attached -= firstArg<View>(); Unit }
        val window = PopUpWindow(object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }, mockk<GeneralSettings>(relaxed = true))
        val service = Robolectric.buildService(MonitorService::class.java).create().get()
        fun field(name: String, value: Any) = MonitorService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        field("injectionComplete", true)
        field("foregroundStartFailed", false)
        field("monitorScope", mockk<MonitorCoroutineScope> { every { coroutineContext } returns (SupervisorJob() + dispatcher) })
        service.dispatcherProvider = mockk { every { Main } returns dispatcher }
        service.popUpWindow = window
        service.notificationManager = mockk(relaxed = true)
        service.notifications = MonitorNotifications(context, service.notificationManager, MonitorNotificationViewFactory(context))
        service.generalSettings = mockk {
            every { showLiveBatteryInNotification.flow } returns liveBattery
            every { useExtraMonitorNotification.flow } returns extraNotification
            every { keepConnectedNotificationAfterDisconnect.flow } returns keepAfterDisconnect
            every { showBatteryInStatusBar.flow } returns statusBarBattery
            every { showBottomPopup.flow } answers { bottomFlow }
            every { showTopPopup.flow } answers { topFlow }
        }
        service.permissionTool = mockk { every { missingScanPermissions } returns flowOf(emptySet()) }
        service.deviceMonitor = mockk { every { primaryDeviceByTier } returns notificationDevice }
        service.batteryEstimator = mockk {
            every { estimates } returns this@PopUpCollectionLifetimeTest.estimates
            every { monitor() } returns emptyFlow()
        }
        service.monitorModeResolver = mockk { every { effectiveMode } returns emptyFlow() }
        service.profilesRepo = mockk { every { profiles } returns MutableStateFlow(emptyList()) }
        service.bluetoothManager = mockk { every { connectedDevices } returns MutableStateFlow(emptyList()) }
        service.aapConnectionManager = mockk { every { allStates } returns MutableStateFlow(emptyMap()) }
        service.popUpReaction = mockk { every { monitor() } answers { nextEvents } }
        service.playPause = mockk { every { monitor() } returns emptyFlow() }
        service.autoConnect = mockk { every { monitor() } returns flow { reconnectCollections++; awaitCancellation() } }
        service.headGestures = mockk { every { monitor() } returns flow { headCollections++; awaitCancellation() } }
        service.heartRate = mockk { every { monitor() } returns flow { heartCollections++; awaitCancellation() } }
        service.sleepReaction = mockk { every { monitor() } returns emptyFlow() }
        service.chargedReaction = mockk { every { monitor() } returns emptyFlow() }
        service.chargedReactionNotifications = mockk(relaxed = true)
        service.caseLowReminderReaction = mockk { every { monitor() } returns emptyFlow() }
        service.conversationReaction = mockk { every { monitor() } returns emptyFlow() }
        return service
    }

    @Test fun `default notification stays generic without battery driven reposts`() = runTest {
        val type = AapPodState.BatteryType.LEFT
        fun withBattery(value: Float) = device.copy(aap = AapPodState(batteries = mapOf(type to AapPodState.Battery(type, value, AapPodState.ChargingState.NOT_CHARGING))))
        notificationDevice.value = withBattery(0.8f)
        val service = service()
        val captured = mutableListOf<Notification>()
        every { service.notificationManager.notify(1, capture(captured)) } returns Unit
        try {
            service.onStartCommand(MonitorService.intent(context), 0, 1)
            runCurrent()
            verify(exactly = 1) { service.notificationManager.notify(1, any()) }
            assertEquals(context.getString(R.string.app_name), captured.last().extras.getString(Notification.EXTRA_TITLE))
            assertEquals(context.getString(R.string.monitor_notification_running), captured.last().extras.getString(Notification.EXTRA_TEXT))
            assertNull(captured.last().contentView)
            notificationDevice.value = withBattery(0.17f)
            testScheduler.advanceTimeBy(1500)
            runCurrent()
            verify(exactly = 1) { service.notificationManager.notify(1, any()) }
            notificationDevice.value = withBattery(0.17f).copy(isSystemConnected = true)
            runCurrent()
            verify(exactly = 2) { service.notificationManager.notify(1, any()) }
            assertEquals(context.getString(R.string.monitor_notification_connected), captured.last().extras.getString(Notification.EXTRA_TEXT))
            notificationDevice.value = withBattery(0.17f).copy(isSystemConnected = false)
            runCurrent()
            verify(exactly = 3) { service.notificationManager.notify(1, any()) }
            assertEquals(context.getString(R.string.monitor_notification_running), captured.last().extras.getString(Notification.EXTRA_TEXT))
            verify { service.notificationManager.cancel(2) }
        } finally { service.onDestroy(); runCurrent() }
    }

    @Test fun `switching live battery off cancels retained extra and switching on resumes real battery`() = runTest {
        val type = AapPodState.BatteryType.LEFT
        fun withBattery(value: Float) = device.copy(aap = AapPodState(batteries = mapOf(type to AapPodState.Battery(type, value, AapPodState.ChargingState.NOT_CHARGING))))
        notificationDevice.value = withBattery(0.8f)
        liveBattery.value = true
        extraNotification.value = true
        keepAfterDisconnect.value = true
        statusBarBattery.value = true
        val service = service()
        val primary = mutableListOf<Notification>()
        val connected = mutableListOf<Notification>()
        every { service.notificationManager.notify(1, capture(primary)) } returns Unit
        every { service.notificationManager.notify(2, capture(connected)) } returns Unit
        try {
            service.onStartCommand(MonitorService.intent(context), 0, 1)
            runCurrent()
            assertEquals(1, connected.size)
            assertTrue(primary.last().extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("80"))
            assertEquals(1, notificationDevice.subscriptionCount.value)
            assertEquals(1, estimates.subscriptionCount.value)
            notificationDevice.value = null
            testScheduler.advanceTimeBy(1500); runCurrent()
            verify(exactly = 0) { service.notificationManager.cancel(2) }
            liveBattery.value = false
            runCurrent()
            verify { service.notificationManager.cancel(2) }
            assertEquals(1, notificationDevice.subscriptionCount.value)
            assertEquals(0, estimates.subscriptionCount.value)
            assertEquals(context.getString(R.string.monitor_notification_running), primary.last().extras.getString(Notification.EXTRA_TEXT))
            val count = primary.size
            notificationDevice.value = withBattery(0.17f)
            testScheduler.advanceTimeBy(1500); runCurrent()
            assertEquals(count, primary.size)
            liveBattery.value = true
            runCurrent()
            assertEquals(2, connected.size)
            assertTrue(primary.last().extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("17"))
            assertTrue(extraNotification.value)
            assertTrue(keepAfterDisconnect.value)
            assertTrue(statusBarBattery.value)
        } finally { service.onDestroy(); runCurrent() }
    }

    @Test fun `public release never subscribes sensors with old enabled settings while reconnect and popup remain active`() = runTest {
        val settings = HeadGestureSettings(context)
        val oldConfig = HeadGestureConfig(enabled = true, calls = true, media = true, threshold = 1_600)
        settings.update("owned", oldConfig)
        val history = context.getSharedPreferences("podbridge_heart_history", Context.MODE_PRIVATE)
        history.edit().putString("owned", "[[1000,72]]").commit()
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
        val service = service()
        try {
            service.onStartCommand(MonitorService.intent(context), 0, 1)
            runCurrent()
            assertEquals(1, attached.size)
            assertEquals(1, reconnectCollections)
            assertMonitoringActive(service)
            assertEquals("Hidden head gestures must not collect in the background", 0, headCollections)
            assertEquals("Hidden heart rate must not collect in the background", 0, heartCollections)

            service.onStartCommand(MonitorService.intent(context, forceStart = true), 0, 2)
            runCurrent()
            assertEquals(1, attached.size)
            assertEquals(2, reconnectCollections)
            assertEquals(0, headCollections)
            assertEquals(0, heartCollections)
        } finally {
            service.onDestroy()
            runCurrent()
        }
        assertEquals(oldConfig, HeadGestureSettings(context).read("owned"))
        assertEquals("[[1000,72]]", history.getString("owned", null))
        assertTrue(attached.isEmpty())
    }

    @Test fun `normal popup collection completion releases overlay while service stays alive`() = runTest {
        val end = CompletableDeferred<Unit>()
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); end.await() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        end.complete(Unit)
        runCurrent()
        assertTrue(attached.isEmpty())
        assertMonitoringActive(service)
        service.onDestroy()
        runCurrent()
    }

    @Test fun `popup collection failure releases overlay without service destruction`() = runTest {
        val fail = CompletableDeferred<Unit>()
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); fail.await(); error("reaction failed") }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        fail.complete(Unit)
        runCurrent()
        assertTrue(attached.isEmpty())
        assertMonitoringActive(service)
        service.onDestroy()
        runCurrent()
    }

    @Test fun `force start immediately retires old overlay before replacement emits`() = runTest {
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        nextEvents = flow { awaitCancellation() }
        service.onStartCommand(MonitorService.intent(context, forceStart = true), 0, 2)
        assertTrue("Replacement must release old overlay synchronously", attached.isEmpty())
        runCurrent()
        service.onDestroy()
        runCurrent()
    }

    @Test fun `delayed old completion cannot dismiss new collection popup`() = runTest {
        val oldFinish = CompletableDeferred<Unit>()
        nextEvents = flow {
            try { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
            finally { withContext(NonCancellable) { oldFinish.await() } }
        }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        val oldView = attached.single()
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device.copy(label = "New session"))); awaitCancellation() }
        service.onStartCommand(MonitorService.intent(context, forceStart = true), 0, 2)
        runCurrent()
        val newView = attached.single()
        assertNotSame(oldView, newView)
        oldFinish.complete(Unit)
        runCurrent()
        assertEquals(listOf(newView), attached)
        assertEquals(2, adds)
        service.onDestroy()
        runCurrent()
    }
    @Test fun `monitor scope cancellation releases popup before onDestroy`() = runTest {
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        val scope = MonitorService::class.java.getDeclaredField("monitorScope").apply { isAccessible = true }.get(service) as MonitorCoroutineScope
        scope.coroutineContext.cancelChildren()
        runCurrent()
        assertTrue(attached.isEmpty())
        service.onDestroy()
        runCurrent()
    }

    @Test fun `first policy precedes events and enabling the popup does not replay old events`() = runTest {
        val ready = CompletableDeferred<Unit>()
        var eventCollected = false
        bottomFlow = flow { ready.await(); emit(false); awaitCancellation() }
        nextEvents = flow { eventCollected = true; emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertFalse(eventCollected)
        ready.complete(Unit)
        runCurrent()
        assertTrue(eventCollected)
        assertTrue(attached.isEmpty())
        service.onDestroy()
        runCurrent()
    }
    @Test fun `policy failure retires popup and stops event collection`() = runTest {
        val fail = CompletableDeferred<Unit>()
        var eventFinished = false
        bottomFlow = flow { emit(true); fail.await(); error("settings read failed") }
        nextEvents = flow {
            try { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
            finally { eventFinished = true }
        }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        fail.complete(Unit)
        runCurrent()
        assertTrue(attached.isEmpty())
        assertTrue(eventFinished)
        assertMonitoringActive(service)
        service.onDestroy()
        runCurrent()
    }
    @Test fun `event completion cancels policy and releases popup`() = runTest {
        val end = CompletableDeferred<Unit>()
        var policyFinished = false
        bottomFlow = flow { try { emit(true); awaitCancellation() } finally { policyFinished = true } }
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); end.await() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        end.complete(Unit)
        runCurrent()
        assertTrue(attached.isEmpty())
        assertTrue(policyFinished)
        service.onDestroy()
        runCurrent()
    }
    @Test fun `policy completion cancels events and releases popup`() = runTest {
        val end = CompletableDeferred<Unit>()
        bottomFlow = flow { emit(true); end.await() }
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        end.complete(Unit)
        runCurrent()
        assertTrue(attached.isEmpty())
        service.onDestroy()
        runCurrent()
    }
    @Test fun `delayed old policy cleanup cannot dismiss replacement popup`() = runTest {
        val finish = CompletableDeferred<Unit>()
        bottomFlow = flow {
            try { emit(true); awaitCancellation() }
            finally { withContext(NonCancellable) { finish.await() } }
        }
        nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
        val service = service()
        service.onStartCommand(MonitorService.intent(context), 0, 1)
        runCurrent()
        assertEquals(1, attached.size)
        bottomFlow = MutableStateFlow(true)
        service.onStartCommand(MonitorService.intent(context, forceStart = true), 0, 2)
        assertTrue(attached.isEmpty())
        runCurrent()
        val replacement = attached.toList()
        assertEquals(1, replacement.size)
        finish.complete(Unit)
        runCurrent()
        assertEquals(replacement, attached)
        service.onDestroy()
        assertTrue(attached.isEmpty())
        runCurrent()
    }

    @Test fun `policy failure retires both overlays before delayed event finalizer`() = runTest {
        verifyRetirementBeforeEventFinalizer(Termination.POLICY_FAILURE)
    }

    @Test fun `policy completion retires both overlays before delayed event finalizer`() = runTest {
        verifyRetirementBeforeEventFinalizer(Termination.POLICY_COMPLETION)
    }

    @Test fun `monitor cancellation retires both overlays before delayed event finalizer`() = runTest {
        verifyRetirementBeforeEventFinalizer(Termination.MONITOR_CANCELLATION)
    }

    private enum class Termination { POLICY_FAILURE, POLICY_COMPLETION, MONITOR_CANCELLATION }

    private suspend fun TestScope.verifyRetirementBeforeEventFinalizer(termination: Termination) {
        val terminatePolicy = CompletableDeferred<Unit>()
        val eventFinalizing = CompletableDeferred<Unit>()
        val releaseEvent = CompletableDeferred<Unit>()
        bottomFlow = when (termination) {
            Termination.POLICY_FAILURE -> flow { emit(true); terminatePolicy.await(); error("policy failed") }
            Termination.POLICY_COMPLETION -> flow { emit(true); terminatePolicy.await() }
            Termination.MONITOR_CANCELLATION -> MutableStateFlow(true)
        }
        nextEvents = flow {
            try { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device)); awaitCancellation() }
            finally { withContext(NonCancellable) { eventFinalizing.complete(Unit); releaseEvent.await() } }
        }
        val service = service()
        fun owner() = MonitorService::class.java.getDeclaredField("popupCollectionOwner").apply { isAccessible = true }.get(service)
        try {
            service.onStartCommand(MonitorService.intent(context), 0, 1)
            runCurrent()
            assertEquals(1, attached.size)
            assertNotNull(owner())
            if (termination == Termination.MONITOR_CANCELLATION) {
                val scope = MonitorService::class.java.getDeclaredField("monitorScope").apply { isAccessible = true }.get(service) as MonitorCoroutineScope
                scope.coroutineContext.cancelChildren()
            } else terminatePolicy.complete(Unit)
            runCurrent()
            assertTrue("The real event collector must be blocked in its finalizer", eventFinalizing.isCompleted)
            assertFalse(releaseEvent.isCompleted)
            assertTrue("Both styles must retire before the event finalizer is released", attached.isEmpty())
            assertNull("The terminated collection must relinquish ownership immediately", owner())

            bottomFlow = MutableStateFlow(true)
            topFlow = MutableStateFlow(true)
            nextEvents = flow { emit(PopUpReaction.Event.PopupShow(java.time.Instant.EPOCH, device.copy(label = "Replacement"))); awaitCancellation() }
            service.onStartCommand(MonitorService.intent(context, forceStart = true), 0, 2)
            runCurrent()
            val replacement = attached.toList()
            val replacementOwner = owner()
            assertEquals(1, replacement.size)
            assertNotNull(replacementOwner)
            releaseEvent.complete(Unit)
            runCurrent()
            assertEquals("Late event cleanup must not dismiss the replacement", replacement, attached)
            assertSame(replacementOwner, owner())
        } finally {
            releaseEvent.complete(Unit)
            service.onDestroy()
            runCurrent()
        }
    }

    private fun assertMonitoringActive(service: MonitorService) {
        val job = MonitorService::class.java.getDeclaredField("monitoringJob").apply { isAccessible = true }.get(service) as kotlinx.coroutines.Job
        assertTrue("The independent monitor must remain alive after popup collection completion", job.isActive)
    }}
