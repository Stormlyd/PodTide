package eu.darken.capod.reaction.core.heartrate

import android.content.Context
import android.os.SystemClock
import androidx.annotation.StringRes
import eu.darken.capod.R
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.monitor.core.DeviceMonitor
import eu.darken.capod.monitor.core.primaryDevice
import eu.darken.capod.pods.core.apple.PodModel
import eu.darken.capod.pods.core.apple.aap.AapConnectionManager
import eu.darken.capod.pods.core.apple.aap.protocol.AapCommand
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import eu.darken.capod.profiles.core.SelectedDeviceTarget
import eu.darken.capod.reaction.core.sensors.SensorStreamLease
import eu.darken.capod.reaction.core.sensors.SensorTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

data class HeartRateSample(val atMillis: Long, val bpm: Int)
data class HeartRateUiState(
    val requested: Boolean = false,
    val reading: HeartRateReading = HeartRateReading(),
    val history: List<HeartRateSample> = emptyList(),
    @StringRes val messageRes: Int? = null,
)

/** AAP remains the only socket owner. This controller accepts measurements only during an active request. */
@Singleton
class HeartRateController @Inject constructor(
    @ApplicationContext context: Context,
    private val deviceMonitor: DeviceMonitor,
    private val manager: AapConnectionManager,
    private val profilesRepo: DeviceProfilesRepo,
) {
    private val preferences = context.getSharedPreferences("podbridge_heart_history", Context.MODE_PRIVATE)
    private val requestedProfile = MutableStateFlow<String?>(null)
    private val _states = MutableStateFlow<Map<String, HeartRateUiState>>(emptyMap())
    val states: StateFlow<Map<String, HeartRateUiState>> = _states

    fun state(profile: String): HeartRateUiState = _states.value[profile] ?: HeartRateUiState(history = loadHistory(profile))
    fun start(profile: String) {
        if (requestedProfile.value == profile) return
        requestedProfile.value?.takeIf { it != profile }?.let { stop(it) }
        _states.update { it + (profile to state(profile).copy(
            requested = true,
            reading = HeartRateReading(),
            messageRes = R.string.heart_rate_waiting_connection,
        )) }
        requestedProfile.value = profile
    }
    fun stop(profile: String) {
        if (requestedProfile.value == profile) requestedProfile.value = null
        _states.update { it + (profile to state(profile).copy(requested = false, reading = HeartRateReading(), messageRes = null)) }
    }
    fun clearHistory(profile: String) {
        preferences.edit().remove(profile).apply()
        _states.update { it + (profile to state(profile).copy(history = emptyList())) }
    }

    private fun loadHistory(profile: String): List<HeartRateSample> = try {
        val array = JSONArray(preferences.getString(profile, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val row = array.getJSONArray(index)
            val bpm = row.getInt(1)
            if (bpm in 1..255) HeartRateSample(row.getLong(0), bpm) else null
        }.takeLast(300)
    } catch (_: Exception) { emptyList() }

    private fun appendHistory(profile: String, bpm: Int): List<HeartRateSample> {
        val old = state(profile).history
        val now = System.currentTimeMillis()
        if (old.lastOrNull()?.let { now - it.atMillis in 0..4_999 } == true) return old
        val updated = (old + HeartRateSample(now, bpm)).takeLast(300)
        val array = JSONArray()
        updated.forEach { array.put(JSONArray().put(it.atMillis).put(it.bpm)) }
        preferences.edit().putString(profile, array.toString()).apply()
        return updated
    }

    fun monitor(): Flow<Unit> = channelFlow<Unit> {
        val session = HeartRateSession()
        var activeProfile: String? = null
        var activeTarget: SelectedDeviceTarget? = null
        val leaseMutex = Mutex()
        val stream = SensorStreamLease { target, enabled ->
            manager.sendCommand(target.address, AapCommand.SetHeartRateTracking(enabled, target.service))
        }

        fun publish(profile: String, @StringRes message: Int? = null) {
            _states.update { current ->
                val old = current[profile] ?: HeartRateUiState(history = loadHistory(profile))
                current + (profile to old.copy(
                    requested = requestedProfile.value == profile,
                    reading = if (activeProfile == profile) session.reading else HeartRateReading(),
                    messageRes = message,
                ))
            }
        }

        suspend fun stopActive() {
            val oldProfile = activeProfile
            activeProfile = null
            activeTarget = null
            session.stop() // Reject late measurements before the asynchronous stop request.
            oldProfile?.let { publish(it, if (requestedProfile.value == it) R.string.heart_rate_waiting_connection else null) }
            stream.stop()
        }

        launch {
            combine(deviceMonitor.primaryDevice(), requestedProfile, profilesRepo.selectionChanges) { device, profile, _ ->
                device to profile
            }.collect { (device, profile) ->
                leaseMutex.withLock {
                    val selected = profile?.let { profilesRepo.selectedTarget(it) }
                    val address = selected?.address?.takeIf { it.isNotBlank() }
                    val canStart = profile != null && device?.profileId == profile && device.model == PodModel.AIRPODS_PRO3 &&
                        device.isSystemConnected && device.isAapReady && device.isEitherPodInEar == true &&
                        address != null && device.profileAddress == address
                    val target = if (canStart) address else null
                    val service = SensorServices.heartRate(device?.aap?.deviceInfo?.marketingVersion)
                    if (stream.isReady && stream.target == SensorTarget(target ?: "", service) &&
                        profile == activeProfile && selected == activeTarget) return@withLock
                    stopActive()
                    if (profile == null) return@withLock
                    if (target == null) {
                        publish(profile, when {
                            device?.profileId != profile -> R.string.heart_rate_choose_primary
                            address == null || device.profileAddress != address -> R.string.heart_rate_waiting_connection
                            device.model != PodModel.AIRPODS_PRO3 -> R.string.heart_rate_unsupported_model
                            !device.isSystemConnected || !device.isAapReady -> R.string.heart_rate_waiting_connection
                            else -> R.string.heart_rate_wear_earbuds
                        })
                        return@withLock
                    }
                    try {
                        if (profilesRepo.selectedTarget(profile) != selected) return@withLock
                        activeProfile = profile
                        activeTarget = selected
                        session.start(SystemClock.elapsedRealtime())
                        stream.start(SensorTarget(target, service))
                        publish(profile)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { session.stop(); activeProfile = null; activeTarget = null; publish(profile, R.string.heart_rate_start_failed) }
                }
            }
        }
        launch {
            manager.heartRate.collect { (address, report) ->
                leaseMutex.withLock {
                    val profile = activeProfile ?: return@withLock
                    if (!stream.isReady || address != stream.target?.address || requestedProfile.value != profile) return@withLock
                    if (profilesRepo.selectedTarget(profile) != activeTarget) {
                        stopActive()
                        return@withLock
                    }
                    if (!session.sample(report.bpm, SystemClock.elapsedRealtime(), report.receivedAtMs)) return@withLock
                    val history = appendHistory(profile, report.bpm)
                    _states.update { it + (profile to HeartRateUiState(true, session.reading, history)) }
                }
            }
        }
        launch {
            while (true) {
                delay(1_000)
                session.tick(SystemClock.elapsedRealtime())
                activeProfile?.let { publish(it) }
            }
        }
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { leaseMutex.withLock { stopActive() } } }
    }.flowOn(Dispatchers.Main.immediate)
}
