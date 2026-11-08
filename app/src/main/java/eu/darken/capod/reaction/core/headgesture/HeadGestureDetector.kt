package eu.darken.capod.reaction.core.headgesture

import kotlin.math.abs
import kotlin.math.roundToInt

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
    private data class PosePoint(val pitch: Float, val yaw: Float, val atMs: Long)
    private val poseHistory = ArrayDeque<PosePoint>()

    fun reset() {
        axis = null
        direction = 0
        reversals = 0
        startedAt = 0L
        poseHistory.clear()
    }

    fun consume(sample: HeadTrackingSample, pose: HeadPose? = null): HeadGesture? {
        consume(sample.motion, sample.receivedAtMs)?.let {
            poseHistory.clear()
            return it
        }
        if (pose != null) {
            return consumePose(pose, sample.receivedAtMs)
        }
        return null
    }

    fun consumePose(pose: HeadPose, nowMillis: Long): HeadGesture? {
        if (nowMillis < cooldownUntil) return null
        if (poseHistory.isNotEmpty() && nowMillis < poseHistory.last().atMs) {
            poseHistory.clear()
        }
        poseHistory.addLast(PosePoint(pose.pitch, pose.yaw, nowMillis))
        while (poseHistory.isNotEmpty() && nowMillis - poseHistory.first().atMs > 1_600L) {
            poseHistory.removeFirst()
        }
        if (poseHistory.size < 4) return null

        val scale = (threshold / 1_000f).coerceIn(0.5f, 2.5f)
        val minNodDownDeg = 6.0f * scale
        val minNodReturnDeg = 3.2f * scale
        val minShakeLegDeg = 5.0f * scale
        val minShakeSpanDeg = 8.0f * scale
        val minShakeReturnDeg = 3.2f * scale

        val minPitch = poseHistory.minOf { it.pitch }
        val maxPitch = poseHistory.maxOf { it.pitch }
        val pitchSpan = maxPitch - minPitch

        val minYaw = poseHistory.minOf { it.yaw }
        val maxYaw = poseHistory.maxOf { it.yaw }
        val yawSpan = maxYaw - minYaw

        if (pitchSpan >= minNodDownDeg && pitchSpan >= yawSpan * 1.25f) {
            if (hasDownwardNod(poseHistory.map { it.pitch }, minNodDownDeg, minNodReturnDeg)) {
                cooldownUntil = nowMillis + 650L
                reset()
                return HeadGesture.NOD
            }
        }
        if (yawSpan >= minShakeSpanDeg && yawSpan >= pitchSpan * 1.15f) {
            if (hasBilateralShake(poseHistory.map { it.yaw }, minShakeLegDeg, minShakeSpanDeg, minShakeReturnDeg)) {
                cooldownUntil = nowMillis + 650L
                reset()
                return HeadGesture.SHAKE
            }
        }
        return null
    }

    private fun hasDownwardNod(series: List<Float>, minDownDeg: Float, minReturnDeg: Float): Boolean {
        var trough = series.first()
        var peakAfterTrough = trough
        var downwardArmed = false
        for (value in series) {
            if (!downwardArmed) {
                if (value < trough) {
                    trough = value
                    peakAfterTrough = value
                } else if (value > peakAfterTrough) {
                    peakAfterTrough = value
                    if (peakAfterTrough - trough >= minDownDeg) {
                        downwardArmed = true
                    }
                }
            } else {
                if (value > peakAfterTrough) {
                    peakAfterTrough = value
                } else if (peakAfterTrough - value >= minReturnDeg) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasBilateralShake(series: List<Float>, minFirstLegDeg: Float, minCrossSpanDeg: Float, minReturnDeg: Float): Boolean {
        return hasSideToSideCycle(series, firstDirection = 1, minFirstLegDeg, minCrossSpanDeg, minReturnDeg) ||
            hasSideToSideCycle(series, firstDirection = -1, minFirstLegDeg, minCrossSpanDeg, minReturnDeg)
    }

    private fun hasSideToSideCycle(
        series: List<Float>,
        firstDirection: Int,
        minFirstLegDeg: Float,
        minCrossSpanDeg: Float,
        minReturnDeg: Float,
    ): Boolean {
        var origin = series.first()
        var extreme1 = origin
        var extreme2 = origin
        var stage = 0 // 0 = waiting for side 1, 1 = waiting for opposite side 2, 2 = waiting for return toward center
        for (value in series) {
            val signedFromOrigin = (value - origin) * firstDirection
            when (stage) {
                0 -> {
                    if (signedFromOrigin < 0f) {
                        origin = value
                        extreme1 = value
                    } else if ((value - extreme1) * firstDirection > 0f) {
                        extreme1 = value
                        if ((extreme1 - origin) * firstDirection >= minFirstLegDeg) {
                            stage = 1
                            extreme2 = extreme1
                        }
                    }
                }
                1 -> {
                    if ((value - extreme1) * firstDirection > 0f) {
                        extreme1 = value
                        extreme2 = value
                    } else if ((extreme2 - value) * firstDirection > 0f) {
                        extreme2 = value
                        if ((extreme1 - extreme2) * firstDirection >= minCrossSpanDeg) {
                            stage = 2
                        }
                    }
                }
                2 -> {
                    if ((extreme2 - value) * firstDirection > 0f) {
                        extreme2 = value
                    } else if ((value - extreme2) * firstDirection >= minReturnDeg) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun consume(motion: HeadMotion, nowMillis: Long): HeadGesture? {
        if (nowMillis < cooldownUntil || abs(motion.horizontal) > 6_000 || abs(motion.vertical) > 6_000) return null
        val activeThreshold = (threshold * 0.85f).roundToInt().coerceAtLeast(500)
        val h = abs(motion.horizontal)
        val v = abs(motion.vertical)
        val dominant = when {
            h >= activeThreshold && h > v * 1.4 -> HeadGesture.SHAKE
            v >= activeThreshold && v > h * 1.4 -> HeadGesture.NOD
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
        cooldownUntil = nowMillis + 600L
        reset()
        return dominant
    }
}
