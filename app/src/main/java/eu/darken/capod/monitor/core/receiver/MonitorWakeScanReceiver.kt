package eu.darken.capod.monitor.core.receiver

import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.monitor.core.worker.MonitorControl
import javax.inject.Inject

/**
 * Controlled recovery entry for the system-managed wake scan registered by
 * [eu.darken.capod.monitor.core.ble.MonitorWakeScan].
 *
 * This is deliberately not a result pipe: scan payloads are not parsed here. The receiver asks for
 * a full monitor session, which rebuilds scanning, connection management, device state
 * subscriptions and popup baselines. [MonitorControl] checks the persisted user intent, so a wake
 * event that arrives after the user switched monitoring off — including a delayed one — is
 * ignored instead of restarting monitoring.
 */
@AndroidEntryPoint
class MonitorWakeScanReceiver : BroadcastReceiver() {

    @Inject lateinit var monitorControl: MonitorControl

    override fun onReceive(context: Context, intent: Intent) {
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (!shouldStartMonitoring(action = intent.action, errorCode = errorCode)) {
            log(TAG, WARN) { "Ignoring wake scan event (action=${intent.action}, errorCode=$errorCode)" }
            return
        }

        log(TAG) { "Wake scan matched an earbud advertisement, requesting a monitor session." }
        monitorControl.startMonitor(forceStart = false)
    }

    companion object {
        private val TAG = logTag("Monitor", "WakeScan", "Receiver")
        const val ACTION = "eu.darken.capod.monitor.WAKE_SCAN_RESULT"

        /**
         * The receiver's entire decision, kept pure so it is testable without the Hilt graph: only
         * our own action, and only a delivery without a platform error, may request a session. A
         * scan error must not be turned into a restart attempt.
         */
        internal fun shouldStartMonitoring(action: String?, errorCode: Int): Boolean =
            action == ACTION && errorCode == 0
    }
}