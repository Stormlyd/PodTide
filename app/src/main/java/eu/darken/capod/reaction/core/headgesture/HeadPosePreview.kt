package eu.darken.capod.reaction.core.headgesture

data class HeadTrackingSample(val motion: HeadMotion, val rotationA: Int, val rotationB: Int, val rotationC: Int, val receivedAtMs: Long)
data class HeadPose(val pitch: Float, val yaw: Float)
enum class HeadPreviewStatus { STOPPED, WAITING, CALIBRATING, LIVE, STALE }
data class HeadPreviewState(val status: HeadPreviewStatus = HeadPreviewStatus.STOPPED, val pose: HeadPose? = null, val motion: HeadMotion? = null, val calibrationCount: Int = 0)

class HeadPosePreview {
    var state = HeadPreviewState(); private set
    private var startedAt = 0L
    private var lastReport: Long? = null
    private val neutralSamples = mutableListOf<Triple<Int, Int, Int>>()
    private var neutral: Pair<Float, Float>? = null
    private var prevRotation: Pair<Int, Int>? = null
    private var stillFrames = 0

    fun start(nowMs: Long) {
        startedAt = nowMs; lastReport = null; neutralSamples.clear(); neutral = null
        prevRotation = null; stillFrames = 0
        state = HeadPreviewState(HeadPreviewStatus.WAITING)
    }
    fun calibrate(nowMs: Long) {
        if (state.status == HeadPreviewStatus.STOPPED) return
        start(nowMs)
        state = state.copy(status = HeadPreviewStatus.CALIBRATING)
    }
    fun stop() {
        neutralSamples.clear(); neutral = null; lastReport = null
        prevRotation = null; stillFrames = 0
        state = HeadPreviewState()
    }
    fun receive(sample: HeadTrackingSample, nowMs: Long): Boolean {
        if (state.status == HeadPreviewStatus.STOPPED || sample.receivedAtMs < startedAt || sample.receivedAtMs > nowMs || nowMs - sample.receivedAtMs >= 2_000) return false
        if (sample.motion.horizontal !in -6_000..6_000 || sample.motion.vertical !in -6_000..6_000) return false
        if (lastReport?.let { sample.receivedAtMs < it } == true) return false
        lastReport = sample.receivedAtMs
        if (neutral == null) {
            neutralSamples += Triple(sample.rotationA, sample.rotationB, sample.rotationC)
            if (neutralSamples.maxOf { it.first } - neutralSamples.minOf { it.first } > 1_200 ||
                neutralSamples.maxOf { it.second } - neutralSamples.minOf { it.second } > 1_200 ||
                neutralSamples.maxOf { it.third } - neutralSamples.minOf { it.third } > 1_200) {
                neutralSamples.clear(); neutralSamples += Triple(sample.rotationA, sample.rotationB, sample.rotationC)
            }
            if (neutralSamples.size >= 10) {
                neutral = neutralSamples.map { it.second }.average().toFloat() to neutralSamples.map { it.third }.average().toFloat()
            }
        } else {
            val prev = prevRotation
            if (prev != null &&
                kotlin.math.abs(sample.rotationB - prev.first) <= 220 &&
                kotlin.math.abs(sample.rotationC - prev.second) <= 220 &&
                kotlin.math.abs(sample.motion.horizontal) <= 300 &&
                kotlin.math.abs(sample.motion.vertical) <= 300
            ) {
                stillFrames++
                val alpha = if (stillFrames >= 8) 0.08f else 0.02f
                val current = neutral!!
                neutral = (current.first + (sample.rotationB - current.first) * alpha) to
                    (current.second + (sample.rotationC - current.second) * alpha)
            } else {
                stillFrames = 0
            }
        }
        prevRotation = sample.rotationB to sample.rotationC
        val pose = neutral?.let { baseline ->
            val b = sample.rotationB - baseline.first
            val c = sample.rotationC - baseline.second
            // Same transform as pinned LibrePods main HeadOrientation.calculateOrientation.
            HeadPose((b + c) / 2f / 32_000f * 180f, (b - c) / 2f / 32_000f * 180f)
        }
        state = HeadPreviewState(if (pose == null) HeadPreviewStatus.CALIBRATING else HeadPreviewStatus.LIVE, pose, sample.motion, neutralSamples.size.coerceAtMost(10))
        return true
    }
    fun tick(nowMs: Long) {
        if (state.status != HeadPreviewStatus.STOPPED && nowMs - (lastReport ?: startedAt) >= 2_000) {
            state = state.copy(status = HeadPreviewStatus.STALE, pose = null, motion = null)
        }
    }
}
