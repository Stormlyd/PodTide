package eu.darken.capod.main.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.coroutine.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import eu.darken.capod.common.debug.logging.Logging.Priority.ERROR
import eu.darken.capod.common.debug.logging.asLog
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneBatterySource @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope appScope: CoroutineScope,
) {
    /** Synchronous sticky read, used for each new widget session after process recreation. */
    fun snapshot(): PhoneBatteryState = parse(
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    )

    val states: Flow<PhoneBatteryState> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) trySend(parse(intent))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(receiver) }
    }
        .distinctUntilChanged()
        .retryWhen { e, attempt ->
            if (e is CancellationException) return@retryWhen false
            log(TAG, ERROR) { "Phone battery receiver failed (attempt=$attempt): ${e.asLog()}" }
            delay(5_000)
            true
        }
        .shareIn(appScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)

    companion object {
        private val TAG = logTag("Widget", "PhoneBatterySource")
        internal fun parse(intent: Intent?): PhoneBatteryState {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return PhoneBatteryState(null, false)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
            val percent = if (present && level >= 0 && scale > 0 && level <= scale) {
                (level.toLong() * 100L / scale.toLong()).toInt()
            } else null
            val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                BatteryManager.BATTERY_STATUS_CHARGING
            return PhoneBatteryState(percent, charging)
        }
    }
}
