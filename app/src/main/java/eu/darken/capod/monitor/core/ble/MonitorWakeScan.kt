package eu.darken.capod.monitor.core.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.coroutine.AppScope
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.notifications.PendingIntentCompat
import eu.darken.capod.monitor.core.receiver.MonitorWakeScanReceiver
import eu.darken.capod.monitor.core.worker.MonitoringState
import eu.darken.capod.pods.core.apple.ble.protocol.ProximityPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a system-managed BLE scan registered — results are delivered through a [PendingIntent] —
 * while no monitor session is running, so a matching earbud advertisement can bring monitoring
 * back after a session ended or the service was stopped.
 *
 * The registration lives as long as this process does. A running monitor session suppresses it
 * ([suppressForSession]) so the same advertisements are not scanned twice, and releases it again
 * when the session ends ([releaseFromSession]). It is removed outright when the user switches
 * monitoring off.
 *
 * This is an opportunity, not a guarantee: whether the platform still delivers to a process it
 * killed depends on the system and the vendor's background policy, and a scan registration does
 * not survive the process itself being destroyed.
 */
@Singleton
class MonitorWakeScan @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothManager: BluetoothManager2,
    private val monitoringState: MonitoringState,
    @AppScope private val scope: CoroutineScope,
) {

    private val mutex = Mutex()

    @Volatile
    private var registered = false

    /** Set while a monitor session owns scanning. Read before any registration attempt. */
    @Volatile
    private var suppressedBySession = false

    private val callbackIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, MonitorWakeScanReceiver::class.java).apply {
                action = MonitorWakeScanReceiver.ACTION
            },
            // Mutable: the platform fills in the scan result extras on this Intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_MUTABLE,
        )
    }

    /** Idempotent. Registers only while the user still wants monitoring and no session is running. */
    fun ensureRegistered() {
        scope.launch { mutex.withLock { register() } }
    }

    /** Idempotent removal, used when the user switches monitoring off. */
    fun unregister() {
        scope.launch { mutex.withLock { unregisterInternal() } }
    }

    /** A monitor session is taking over scanning; drop the wake registration while it runs. */
    fun suppressForSession() {
        suppressedBySession = true
        scope.launch { mutex.withLock { unregisterInternal() } }
    }

    /** The monitor session ended; the wake path may take over again if the user still wants it. */
    fun releaseFromSession() {
        suppressedBySession = false
        scope.launch { mutex.withLock { register() } }
    }

    @SuppressLint("MissingPermission")
    private suspend fun register() {
        if (registered) return
        if (suppressedBySession) {
            log(TAG) { "A monitor session is running, not registering the wake scan." }
            return
        }
        if (!monitoringState.isEnabled()) {
            log(TAG) { "Monitoring is disabled by the user, not registering the wake scan." }
            return
        }
        val scanner = bluetoothManager.scanner
        if (scanner == null) {
            // Bluetooth off or unavailable: no registration now, and no retry loop either. The
            // next process start, session end or user action will call register() again.
            log(TAG, WARN) { "No BLE scanner available, cannot register the wake scan." }
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            val filters = ProximityPairing.getBleScanFilter().toList()
            val errorCode = scanner.startScan(filters, settings, callbackIntent)
            if (errorCode != 0) {
                log(TAG, WARN) { "Wake scan startScan returned errorCode=$errorCode" }
                return
            }
            registered = true
            log(TAG) { "Wake scan registered (filters=${filters.size})." }
        } catch (e: SecurityException) {
            log(TAG, WARN) { "Wake scan registration denied: ${e.message}" }
        } catch (e: Exception) {
            log(TAG, WARN) { "Wake scan registration failed: ${e.message}" }
        }
    }

    @SuppressLint("MissingPermission")
    private fun unregisterInternal() {
        if (!registered) return
        try {
            bluetoothManager.scanner?.stopScan(callbackIntent)
        } catch (e: SecurityException) {
            log(TAG, WARN) { "Wake scan stop denied: ${e.message}" }
        } catch (e: Exception) {
            log(TAG, WARN) { "Wake scan stop failed: ${e.message}" }
        }
        registered = false
        log(TAG) { "Wake scan unregistered." }
    }

    companion object {
        private val TAG = logTag("Monitor", "WakeScan")

        // Distinct from BleScanner's callback request code so the two registrations never replace
        // each other's PendingIntent.
        private const val REQUEST_CODE = 271
    }
}
