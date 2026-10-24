package com.worldexplorer.domain.geo

import com.worldexplorer.domain.fakes.TestGeo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renderers (earcut in MapLibre) may drop a whole polygon when a ring self-intersects, which for the
 * fog would expose the entire map. Every produced ring must therefore be simple.
 */
class RingValidityTest {
    private fun segmentsIntersect(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) = (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun selfIntersections(r: GeoRing): Int {
        var count = 0
        val n = r.size
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            for (j in i + 2 until n) {
                val j2 = (j + 1) % n
                if (j2 == i) continue
                if (segmentsIntersect(r.longitude(i), r.latitude(i), r.longitude(i2), r.latitude(i2), r.longitude(j), r.latitude(j), r.longitude(j2), r.latitude(j2))) count++
            }
        }
        return count
    }

    private fun window(cells: Collection<CellKey>): CellWindow {
        val rows = cells.map { it.row.toLong() }
        val cols = cells.map { it.column.toLong() }
        return CellWindow(0, rows.min() - 2, rows.max() + 2, cols.min() - 2, cols.max() + 2, cells.map { packCell(it.row.toLong(), it.column.toLong()) }.toLongArray())
    }

    private fun crossings(a: GeoRing, b: GeoRing): Int {
        var count = 0
        for (i in 0 until a.size) {
            val i2 = (i + 1) % a.size
            for (j in 0 until b.size) {
                val j2 = (j + 1) % b.size
                if (segmentsIntersect(a.longitude(i), a.latitude(i), a.longitude(i2), a.latitude(i2), b.longitude(j), b.latitude(j), b.longitude(j2), b.latitude(j2))) count++
            }
        }
        return count
    }

    private fun assertSimple(g: ExploredGeometry) {
        g.boundaries.forEach { r -> val n = selfIntersections(r); assertTrue("ring of ${r.size} vertices has $n self-intersections", n == 0) }
        for (i in g.boundaries.indices) for (j in i + 1 until g.boundaries.size) {
            val n = crossings(g.boundaries[i], g.boundaries[j])
            assertTrue("rings $i and $j cross $n times", n == 0)
        }
    }

    @Test
    fun `walk with a turn produces simple rings for every radius`() {
        for (radius in listOf(10.0, 20.0, 30.0, 50.0, 100.0)) {
            val cells = LinkedHashSet<CellKey>()
            var lat = TestGeo.BASE_LAT
            var lon = TestGeo.BASE_LON
            repeat(40) { cells += ExplorationGrid.cellsWithinRadius(lat, lon, radius); lat = TestGeo.north(lat, 6.7) }
            repeat(40) { cells += ExplorationGrid.cellsWithinRadius(lat, lon, radius); lon = TestGeo.east(lat, lon, 6.7) }
            assertSimple(ExploredGeometryBuilder.build(listOf(window(cells))))
        }
    }

    @Test
    fun `random territory produces simple rings`() {
        val rnd = java.util.Random(1)
        val cells = HashSet<CellKey>()
        repeat(300) { cells += ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT + rnd.nextDouble() * 0.003, TestGeo.BASE_LON + rnd.nextDouble() * 0.004, 10.0 + rnd.nextInt(40)) }
        assertSimple(ExploredGeometryBuilder.build(listOf(window(cells))))
    }
}
