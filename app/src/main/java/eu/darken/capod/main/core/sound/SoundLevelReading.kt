package eu.darken.capod.main.core.sound

import java.time.Instant

/**
 * Approximate environmental sound pressure level measured by the phone microphone.
 *
 * This is NOT an AirPods measurement: the known AAP protocol in this project has no sound-level
 * report. The value is only shown as a rough dB SPL estimate and can be calibrated by the user.
 */
data class SoundLevelReading(
    val db: Float,
    val measuredAt: Instant,
    val calibrationDb: Int,
) {
    val band: Band
        get() = when {
            db < 40f -> Band.QUIET
            db < 65f -> Band.NORMAL
            db < 85f -> Band.LOUD
            else -> Band.VERY_LOUD
        }

    enum class Band { QUIET, NORMAL, LOUD, VERY_LOUD }
}