package eu.darken.capod.reaction.core.heartrate

enum class HeartRateStatus { STOPPED, WAITING, ACTIVE, STALE }
data class HeartRateReading(val status: HeartRateStatus = HeartRateStatus.STOPPED, val bpm: Int? = null)
data class HeartRateReport(val bpm: Int, val receivedAtMs: Long)

class HeartRateSession(private val staleAfterMs: Long = 10_000) {
    var reading = HeartRateReading(); private set
    private var lastSampleMs: Long? = null
    private var waitingSinceMs: Long? = null
    fun start(nowMs: Long) {
        lastSampleMs = null
        waitingSinceMs = nowMs
        reading = HeartRateReading(HeartRateStatus.WAITING)
    }
    fun sample(bpm: Int, nowMs: Long, receivedAtMs: Long = nowMs): Boolean {
        if (reading.status == HeartRateStatus.STOPPED || bpm !in 1..255) return false
        if (receivedAtMs < (waitingSinceMs ?: return false) || receivedAtMs > nowMs || nowMs - receivedAtMs >= staleAfterMs) return false
        if (lastSampleMs?.let { receivedAtMs < it } == true) return false
        lastSampleMs = receivedAtMs
        reading = HeartRateReading(HeartRateStatus.ACTIVE, bpm)
        return true
    }
    fun tick(nowMs: Long) {
        val last = lastSampleMs ?: waitingSinceMs ?: return
        if (nowMs - last >= staleAfterMs) reading = HeartRateReading(HeartRateStatus.STALE)
    }
    fun stop() { lastSampleMs = null; waitingSinceMs = null; reading = HeartRateReading() }
}

object SensorServices {
    // Upstream version3 is AAP Information segment 10, not firmwareVersion (segment 4).
    // Preserve its first-digit convention and tolerate missing/non-numeric metadata.
    private fun newFirmware(version: String?): Boolean = version?.firstOrNull()?.digitToIntOrNull()?.let { it >= 9 } == true
    fun heartRate(marketingVersion: String?): Int = if (newFirmware(marketingVersion)) 84 else 19
    fun headMotion(marketingVersion: String?): Int = if ((marketingVersion?.firstOrNull()?.digitToIntOrNull() ?: 8) >= 8) 16 else 14
}
