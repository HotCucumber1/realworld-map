package com.worldexplorer.domain.tracking

import com.worldexplorer.domain.fakes.TestGeo
import com.worldexplorer.domain.model.TrackingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRequestPolicyTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `few samples are never stationary`() {
        assertFalse(LocationRequestPolicy.isStationary(listOf(TestGeo.sample(t = now)), now))
    }

    @Test
    fun `clustered samples are stationary and get the longer interval`() {
        val samples = (0 until 6).map { TestGeo.sample(lat = TestGeo.north(TestGeo.BASE_LAT, it * 0.5), t = now - it * 5_000) }
        assertTrue(LocationRequestPolicy.isStationary(samples, now))
        assertEquals(TrackingProfile.BALANCED.stationaryIntervalMillis, LocationRequestPolicy.intervalMillis(TrackingProfile.BALANCED, true))
    }

    @Test
    fun `walking samples are not stationary`() {
        val samples = (0 until 6).map { TestGeo.sample(lat = TestGeo.north(TestGeo.BASE_LAT, it * 6.0), t = now - it * 4_000) }
        assertFalse(LocationRequestPolicy.isStationary(samples, now))
    }

    @Test
    fun `reported speed prevents stationary mode`() {
        val samples = (0 until 6).map { TestGeo.sample(t = now - it * 4_000, speed = 1.4f) }
        assertFalse(LocationRequestPolicy.isStationary(samples, now))
    }
}
