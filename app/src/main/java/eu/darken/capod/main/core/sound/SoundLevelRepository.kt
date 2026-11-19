package eu.darken.capod.main.core.sound

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.glance.appwidget.updateAll
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the most recent measurement so the home-screen widget can show it later. */
@Singleton
class SoundLevelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _lastReading = MutableStateFlow(readCached(context))
    val lastReading: StateFlow<SoundLevelReading?> = _lastReading.asStateFlow()

    suspend fun record(reading: SoundLevelReading) {
        prefs.edit()
            .putInt(KEY_DB_BITS, reading.db.toRawBits())
            .putLong(KEY_TIME, reading.measuredAt.toEpochMilli())
            .putInt(KEY_CALIBRATION, reading.calibrationDb)
            .apply()
        _lastReading.value = reading
        runCatching { eu.darken.capod.main.ui.widget.SoundLevelGlanceWidget().updateAll(context) }
    }

    companion object {
        private const val PREFS = "sound_level_widget"
        private const val KEY_DB_BITS = "last_db_bits"
        private const val KEY_TIME = "last_time"
        private const val KEY_CALIBRATION = "last_calibration"

        fun readCached(context: Context): SoundLevelReading? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_DB_BITS)) return null
            val bits = prefs.getInt(KEY_DB_BITS, 0)
            val time = prefs.getLong(KEY_TIME, 0L)
            if (time <= 0L) return null
            return SoundLevelReading(
                db = Float.fromBits(bits),
                measuredAt = Instant.ofEpochMilli(time),
                calibrationDb = prefs.getInt(KEY_CALIBRATION, SoundLevelMeter.DEFAULT_CALIBRATION_DB),
            )
        }
    }
}