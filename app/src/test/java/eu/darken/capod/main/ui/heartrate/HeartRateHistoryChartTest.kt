package eu.darken.capod.main.ui.heartrate

import eu.darken.capod.reaction.core.heartrate.HeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateHistoryChartTest {
    @Test fun `records are ordered by time and gaps do not get joined`() {
        val samples = listOf(HeartRateSample(50_000, 95), HeartRateSample(5_000, 70), HeartRateSample(10_000, 80))
        assertEquals(listOf(listOf(samples[1], samples[2]), listOf(samples[0])), heartRateSegments(samples))
    }

    @Test fun `invalid readings do not appear on the chart`() {
        val valid = HeartRateSample(10_000, 80)
        assertEquals(listOf(listOf(valid)), heartRateSegments(listOf(HeartRateSample(5_000, 0), valid)))
        assertEquals(emptyList<List<HeartRateSample>>(), heartRateSegments(emptyList()))
    }
}
