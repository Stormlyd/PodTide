package eu.darken.capod.reaction.core.headgesture

import kotlin.math.abs

data class HeadMotion(val horizontal: Int, val vertical: Int)
enum class HeadGesture { NOD, SHAKE }
enum class GestureAction { NONE, PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, ANSWER, DECLINE }

object GestureActionResolver {
    fun resolve(gesture: HeadGesture, ringing: Boolean, nod: GestureAction, shake: GestureAction): GestureAction =
        if (ringing) {
            if (gesture == HeadGesture.NOD) GestureAction.ANSWER else GestureAction.DECLINE
        } else {
            if (gesture == HeadGesture.NOD) nod else shake
        }
}

class HeadGestureDetector(private val threshold: Int = 1_000) {
    private var axis: HeadGesture? = null
    private var direction = 0
    private var reversals = 0
    private var startedAt = 0L
    private var cooldownUntil = 0L

    fun consume(motion: HeadMotion, nowMillis: Long): HeadGesture? {
        if (nowMillis < cooldownUntil || abs(motion.horizontal) > 6_000 || abs(motion.vertical) > 6_000) return null
        val h = abs(motion.horizontal)
        val v = abs(motion.vertical)
        val dominant = when {
            h >= threshold && h > v * 1.6 -> HeadGesture.SHAKE
            v >= threshold && v > h * 1.6 -> HeadGesture.NOD
            else -> return null
        }
        val sign = if ((if (dominant == HeadGesture.SHAKE) motion.horizontal else motion.vertical) > 0) 1 else -1
        if (axis != dominant || nowMillis - startedAt > 1_300 || nowMillis < startedAt) {
            axis = dominant
            direction = sign
            reversals = 1
            startedAt = nowMillis
            return null
        }
        if (sign != direction) {
            direction = sign
            reversals++
        }
        if (reversals < 3) return null
        cooldownUntil = nowMillis + 1_800
        axis = null
        reversals = 0
        return dominant
    }
}
