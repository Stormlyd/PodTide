package eu.darken.capod.monitor.core.companion

import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.monitor.core.worker.MonitorControl
import javax.inject.Inject

/**
 * Bound by the system whenever an associated pod shows up, even if our process was killed.
 *
 * This is the only wake-up path that survives process death; starting the (already gated) monitor
 * from here is allowed in the background because the app holds
 * `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`. [MonitorControl] still honours the
 * persisted user intent, so a user who switched monitoring off is not silently resumed.
 */
@RequiresApi(33)
@AndroidEntryPoint
class MonitorCompanionService : CompanionDeviceService() {

    @Inject lateinit var monitorControl: MonitorControl

    override fun onDeviceAppeared(address: String) {
        log(TAG) { "Companion device appeared: $address" }
        monitorControl.startMonitor(forceStart = false)
    }

    override fun onDeviceDisappeared(address: String) {
        log(TAG) { "Companion device disappeared: $address" }
    }

    companion object {
        private val TAG = logTag("Monitor", "CompanionService")
    }
}