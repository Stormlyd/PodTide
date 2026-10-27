package eu.darken.capod.reaction.core.headgesture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HeadPreviewRequest(val token: Long, val profileId: String, val startedAtMs: Long)

/** Synchronous mode fence: pending manager reports retain their original receive timestamp. */
class HeadPreviewDemand {
    private var nextToken = 0L
    private var modeStartedAtMs = 0L
    @Volatile private var epochValue = 0L
    val epoch: Long get() = epochValue
    private val _request = MutableStateFlow<HeadPreviewRequest?>(null)
    val request = _request.asStateFlow()
    val mediaAllowed: Boolean get() = _request.value == null

    @Synchronized fun start(profileId: String, nowMs: Long): Long {
        val token = ++nextToken
        modeStartedAtMs = nowMs
        epochValue++
        _request.value = HeadPreviewRequest(token, profileId, nowMs)
        return token
    }

    @Synchronized fun stop(token: Long, nowMs: Long): Boolean {
        if (_request.value?.token != token) return false
        modeStartedAtMs = nowMs
        epochValue++
        _request.value = null
        return true
    }

    @Synchronized fun recalibrate(token: Long, nowMs: Long): Boolean {
        if (_request.value?.token != token) return false
        modeStartedAtMs = nowMs
        epochValue++
        return true
    }

    @Synchronized
    fun acceptsMediaReport(receivedAtMs: Long, nowMs: Long): Boolean =
        mediaAllowed && receivedAtMs > modeStartedAtMs && receivedAtMs <= nowMs && nowMs - receivedAtMs < 2_000

    @Synchronized
    fun acceptsPreviewReport(token: Long, receivedAtMs: Long, nowMs: Long): Boolean =
        _request.value?.let { it.token == token && receivedAtMs > modeStartedAtMs &&
            receivedAtMs <= nowMs && nowMs - receivedAtMs < 2_000 } == true

    @Synchronized
    fun acceptsCallReport(receivedAtMs: Long, nowMs: Long): Boolean =
        receivedAtMs > modeStartedAtMs && receivedAtMs <= nowMs && nowMs - receivedAtMs < 2_000
}
