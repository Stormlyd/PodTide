package eu.darken.capod.main.core.sound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.capod.common.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Continuous sound-level estimator based on the phone microphone.
 *
 * Microphone full-scale is mapped to roughly 120 dB SPL with a user-adjustable calibration offset.
 * Recordings are never written to storage; only the measured level is emitted.
 */
@Singleton
class SoundLevelMeter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeSource: TimeSource,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _calibrationDb = MutableStateFlow(prefs.getInt(KEY_CALIBRATION, DEFAULT_CALIBRATION_DB))
    val calibrationDb: StateFlow<Int> = _calibrationDb.asStateFlow()

    fun setCalibration(db: Int) {
        val clamped = db.coerceIn(MIN_CALIBRATION_DB, MAX_CALIBRATION_DB)
        prefs.edit().putInt(KEY_CALIBRATION, clamped).apply()
        _calibrationDb.value = clamped
    }

    fun stream(): Flow<SoundLevelReading> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO not granted")
        }

        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IllegalStateException("AudioRecord buffer unavailable: $minBuffer")

        val samplesPerWindow = sampleRate / 10 // ~100 ms
        val bufferSize = maxOf(minBuffer, samplesPerWindow * 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord could not be initialised")
        }

        var smoothed: Float? = null
        try {
            recorder.startRecording()
            val buffer = ShortArray(samplesPerWindow)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var sumSquares = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    sumSquares += s * s
                }
                val rms = sqrt(sumSquares / read)
                val dbfs = if (rms <= 0.0) -120.0 else 20.0 * log10(rms / 32768.0)
                val raw = (dbfs + _calibrationDb.value).toFloat().coerceIn(MIN_DB, MAX_DB)
                val next = smoothed?.let { it + (raw - it) * SMOOTHING } ?: raw
                smoothed = next
                emit(
                    SoundLevelReading(
                        db = next,
                        measuredAt = timeSource.now(),
                        calibrationDb = _calibrationDb.value,
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val PREFS = "sound_level"
        private const val KEY_CALIBRATION = "calibration_db"
        const val DEFAULT_CALIBRATION_DB = 100
        const val MIN_CALIBRATION_DB = 80
        const val MAX_CALIBRATION_DB = 120
        private const val SAMPLE_RATE = 44100
        private const val SMOOTHING = 0.22f
        private const val MIN_DB = 30f
        private const val MAX_DB = 120f
    }
}