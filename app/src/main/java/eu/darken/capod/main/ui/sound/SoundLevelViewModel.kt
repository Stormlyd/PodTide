package eu.darken.capod.main.ui.sound

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.capod.main.core.sound.SoundLevelMeter
import eu.darken.capod.main.core.sound.SoundLevelReading
import eu.darken.capod.main.core.sound.SoundLevelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SoundLevelViewModel @Inject constructor(
    private val meter: SoundLevelMeter,
    private val repository: SoundLevelRepository,
) : ViewModel() {

    val calibration: StateFlow<Int> = meter.calibrationDb
    val lastReading: StateFlow<SoundLevelReading?> = repository.lastReading

    var isMeasuring by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun start() {
        if (isMeasuring) return
        error = null
        isMeasuring = true
        job = viewModelScope.launch {
            var lastPersistedAt = 0L
            meter.stream()
                .onEach { reading ->
                    // Persist/widget-refresh at most once per second; the meter itself stays ~10 Hz.
                    val now = reading.measuredAt.toEpochMilli()
                    if (now - lastPersistedAt >= 1_000L) {
                        lastPersistedAt = now
                        repository.record(reading)
                    }
                }
                .catch { e ->
                    error = e.message ?: e::class.simpleName
                    isMeasuring = false
                }
                .collect()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        isMeasuring = false
    }

    fun setCalibration(db: Int) = meter.setCalibration(db)

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}