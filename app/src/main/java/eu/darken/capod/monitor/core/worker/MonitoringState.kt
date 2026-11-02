package eu.darken.capod.monitor.core.worker

import eu.darken.capod.common.coroutine.AppScope
import eu.darken.capod.common.datastore.value
import eu.darken.capod.main.core.GeneralSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's explicit monitoring intent, mirrored in memory so synchronous entry points (boot
 * receiver, Bluetooth events, scan callbacks) can check it without a blocking store read.
 *
 * The mirror is seeded eagerly and can briefly lag right after a process start, so
 * [MonitorService] re-reads the persisted value before it rebuilds a session: a stale `true`
 * never gets to restore monitoring the user switched off.
 */
@Singleton
class MonitoringState @Inject constructor(
    private val generalSettings: GeneralSettings,
    @AppScope scope: CoroutineScope,
) {

    val enabled: StateFlow<Boolean> = generalSettings.monitoringEnabled.flow
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Persists the intent. Callers apply the consequences (stop service, wake scan) themselves. */
    suspend fun setEnabled(enabled: Boolean) {
        generalSettings.monitoringEnabled.value(enabled)
    }

    /** Authoritative read straight from the store, for suspend contexts. */
    suspend fun isEnabled(): Boolean = generalSettings.monitoringEnabled.value()
}
