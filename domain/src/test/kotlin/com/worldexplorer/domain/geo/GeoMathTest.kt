package com.worldexplorer.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun `one degree of longitude on the equator is about 111 km`() {
        assertEquals(111_195.0, GeoMath.haversineMeters(0.0, 0.0, 0.0, 1.0), 1.0)
    }

    @Test
    fun `one degree of longitude at 60 degrees is half as long as at the equator`() {
        val atEquator = GeoMath.haversineMeters(0.0, 10.0, 0.0, 11.0)
        val at60 = GeoMath.haversineMeters(60.0, 10.0, 60.0, 11.0)
        assertEquals(0.5, at60 / atEquator, 0.001)
    }

    @Test
    fun `distance across the antimeridian is short, not around the world`() {
        val d = GeoMath.haversineMeters(10.0, 179.9995, 10.0, -179.9995)
        assertEquals(109.5, d, 1.0)
    }

    @Test
    fun `distance is not computed from raw degree differences`() {
        // 0.001° of latitude vs 0.001° of longitude at 70°N differ by a factor of ~3.
        val dLat = GeoMath.haversineMeters(70.0, 20.0, 70.001, 20.0)
        val dLon = GeoMath.haversineMeters(70.0, 20.0, 70.0, 20.001)
        assertEquals(dLat * 0.342, dLon, 0.5)
    }

    @Test
    fun `path length sums segments`() {
        val pts = listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001).map { com.worldexplorer.domain.model.GeoPoint(it.first, it.second) }
        assertEquals(2 * 111.195, GeoMath.pathLengthMeters(pts), 0.05)
    }

    @Test
    fun `longitude normalization wraps into -180 to 180`() {
        assertEquals(-170.0, GeoMath.normalizeLongitude(190.0), 1e-9)
        assertEquals(170.0, GeoMath.normalizeLongitude(-190.0), 1e-9)
        assertEquals(-180.0, GeoMath.normalizeLongitude(180.0), 1e-9)
        assertEquals(0.0, GeoMath.normalizeLongitude(720.0), 1e-9)
    }

    @Test
    fun `coordinate validation rejects out of range and NaN`() {
        assertTrue(GeoMath.isValidCoordinate(89.9, -179.9))
        assertFalse(GeoMath.isValidCoordinate(90.1, 0.0))
        assertFalse(GeoMath.isValidCoordinate(0.0, 180.5))
        assertFalse(GeoMath.isValidCoordinate(Double.NaN, 0.0))
        assertFalse(GeoMath.isValidCoordinate(0.0, Double.POSITIVE_INFINITY))
    }
}
