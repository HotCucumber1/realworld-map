package com.worldexplorer.domain.filter

import com.worldexplorer.domain.fakes.TestGeo
import com.worldexplorer.domain.fakes.TestGeo.BASE_LAT
import com.worldexplorer.domain.fakes.TestGeo.BASE_LON
import com.worldexplorer.domain.fakes.TestGeo.sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFilterTest {
    private val now = 1_700_000_000_000L
    private val config = LocationFilterConfig()
    private val filter = LocationFilter(config)

    private fun reason(d: FilterDecision) = (d as FilterDecision.Rejected).reason

    @Test
    fun `first valid fix is accepted with zero distance`() {
        val d = filter.evaluate(sample(t = now), now)
        assertEquals(0.0, (d as FilterDecision.Accepted).distanceFromPreviousMeters, 0.0)
    }

    @Test
    fun `invalid coordinates are rejected`() {
        assertEquals(RejectReason.INVALID_COORDINATES, reason(filter.evaluate(sample(lat = 91.0, t = now), now)))
        assertEquals(RejectReason.INVALID_COORDINATES, reason(filter.evaluate(sample(lon = Double.NaN, t = now), now)))
        assertEquals(RejectReason.NULL_ISLAND, reason(filter.evaluate(sample(lat = 0.0, lon = 0.0, t = now), now)))
    }

    @Test
    fun `invalid timestamps are rejected`() {
        assertEquals(RejectReason.INVALID_TIMESTAMP, reason(filter.evaluate(sample(t = 0), now)))
        assertEquals(RejectReason.INVALID_TIMESTAMP, reason(filter.evaluate(sample(t = now + config.maxFutureSkewMillis + 1), now)))
    }

    @Test
    fun `fixes with low or missing accuracy are rejected`() {
        assertEquals(RejectReason.LOW_ACCURACY, reason(filter.evaluate(sample(t = now, accuracy = config.maxAccuracyMeters + 1), now)))
        assertEquals(RejectReason.MISSING_ACCURACY, reason(filter.evaluate(sample(t = now, accuracy = null), now)))
        assertEquals(RejectReason.MISSING_ACCURACY, reason(filter.evaluate(sample(t = now, accuracy = 0f), now)))
        assertTrue(filter.evaluate(sample(t = now, accuracy = config.maxAccuracyMeters), now) is FilterDecision.Accepted)
    }

    @Test
    fun `same timestamp, older timestamp and bursts are rejected`() {
        filter.evaluate(sample(t = now), now)
        val moved = TestGeo.north(BASE_LAT, 3.0)
        assertEquals(RejectReason.DUPLICATE_TIMESTAMP, reason(filter.evaluate(sample(lat = moved, t = now), now)))
        assertEquals(RejectReason.OUT_OF_ORDER, reason(filter.evaluate(sample(lat = moved, t = now - 5_000), now)))
        assertEquals(RejectReason.TOO_FREQUENT, reason(filter.evaluate(sample(lat = moved, t = now + 300), now)))
    }

    @Test
    fun `identical position is a duplicate until keep-alive passes`() {
        filter.evaluate(sample(t = now), now)
        assertEquals(RejectReason.DUPLICATE_POSITION, reason(filter.evaluate(sample(t = now + 5_000), now + 5_000)))
        val later = now + config.identicalPositionKeepAliveMillis
        assertTrue("standing still keeps exploring", filter.evaluate(sample(t = later), later) is FilterDecision.Accepted)
    }

    @Test
    fun `standing still with normal jitter is accepted`() {
        filter.evaluate(sample(t = now), now)
        val jitter = TestGeo.north(BASE_LAT, 1.5)
        assertTrue(filter.evaluate(sample(lat = jitter, t = now + 4_000), now + 4_000) is FilterDecision.Accepted)
    }

    @Test
    fun `walking produces accepted fixes with haversine distance`() {
        filter.evaluate(sample(t = now), now)
        val lat = TestGeo.north(BASE_LAT, 6.0)
        val d = filter.evaluate(sample(lat = lat, t = now + 4_000), now + 4_000) as FilterDecision.Accepted
        assertEquals(6.0, d.distanceFromPreviousMeters, 0.01)
    }

    @Test
    fun `unrealistic jump is rejected`() {
        filter.evaluate(sample(t = now), now)
        val far = TestGeo.north(BASE_LAT, 2_000.0) // 2 km in 4 s = 500 m/s
        assertEquals(RejectReason.UNREALISTIC_JUMP, reason(filter.evaluate(sample(lat = far, t = now + 4_000), now + 4_000)))
        // The anchor is unchanged: a normal next fix near the old position is fine.
        val near = TestGeo.north(BASE_LAT, 5.0)
        assertTrue(filter.evaluate(sample(lat = near, t = now + 8_000), now + 8_000) is FilterDecision.Accepted)
    }

    @Test
    fun `fast but realistic movement such as a train is accepted`() {
        filter.evaluate(sample(t = now), now)
        val lat = TestGeo.north(BASE_LAT, 300.0) // 300 m in 4 s = 75 m/s
        assertTrue(filter.evaluate(sample(lat = lat, t = now + 4_000), now + 4_000) is FilterDecision.Accepted)
    }

    @Test
    fun `consistent fixes after a jump are eventually accepted as relocation`() {
        filter.evaluate(sample(t = now), now)
        var lat = TestGeo.north(BASE_LAT, 5_000.0)
        var t = now + 4_000
        val decisions = (0 until 4).map {
            val d = filter.evaluate(sample(lat = lat, t = t), t)
            lat = TestGeo.north(lat, 5.0); t += 4_000
            d
        }
        assertTrue(decisions[0] is FilterDecision.Rejected)
        assertTrue(decisions[1] is FilterDecision.Rejected)
        assertTrue("relocation accepted after ${config.relocationConfirmations} confirmations", decisions[2] is FilterDecision.Accepted)
        assertTrue(decisions[3] is FilterDecision.Accepted)
    }

    @Test
    fun `reset restores the anchor from the last stored point`() {
        filter.reset(sample(t = now))
        assertEquals(RejectReason.DUPLICATE_TIMESTAMP, reason(filter.evaluate(sample(lat = TestGeo.north(BASE_LAT, 3.0), t = now), now)))
    }

    @Test
    fun `fix with large tolerance is not a jump when within accuracy`() {
        filter.evaluate(sample(t = now, accuracy = 30f), now)
        val lat = TestGeo.north(BASE_LAT, 50.0)
        // 50 m in 1 s exceeds no speed limit check because distance is within 30 + 30 m accuracy.
        assertTrue(filter.evaluate(sample(lat = lat, lon = BASE_LON, t = now + 1_000, accuracy = 30f), now + 1_000) is FilterDecision.Accepted)
    }
}
