package eu.darken.capod.pods.core.apple.aap.engine

import eu.darken.capod.common.TimeSource
import eu.darken.capod.pods.core.apple.aap.protocol.*
import eu.darken.capod.pods.core.apple.aap.AapPodState
import eu.darken.capod.pods.core.apple.PodModel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.time.Instant

class CustomEqEngineTest : BaseTest() {
    private val clock = mockk<TimeSource> {
        every { now() } returns Instant.ofEpochMilli(1000)
        every { currentTimeMillis() } returns 1000
    }
    private val profile = DefaultAapDeviceProfile(PodModel.AIRPODS_PRO3)
    private val eq = AapSetting.CustomEq(2, 0, 50, 100)
    private fun frame(opcode: Int, vararg payload: Int) = AapMessage.parse(
        byteArrayOf(4, 0, 4, 0, opcode.toByte(), 0) + payload.map { it.toByte() }.toByteArray())!!
    private fun ready(scope: TestScope) = AapSessionEngine(profile, clock).apply {
        start(scope); onHandshakeSent(); processMessage(frame(2))
    }

    @Test
    fun `previous equal readback and unrelated traffic cannot confirm new EQ request`() = runTest {
        val engine = ready(this)
        engine.processMessage(AapMessage.parse(profile.encodeCommand(AapCommand.SetCustomEq(eq)))!!)
        val rejected = mutableListOf<AapCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.settingRejected.collect { rejected += it } }
        val sent = mutableListOf<AapCommand>()
        engine.send(AapCommand.SetCustomEq(eq)) { sent += it }
        engine.processMessage(frame(9, 0x26, 1))
        advanceTimeBy(4001); runCurrent()
        rejected shouldContain AapCommand.SetCustomEq(eq)
        sent.size shouldBe 2
        engine.state.value.customEqConfirmationRevision shouldBe 0
        engine.reset()
    }

    @Test
    fun `concurrent ANC and EQ each retain verification and reject missing echoes`() = runTest {
        val engine = ready(this)
        val rejected = mutableListOf<AapCommand>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.settingRejected.collect { rejected += it } }
        val anc = AapCommand.SetAncMode(AapSetting.AncMode.Value.ON)
        engine.send(anc) { }
        engine.send(AapCommand.SetCustomEq(eq)) { }
        advanceTimeBy(4001); runCurrent()
        rejected shouldContain anc
        rejected shouldContain AapCommand.SetCustomEq(eq)
        engine.reset()
    }

    @Test
    fun `only a new matching EQ frame confirms the request`() = runTest {
        val engine = ready(this)
        val sent = mutableListOf<AapCommand>()
        engine.send(AapCommand.SetCustomEq(eq)) { sent += it }
        engine.state.value.pendingCustomEq shouldBe eq
        engine.processMessage(AapMessage.parse(profile.encodeCommand(AapCommand.SetCustomEq(eq.copy(high = 99))))!!)
        engine.state.value.pendingCustomEq shouldBe eq
        engine.state.value.customEqConfirmationRevision shouldBe 0
        engine.processMessage(AapMessage.parse(profile.encodeCommand(AapCommand.SetCustomEq(eq)))!!)
        engine.state.value.pendingCustomEq shouldBe null
        engine.state.value.customEqConfirmationRevision shouldBe 1
        advanceTimeBy(4001); runCurrent()
        sent.size shouldBe 1
        engine.reset()
    }

    @Test
    fun `EQ remains queued while both pods are out and gets its own timer on flush`() {
        val controller = AapOutboundController(clock)
        val out = AapPodState().withSetting(
            AapSetting.EarDetection::class,
            AapSetting.EarDetection(
                AapSetting.EarDetection.PodPlacement.NOT_IN_EAR,
                AapSetting.EarDetection.PodPlacement.NOT_IN_EAR,
            ),
        )
        val command = AapCommand.SetCustomEq(eq)
        val queued = controller.onCommandRequested(out, OutboundRuntimeState(), command)
        queued.commandsToSend shouldBe emptyList()
        queued.podState.pendingCustomEq shouldBe eq
        queued.podState.setting<AapSetting.CustomEq>() shouldBe null
        val inEar = queued.podState.withSetting(
            AapSetting.EarDetection::class,
            AapSetting.EarDetection(
                AapSetting.EarDetection.PodPlacement.IN_EAR,
                AapSetting.EarDetection.PodPlacement.NOT_IN_EAR,
            ),
        )
        val flushed = controller.onEarDetectionInEar(inEar, queued.runtimeState)
        flushed.commandsToSend shouldBe listOf(command)
        flushed.runtimeState.customEqVerification?.command shouldBe command
        flushed.timerActions shouldContain EngineTimerAction.Start(
            EngineTimerKey.CustomEqVerification,
            AapOutboundController.VERIFICATION_TIMEOUT_MS,
        )
    }
}
