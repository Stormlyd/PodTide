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
}
