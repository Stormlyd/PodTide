package eu.darken.capod.reaction.core.heartrate

import org.junit.jupiter.api.Test
import testhelpers.shouldBe

class HeartRateSessionTest {
    @Test fun `buffered prior-session and stale reports cannot become a live reading after restart`() {
        val session = HeartRateSession()
        session.start(100)
        session.sample(72, 150, 150) shouldBe true
        session.stop(); session.start(200)
        session.sample(72, 250, 150) shouldBe false
        session.sample(72, 10_300, 250) shouldBe false
        session.sample(72, 10_300, 10_301) shouldBe false
        session.sample(75, 10_300, 10_299) shouldBe true
        session.reading shouldBe HeartRateReading(HeartRateStatus.ACTIVE, 75)
    }

    @Test fun `missing first report times out without inventing a live measurement`() {
        val session = HeartRateSession()
        session.start(500)
        session.tick(10_499)
        session.reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        session.tick(10_500)
        session.reading shouldBe HeartRateReading(HeartRateStatus.STALE)
        session.start(11_000)
        session.tick(11_001)
        session.reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
    }

    @Test fun `start command is waiting and only a valid measurement becomes active`() {
        val session = HeartRateSession()
        session.start(0)
        session.reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
        session.sample(0, 100) shouldBe false
        session.sample(72, 200) shouldBe true
        session.reading shouldBe HeartRateReading(HeartRateStatus.ACTIVE, 72)
    }

    @Test fun `stale reading disappears and resumes only on a new report`() {
        val session = HeartRateSession()
        session.start(0); session.sample(72, 0)
        session.tick(9_999)
        session.reading.bpm shouldBe 72
        session.tick(10_000)
        session.reading shouldBe HeartRateReading(HeartRateStatus.STALE)
        session.sample(75, 10_100) shouldBe true
        session.reading.bpm shouldBe 75
    }

    @Test fun `stop and disconnect reject late packets and restart clears previous measurement`() {
        val session = HeartRateSession()
        session.sample(72, 0) shouldBe false
        session.start(0); session.sample(72, 100); session.stop()
        session.sample(80, 200) shouldBe false
        session.reading shouldBe HeartRateReading()
        session.start(300)
        session.reading shouldBe HeartRateReading(HeartRateStatus.WAITING)
    }

    @Test fun `firmware service uses marketing field first digit with safe missing fallback`() {
        SensorServices.heartRate("8454624") shouldBe 19
        SensorServices.heartRate("9B2091") shouldBe 84
        SensorServices.heartRate(null) shouldBe 19
        SensorServices.heartRate("") shouldBe 19
        SensorServices.heartRate("unknown") shouldBe 19
        SensorServices.headMotion("9B2091") shouldBe 16
        SensorServices.headMotion("8454624") shouldBe 16
        SensorServices.headMotion("7A1") shouldBe 14
        SensorServices.headMotion(null) shouldBe 16
    }
}
