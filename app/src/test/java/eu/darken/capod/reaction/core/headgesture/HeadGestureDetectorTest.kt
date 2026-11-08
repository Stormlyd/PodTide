package eu.darken.capod.reaction.core.headgesture

import testhelpers.shouldBe
import org.junit.jupiter.api.Test

class HeadGestureDetectorTest {
    @Test
    fun `stillness and a one direction movement never activate a gesture`() {
        val detector = HeadGestureDetector()
        (0..20).map { detector.consume(HeadMotion(it * 20, 0), it * 50L) }.filterNotNull() shouldBe emptyList()
    }

    @Test
    fun `three alternating vertical movements produce one nod with cooldown`() {
        val detector = HeadGestureDetector()
        val values = listOf(0, 1400, 0, -1400, 0, 1400, 0, -1400)
        val events = values.mapIndexedNotNull { i, v -> detector.consume(HeadMotion(0, v), i * 120L) }
        events shouldBe listOf(HeadGesture.NOD)
    }

    @Test
    fun `horizontal movement selects shake and calibration extremes are discarded`() {
        val detector = HeadGestureDetector()
        detector.consume(HeadMotion(32767, 0), 0) shouldBe null
        val events = listOf(0, 1400, 0, -1400, 0, 1400).mapIndexedNotNull { i, v ->
            detector.consume(HeadMotion(v, 0), 100L + i * 120)
        }
        events shouldBe listOf(HeadGesture.SHAKE)
    }

    @Test
    fun `music mapping is overridden by an incoming call`() {
        GestureActionResolver.resolve(HeadGesture.NOD, true, GestureAction.NEXT, GestureAction.PREVIOUS) shouldBe GestureAction.ANSWER
        GestureActionResolver.resolve(HeadGesture.SHAKE, true, GestureAction.NEXT, GestureAction.PREVIOUS) shouldBe GestureAction.DECLINE
        GestureActionResolver.resolve(HeadGesture.NOD, false, GestureAction.NEXT, GestureAction.PREVIOUS) shouldBe GestureAction.NEXT
    }

    @Test
    fun `pose trajectory detects nod and shake when raw motion delta stays zero`() {
        val detector = HeadGestureDetector()
        val nodPitches = listOf(0f, 4f, 9f, 13f, 6f, -2f, 1f)
        val nodEvents = nodPitches.mapIndexedNotNull { i, pitch ->
            val t = 100L + i * 60L
            detector.consume(HeadTrackingSample(HeadMotion(0, 0), 0, 0, 0, t), HeadPose(pitch = pitch, yaw = 0f))
        }
        nodEvents shouldBe listOf(HeadGesture.NOD)

        // Upward-only head tilt (bottom to top, then returning to center) must NOT count as a downward nod
        val upwardOnlyPitches = listOf(0f, -4f, -9f, -13f, -6f, -1f, 0f)
        val upwardEvents = upwardOnlyPitches.mapIndexedNotNull { i, pitch ->
            val t = 1_500L + i * 60L
            detector.consume(HeadTrackingSample(HeadMotion(0, 0), 0, 0, 0, t), HeadPose(pitch = pitch, yaw = 0f))
        }
        upwardEvents shouldBe emptyList()

        // Single-side head turn (center -> left -> center) must NOT count as a shake
        val oneSideYaws = listOf(0f, -5f, -12f, -4f, 0f)
        val oneSideEvents = oneSideYaws.mapIndexedNotNull { i, yaw ->
            val t = 2_200L + i * 60L
            detector.consume(HeadTrackingSample(HeadMotion(0, 0), 0, 0, 0, t), HeadPose(pitch = 0f, yaw = yaw))
        }
        oneSideEvents shouldBe emptyList()

        // Bilateral shake (center -> left -> right -> center) counts as a shake
        val shakeYaws = listOf(0f, -4f, -8f, -2f, 5f, 2f, 0f)
        val shakeEvents = shakeYaws.mapIndexedNotNull { i, yaw ->
            val t = 3_000L + i * 60L
            detector.consume(HeadTrackingSample(HeadMotion(0, 0), 0, 0, 0, t), HeadPose(pitch = 0f, yaw = yaw))
        }
        shakeEvents shouldBe listOf(HeadGesture.SHAKE)
    }
}
