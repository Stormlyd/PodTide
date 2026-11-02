package eu.darken.capod.monitor.core.worker

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.asLog
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.hasApiLevel
import eu.darken.capod.common.permissions.Permission
import eu.darken.capod.common.startServiceCompat
import eu.darken.capod.monitor.core.ble.MonitorWakeScan
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonitorControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitoringState: MonitoringState,
    private val wakeScan: MonitorWakeScan,
) {

    /**
     * Requests a monitor session. Every entry point — UI, boot broadcast, Bluetooth events, the
     * wake scan and sticky service restarts — funnels through here so the persisted user intent is
     * checked in exactly one place. [MonitorService] re-checks it before rebuilding a session.
     */
    @SuppressLint("NewApi")
    fun startMonitor(
        forceStart: Boolean = false,
    ) {
        log(TAG, VERBOSE) { "startMonitor(forceStart=$forceStart)" }

        // "The system ended the process" and "the user switched monitoring off" are different
        // states. Only the second one blocks a start request, and it blocks every entry point,
        // including delayed callbacks that arrive after the user's action.
        if (!monitoringState.enabled.value) {
            log(TAG) { "Monitoring is disabled by the user, ignoring start request." }
            return
        }

        val hasBluetoothPermission =
            Permission.BLUETOOTH.isGranted(context) || Permission.BLUETOOTH_SCAN.isGranted(context)
        if (!hasBluetoothPermission) {
            log(TAG, WARN) { "Missing Bluetooth permission, not starting monitor service." }
            return
        }

        try {
            context.startServiceCompat(MonitorService.intent(context, forceStart))
            log(TAG) { "Monitor start request sent." }
        } catch (e: IllegalStateException) {
            if (hasApiLevel(31) && e is ForegroundServiceStartNotAllowedException) {
                log(TAG, WARN) { "Start rejected, app is not allowed to start a FGS from the background: ${e.asLog()}" }
            } else {
                log(TAG, WARN) { "Failed to start monitor service: ${e.asLog()}" }
            }
        } catch (e: SecurityException) {
            log(TAG, WARN) { "Failed to start monitor service, permission issue: ${e.asLog()}" }
        }
    }

    fun stopMonitor() {
        log(TAG, VERBOSE) { "stopMonitor()" }
        context.stopService(MonitorService.intent(context))
        log(TAG) { "Monitor stop request sent." }
    }

    /**
     * Applies the user's explicit monitoring choice.
     *
     * The intent is persisted BEFORE anything is torn down, so a boot broadcast, Bluetooth event or
     * delayed scan callback that races the shutdown already sees the stop intent and cannot resume
     * monitoring. Re-enabling restores the wake scan registration and requests a session. Leaving a
     * screen, backgrounding the app or returning home never calls this.
     */
    suspend fun setMonitoringEnabled(enabled: Boolean) {
        log(TAG) { "setMonitoringEnabled($enabled)" }
        monitoringState.setEnabled(enabled)
        if (enabled) {
            wakeScan.ensureRegistered()
            startMonitor(forceStart = false)
        } else {
            wakeScan.unregister()
            stopMonitor()
        }
    }

    companion object {
        private val TAG = logTag("Monitor", "Control")
    }
}
