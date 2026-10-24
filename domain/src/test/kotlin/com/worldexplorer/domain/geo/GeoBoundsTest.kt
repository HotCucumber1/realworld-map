package com.worldexplorer.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoBoundsTest {
    @Test
    fun `normal bounds give one range`() {
        assertEquals(listOf(10.0..20.0), GeoBounds(0.0, 10.0, 5.0, 20.0).longitudeRanges())
    }

    @Test
    fun `bounds crossing the antimeridian split in two`() {
        val ranges = GeoBounds(0.0, 170.0, 5.0, -170.0).longitudeRanges()
        assertEquals(listOf(170.0..180.0, -180.0..-170.0), ranges)
    }

    @Test
    fun `bounds reported beyond 180 by a wrapped map are normalized`() {
        val ranges = GeoBounds(0.0, 175.0, 5.0, 185.0).longitudeRanges()
        assertEquals(2, ranges.size)
        assertEquals(175.0, ranges[0].start, 1e-9)
        assertEquals(-175.0, ranges[1].endInclusive, 1e-9)
    }

    @Test
    fun `whole world zoom yields single full range`() {
        assertEquals(listOf(-180.0..180.0), GeoBounds(-80.0, -200.0, 80.0, 250.0).longitudeRanges())
    }

    @Test
    fun `expansion clamps latitude`() {
        val e = GeoBounds(80.0, 0.0, 89.0, 1.0).expandedBy(1.0)
        assertEquals(90.0, e.north, 1e-9)
        assertEquals(71.0, e.south, 1e-9)
    }
}
