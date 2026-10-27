package eu.darken.capod.main.ui.widget

import eu.darken.capod.common.coroutine.AppScope
import eu.darken.capod.common.debug.logging.Logging.Priority.ERROR
import eu.darken.capod.common.debug.logging.asLog
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneBatteryWidgetUpdater @Inject constructor(
    private val settings: WidgetSettings,
    private val source: PhoneBatterySource,
    private val widgetManager: WidgetManager,
    @AppScope private val appScope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun setup() {
        if (!started.compareAndSet(false, true)) return
        appScope.launch {
            settings.enabledPhoneWidgetIds
                .retryWhen { cause, attempt ->
                    if (cause is CancellationException) return@retryWhen false
                    log(TAG, ERROR) { "Phone widget settings failed (attempt=$attempt): ${cause.asLog()}" }
                    // Drop the previous eligibility while settings cannot confirm it.
                    emit(emptySet())
                    delay(RETRY_DELAY_MS)
                    true
                }
                .distinctUntilChanged()
                .flatMapLatest { savedIds ->
                    if (savedIds.isEmpty()) emptyFlow() else flow {
                        emit(savedIds intersect widgetManager.installedBatteryWidgetIds())
                    }.retryWhen { cause, attempt ->
                        if (cause is CancellationException) return@retryWhen false
                        log(TAG, ERROR) { "Cannot list battery widgets (attempt=$attempt): ${cause.asLog()}" }
                        delay(RETRY_DELAY_MS)
                        true
                    }.flatMapLatest { ids ->
                        if (ids.isEmpty()) emptyFlow() else source.states
                            .distinctUntilChanged()
                            .onEach {
                                try {
                                    widgetManager.refreshBatteryWidgets(ids)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    log(TAG, ERROR) { "Phone battery widget update failed: ${e.asLog()}" }
                                }
                            }
                        }
                }
                .collect {}
        }
    }

    companion object {
        private const val RETRY_DELAY_MS = 1_000L
        private val TAG = logTag("Widget", "PhoneBatteryUpdater")
    }
}
