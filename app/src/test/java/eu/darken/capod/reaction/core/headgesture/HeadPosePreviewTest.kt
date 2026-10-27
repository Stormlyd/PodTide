package eu.darken.capod.reaction.core.headgesture

import org.junit.jupiter.api.Test
import testhelpers.shouldBe

class HeadPosePreviewTest {
    private fun sample(at: Long, b: Int = 1000, c: Int = -1000, motion: HeadMotion = HeadMotion(0, 0), a: Int = 19000) = HeadTrackingSample(motion, a, b, c, at)
    private fun calibrated(): HeadPosePreview = HeadPosePreview().apply {
        start(0)
        repeat(10) { receive(sample(it * 40L), it * 40L) }
    }

    @Test fun `waiting does not display invented zeros and missing first sample becomes stale`() {
        val preview = HeadPosePreview()
        preview.start(10)
        preview.state shouldBe HeadPreviewState(HeadPreviewStatus.WAITING)
        preview.tick(2_009)
        preview.state.status shouldBe HeadPreviewStatus.WAITING
        preview.tick(2_010)
        preview.state shouldBe HeadPreviewState(HeadPreviewStatus.STALE)
    }

    @Test fun `ten neutral samples calibrate and preserve upstream pitch yaw transform`() {
        val preview = calibrated()
        preview.state.status shouldBe HeadPreviewStatus.LIVE
        preview.state.calibrationCount shouldBe 10
        preview.state.pose shouldBe HeadPose(0f, 0f)
        preview.receive(sample(500, 17000, 15000), 500) shouldBe true
        preview.state.pose shouldBe HeadPose(90f, 0f)
        preview.receive(sample(600, 17000, -17000), 600) shouldBe true
        preview.state.pose shouldBe HeadPose(0f, 90f)
    }

    @Test fun `prior session future stale and calibration sentinel samples are rejected`() {
        val preview = HeadPosePreview()
        preview.receive(sample(0), 0) shouldBe false
        preview.start(100)
        preview.receive(sample(90), 110) shouldBe false
        preview.receive(sample(120), 110) shouldBe false
        preview.receive(sample(100), 2100) shouldBe false
        preview.receive(sample(110, motion = HeadMotion(32767, 0)), 110) shouldBe false
        preview.state.calibrationCount shouldBe 0
        preview.receive(sample(120), 120) shouldBe true
        preview.stop()
        preview.receive(sample(130), 130) shouldBe false
        preview.state shouldBe HeadPreviewState()
    }

    @Test fun `stale hides live values and new sample resumes calibration baseline`() {
        val preview = calibrated()
        preview.tick(2_360)
        preview.state.pose shouldBe null
        preview.state.motion shouldBe null
        preview.state.status shouldBe HeadPreviewStatus.STALE
        preview.receive(sample(2_400, 17000, 15000), 2_400) shouldBe true
        preview.state.pose shouldBe HeadPose(90f, 0f)
        preview.calibrate(2_500)
        preview.state.pose shouldBe null
        preview.state.calibrationCount shouldBe 0
        preview.receive(sample(2_490), 2_510) shouldBe false
    }

    @Test fun `moving head cannot finish neutral calibration`() {
        val preview = HeadPosePreview()
        preview.start(0)
        repeat(20) { preview.receive(sample(it * 40L, if (it % 2 == 0) 0 else 4000, 0), it * 40L) }
        preview.state.status shouldBe HeadPreviewStatus.CALIBRATING
        repeat(10) { preview.receive(sample(1000 + it * 40L, 4000, 0), 1000 + it * 40L) }
        preview.state.status shouldBe HeadPreviewStatus.LIVE
    }

    @Test fun `first rotation axis instability also resets calibration window`() {
        val preview = HeadPosePreview()
        preview.start(0)
        repeat(20) { i -> preview.receive(sample(i * 40L, a = if (i % 2 == 0) 0 else 4_000), i * 40L) }
        preview.state.status shouldBe HeadPreviewStatus.CALIBRATING
        repeat(10) { i -> preview.receive(sample(1_000 + i * 40L, a = 4_000), 1_000 + i * 40L) }
        preview.state.status shouldBe HeadPreviewStatus.LIVE
    }
}
