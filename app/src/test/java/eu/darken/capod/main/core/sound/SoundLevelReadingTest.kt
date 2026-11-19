package eu.darken.capod.main.core.sound

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.time.Instant

class SoundLevelReadingTest : BaseTest() {

    private fun reading(db: Float) = SoundLevelReading(
        db = db,
        measuredAt = Instant.ofEpochMilli(1000),
        calibrationDb = SoundLevelMeter.DEFAULT_CALIBRATION_DB,
    )

    @Test
    fun `bands follow the documented thresholds`() {
        reading(30f).band shouldBe SoundLevelReading.Band.QUIET
        reading(39.9f).band shouldBe SoundLevelReading.Band.QUIET
        reading(40f).band shouldBe SoundLevelReading.Band.NORMAL
        reading(64.9f).band shouldBe SoundLevelReading.Band.NORMAL
        reading(65f).band shouldBe SoundLevelReading.Band.LOUD
        reading(84.9f).band shouldBe SoundLevelReading.Band.LOUD
        reading(85f).band shouldBe SoundLevelReading.Band.VERY_LOUD
    }
}