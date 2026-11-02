package eu.darken.capod.monitor.core.receiver

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The Hilt-injected `onReceive` is a two-line adapter around [MonitorWakeScanReceiver.shouldStartMonitoring];
 * this covers the decision itself, which is where the "controlled recovery, not a result pipe" rule
 * lives: only our own action and only an error-free delivery may ask for a session.
 */
class MonitorWakeScanReceiverTest : BaseTest() {

    @Test
    fun `a matching advertisement requests a monitor session`() {
        MonitorWakeScanReceiver.shouldStartMonitoring(
            action = MonitorWakeScanReceiver.ACTION,
            errorCode = 0,
        ) shouldBe true
    }

    @Test
    fun `an unrelated broadcast is ignored`() {
        MonitorWakeScanReceiver.shouldStartMonitoring(
            action = "eu.darken.capod.something.else",
            errorCode = 0,
        ) shouldBe false
        MonitorWakeScanReceiver.shouldStartMonitoring(action = null, errorCode = 0) shouldBe false
    }

    @Test
    fun `a scan delivery error does not request a session`() {
        MonitorWakeScanReceiver.shouldStartMonitoring(
            action = MonitorWakeScanReceiver.ACTION,
            errorCode = 3,
        ) shouldBe false
    }
}