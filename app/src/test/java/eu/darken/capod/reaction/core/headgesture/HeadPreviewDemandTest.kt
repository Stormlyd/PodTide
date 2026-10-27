package eu.darken.capod.reaction.core.headgesture

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HeadPreviewDemandTest {
    @Test fun `start suppresses media before stream reconciliation and old owner cannot stop replacement`() {
        val demand = HeadPreviewDemand()
        assertEquals(0L, demand.epoch)
        val first = demand.start("old", 100)
        assertEquals(1L, demand.epoch)
        assertFalse(demand.mediaAllowed)
        val second = demand.start("new", 200)
        demand.stop(first, 201)
        assertEquals(2L, demand.epoch)
        assertEquals("new", demand.request.value?.profileId)
        assertFalse(demand.mediaAllowed)
        demand.stop(second, 202)
        assertEquals(3L, demand.epoch)
        assertTrue(demand.mediaAllowed)
    }

    @Test fun `buffered preview era report cannot trigger restored media and stale reports are rejected`() {
        val demand = HeadPreviewDemand()
        val token = demand.start("owned", 100)
        demand.stop(token, 200)
        assertFalse(demand.acceptsMediaReport(199, 201))
        assertFalse(demand.acceptsMediaReport(200, 201))
        assertFalse(demand.acceptsMediaReport(202, 2202))
        assertTrue(demand.acceptsMediaReport(202, 203))
    }

    @Test fun `recalibration advances the receive-time fence without releasing the owner`() {
        val demand = HeadPreviewDemand()
        val token = demand.start("owned", 100)
        assertTrue(demand.acceptsPreviewReport(token, 101, 102))
        assertTrue(demand.recalibrate(token, 200))
        assertEquals(token, demand.request.value?.token)
        assertFalse(demand.acceptsPreviewReport(token, 199, 201))
        assertFalse(demand.acceptsPreviewReport(token, 200, 201))
        assertTrue(demand.acceptsPreviewReport(token, 201, 202))
        assertFalse(demand.recalibrate(token + 1, 300))
    }
}
