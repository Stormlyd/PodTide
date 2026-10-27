package eu.darken.capod.reaction.core.headgesture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.MediaControl
import eu.darken.capod.R
import eu.darken.capod.monitor.core.PodDevice
import eu.darken.capod.monitor.core.worker.MonitorControl
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.primaryDevice
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.reaction.core.sensors.SensorStreamLease
import eu.darken.capod.reaction.core.sensors.SensorTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeadGestureController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: DeviceMonitor,
    private val manager: AapConnectionManager,
    private val settings: HeadGestureSettings,
    private val media: MediaControl,
    private val monitorControl: MonitorControl,
    private val feedback: HeadPreviewTone,
) {
    private val tag = logTag("PodBridge", "HeadGestures")
    private val telephony get() = context.getSystemService(TelephonyManager::class.java)
    private val previewDemand = HeadPreviewDemand()
    private val monitorMutex = Mutex()
    private val _preview = MutableStateFlow(HeadPreviewUiState())
    val preview: StateFlow<HeadPreviewUiState> = _preview
    private val calibrationVersion = MutableStateFlow(0L)

    @Synchronized fun startPreview(profileId: String): Long {
        val token = previewDemand.start(profileId, SystemClock.elapsedRealtime())
        feedback.release()
        _preview.value = HeadPreviewUiState(token = token, requested = true, messageRes = R.string.head_preview_waiting_monitor)
        monitorControl.startMonitor()
        return token
    }

    @Synchronized fun stopPreview(token: Long) {
        if (!previewDemand.stop(token, SystemClock.elapsedRealtime())) return
        feedback.release()
        _preview.value = HeadPreviewUiState()
    }

    @Synchronized fun recalibratePreview(token: Long) {
        if (previewDemand.request.value?.token != token) return
        calibrationVersion.value++ // Fence the sample collector before asynchronous reconciliation.
        previewDemand.recalibrate(token, SystemClock.elapsedRealtime())
        _preview.update { it.copy(state = HeadPreviewState(HeadPreviewStatus.CALIBRATING), lastSampleAgeMs = null,
            messageRes = R.string.head_preview_calibrating) }
    }

    fun resetPreviewCounts(token: Long) {
        if (previewDemand.request.value?.token == token) _preview.update { it.copy(nods = 0, shakes = 0) }
    }

    fun setPreviewSound(token: Long, enabled: Boolean) {
        if (previewDemand.request.value?.token != token) return
        if (!enabled) feedback.release()
        _preview.update { it.copy(soundEnabled = enabled) }
    }

    @Suppress("DEPRECATION")
    private fun callState(): Int = try {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
            telephony?.callState ?: -1
        else -1
    } catch (_: SecurityException) { -1 }

    private fun calls(): Flow<Int> = callbackFlow {
        trySend(callState())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { trySend(callState()) }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    fun monitor(): Flow<Unit> = channelFlow<Unit> { monitorMutex.withLock {
        val phoneState = MutableStateFlow(callState())
        val stream = SensorStreamLease { target, enabled ->
            manager.sendCommand(target.address, AapCommand.SetHeadTracking(enabled, target.service))
        }
        var detector = HeadGestureDetector()
        var detectorModeEpoch = previewDemand.epoch
        var streamStartedAtMs = Long.MAX_VALUE
        var ringingStartedAtMs = Long.MAX_VALUE
        var activeConfig: HeadGestureConfig? = null
        var consumedCall = false
        var trackingCallState = -1
        val posePreview = HeadPosePreview()
        var previewToken: Long? = null
        var previewReceiving = false
        var appliedCalibration = calibrationVersion.value
        var previewDetector = HeadGestureDetector()
        var lastPreviewReceivedAt: Long? = null

        fun waitingReason(device: PodDevice?, profile: String): Int = when {
            device?.profileId != profile -> R.string.head_preview_choose_primary
            !device.isSystemConnected -> R.string.head_preview_waiting_audio
            !device.isAapReady -> R.string.head_preview_waiting_aap
            device.isEitherPodInEar != true -> R.string.head_preview_wear
            else -> R.string.head_preview_waiting_samples
        }

        launch { calls().collect { state -> if (state != TelephonyManager.CALL_STATE_RINGING) consumedCall = false; phoneState.value = state } }
        launch {
            combine(monitor.primaryDevice(), settings.changes, phoneState, previewDemand.request, calibrationVersion) { device, _, call, request, calibration ->
                Triple(device, call, request) to calibration
            }.collect { (values, calibration) ->
                    val (device, call, request) = values
                    val config = device?.profileId?.let(settings::read)
                    val wantsCalls = config?.calls == true && call == TelephonyManager.CALL_STATE_RINGING && !consumedCall
                    val wantsMedia = config?.media == true && call == TelephonyManager.CALL_STATE_IDLE && previewDemand.mediaAllowed
                    val wantsPreview = request != null && device?.profileId == request.profileId &&
                        call !in setOf(TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK)
                    val target = (device?.profileAddress?.takeIf { it.isNotBlank() } ?: device?.address)?.takeIf {
                        device?.isAapReady == true && device.isSystemConnected && device.isEitherPodInEar == true &&
                            ((config?.enabled == true && (wantsCalls || wantsMedia)) || wantsPreview)
                    }
                    val paused = request != null && call in setOf(TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK)
                    if (paused) feedback.release()
                    val canPreview = request != null && target != null && !paused
                    if (request?.token != previewToken || canPreview != previewReceiving || calibration != appliedCalibration) {
                        detector = HeadGestureDetector(config?.threshold ?: 1_000)
                        previewToken = request?.token
                        previewReceiving = canPreview
                        appliedCalibration = calibration
                        posePreview.stop()
                        lastPreviewReceivedAt = null
                        previewDetector = HeadGestureDetector(config?.threshold ?: 1_000)
                        if (canPreview) {
                            posePreview.start(SystemClock.elapsedRealtime())
                            if (calibration != 0L) posePreview.calibrate(SystemClock.elapsedRealtime())
                        }
                    }
                    if (request != null && _preview.value.token == request.token) {
                        val reason = when {
                            paused -> R.string.head_preview_paused_call
                            target == null -> waitingReason(device, request.profileId)
                            call == -1 -> R.string.head_preview_unknown_call
                            posePreview.state.status == HeadPreviewStatus.LIVE -> null
                            else -> R.string.head_preview_waiting_samples
                        }
                        _preview.update { it.copy(state = if (canPreview) posePreview.state else HeadPreviewState(HeadPreviewStatus.WAITING),
                            lastSampleAgeMs = if (canPreview) it.lastSampleAgeMs else null, messageRes = reason) }
                    }
                    val service = eu.darken.capod.reaction.core.heartrate.SensorServices.headMotion(device?.aap?.deviceInfo?.marketingVersion)
                    if (stream.isReady && stream.target == SensorTarget(target ?: "", service) && config == activeConfig && call == trackingCallState) return@collect
                    streamStartedAtMs = Long.MAX_VALUE
                    stream.stop()
                    activeConfig = config
                    ringingStartedAtMs = if (call == TelephonyManager.CALL_STATE_RINGING) SystemClock.elapsedRealtime() else Long.MAX_VALUE
                    trackingCallState = call
                    detector = HeadGestureDetector(config?.threshold ?: 1_000)
                    if (target != null) {
                        try {
                            streamStartedAtMs = SystemClock.elapsedRealtime()
                            stream.start(SensorTarget(target, service))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (e: Exception) {
                            log(tag, WARN) { "Tracking start failed: $e" }
                            if (request != null && _preview.value.token == request.token) {
                                posePreview.stop()
                                previewReceiving = false
                                _preview.update { it.copy(state = HeadPreviewState(HeadPreviewStatus.WAITING),
                                    lastSampleAgeMs = null, messageRes = R.string.head_preview_start_failed) }
                            }
                        }
                    }
                }
        }
        launch {
            manager.headSamples.collect { (address, sample) ->
                if (!stream.isReady || address != stream.target?.address) return@collect
                val now = SystemClock.elapsedRealtime()
                if (sample.receivedAtMs <= streamStartedAtMs || sample.receivedAtMs > now || now - sample.receivedAtMs >= 2_000) return@collect
                val call = callState()
                val request = previewDemand.request.value
                if (previewDemand.epoch != detectorModeEpoch) {
                    detectorModeEpoch = previewDemand.epoch
                    detector = HeadGestureDetector(activeConfig?.threshold ?: 1_000)
                }
                if (request != null && _preview.value.token == request.token &&
                    call in setOf(TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK)) {
                    feedback.release()
                    _preview.update { it.copy(state = HeadPreviewState(HeadPreviewStatus.WAITING),
                        lastSampleAgeMs = null, messageRes = R.string.head_preview_paused_call) }
                }
                if (request != null && previewReceiving && previewToken == request.token &&
                    calibrationVersion.value == appliedCalibration &&
                    _preview.value.token == request.token &&
                    call !in setOf(TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK) &&
                    previewDemand.acceptsPreviewReport(request.token, sample.receivedAtMs, now) &&
                    posePreview.receive(sample, now)) {
                    lastPreviewReceivedAt = sample.receivedAtMs
                    val gesture = if (posePreview.state.status == HeadPreviewStatus.LIVE && call == TelephonyManager.CALL_STATE_IDLE)
                        previewDetector.consume(sample.motion, sample.receivedAtMs) else null
                    _preview.update { old -> old.copy(state = posePreview.state, lastSampleAgeMs = now - sample.receivedAtMs,
                        messageRes = when {
                            call == -1 -> R.string.head_preview_unknown_call
                            posePreview.state.status == HeadPreviewStatus.CALIBRATING -> R.string.head_preview_calibrating
                            else -> null
                        },
                        nods = old.nods + if (gesture == HeadGesture.NOD) 1 else 0,
                        shakes = old.shakes + if (gesture == HeadGesture.SHAKE) 1 else 0) }
                    if (gesture != null && _preview.value.soundEnabled && previewDemand.request.value?.token == request.token) {
                        feedback.play()
                    }
                }
                val config = activeConfig ?: return@collect
                if (call != TelephonyManager.CALL_STATE_IDLE && call != TelephonyManager.CALL_STATE_RINGING) return@collect
                if (call != trackingCallState) return@collect
                val ringing = call == TelephonyManager.CALL_STATE_RINGING
                if (!ringing && !previewDemand.acceptsMediaReport(sample.receivedAtMs, now)) return@collect
                if (ringing && (!config.enabled || sample.receivedAtMs <= ringingStartedAtMs ||
                    !previewDemand.acceptsCallReport(sample.receivedAtMs, now))) return@collect
                if ((ringing && (!config.calls || consumedCall)) || (!ringing && !config.media)) return@collect
                val gesture = detector.consume(sample.motion, sample.receivedAtMs) ?: return@collect
                val action = GestureActionResolver.resolve(gesture, ringing, config.nod, config.shake)
                try {
                    if (ringing && action in setOf(GestureAction.ANSWER, GestureAction.DECLINE)) consumedCall = true
                    execute(action)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    consumedCall = false
                    log(tag, WARN) { "Gesture action $action failed: $e" }
                }
            }
        }
        launch {
            while (true) {
                delay(250)
                val request = previewDemand.request.value ?: continue
                if (!previewReceiving || previewToken != request.token ||
                    calibrationVersion.value != appliedCalibration || _preview.value.token != request.token) continue
                val now = SystemClock.elapsedRealtime()
                posePreview.tick(now)
                _preview.update { old -> old.copy(state = posePreview.state,
                    lastSampleAgeMs = lastPreviewReceivedAt?.let { now - it },
                    messageRes = if (posePreview.state.status == HeadPreviewStatus.STALE) R.string.head_preview_stale else old.messageRes) }
            }
        }
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                posePreview.stop()
                lastPreviewReceivedAt = null
                val ownedToken = previewToken
                if (ownedToken != null && previewDemand.request.value?.token == ownedToken) {
                    _preview.update { old -> if (old.token == ownedToken) old.copy(
                        state = HeadPreviewState(HeadPreviewStatus.WAITING), lastSampleAgeMs = null,
                        messageRes = R.string.head_preview_waiting_monitor,
                    ) else old }
                }
                stream.stop()
                feedback.release()
            }
        }
    } }.flowOn(Dispatchers.Main.immediate)

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun execute(action: GestureAction) {
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.PLAY_PAUSE -> media.sendPlayPause()
            GestureAction.NEXT -> media.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            GestureAction.PREVIOUS -> media.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            GestureAction.VOLUME_UP -> media.adjustVolumeUp()
            GestureAction.VOLUME_DOWN -> media.adjustVolumeDown()
            GestureAction.ANSWER, GestureAction.DECLINE -> {
                check(context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) { "Call permission not granted" }
                val telecom = checkNotNull(context.getSystemService(TelecomManager::class.java))
                if (action == GestureAction.ANSWER) telecom.acceptRingingCall()
                else if (Build.VERSION.SDK_INT >= 28) {
                    check(telecom.endCall()) { "Call was not declined" }
                } else error("Call decline requires Android 9 or newer")
            }
        }
    }
}
