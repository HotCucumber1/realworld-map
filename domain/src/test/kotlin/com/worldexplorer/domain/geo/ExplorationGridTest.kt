package com.worldexplorer.domain.geo

import com.worldexplorer.domain.fakes.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ExplorationGridTest {
    @Test
    fun `cell covering a point contains that point`() {
        val cell = ExplorationGrid.cellOf(TestGeo.BASE_LAT, TestGeo.BASE_LON)
        val south = ExplorationGrid.rowSouthLatitude(cell.row.toLong())
        val west = ExplorationGrid.columnWestLongitude(cell.column.toLong())
        assertTrue(TestGeo.BASE_LAT >= south && TestGeo.BASE_LAT < south + ExplorationGrid.CELL_DEGREES)
        assertTrue(TestGeo.BASE_LON >= west && TestGeo.BASE_LON < west + ExplorationGrid.CELL_DEGREES)
    }

    @Test
    fun `circle of 20 m covers an area close to pi r squared`() {
        val cells = ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0)
        val area = cells.sumOf { ExplorationGrid.cellAreaSquareMeters(it.row) }
        assertEquals(PI * 20 * 20, area, PI * 400 * 0.1)
    }

    @Test
    fun `every returned cell centre is inside the radius`() {
        for (radius in listOf(10.0, 20.0, 30.0, 50.0, 100.0)) {
            val cells = ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, radius)
            cells.forEach {
                val d = GeoMath.haversineMeters(TestGeo.BASE_LAT, TestGeo.BASE_LON, ExplorationGrid.rowCenterLatitude(it.row), ExplorationGrid.columnCenterLongitude(it.column))
                assertTrue("cell $it at $d m for radius $radius", d <= radius + 0.01 || it == ExplorationGrid.cellOf(TestGeo.BASE_LAT, TestGeo.BASE_LON))
            }
            assertEquals("no duplicates", cells.size, cells.toSet().size)
        }
    }

    @Test
    fun `larger radius covers a superset`() {
        val small = ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 10.0).toSet()
        val large = ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 50.0).toSet()
        assertTrue(large.containsAll(small))
        assertTrue(large.size > small.size * 20)
    }

    @Test
    fun `circle at the antimeridian wraps columns to both sides`() {
        val cells = ExplorationGrid.cellsWithinRadius(0.0, 179.99995, 20.0)
        val columns = cells.map { it.column }.toSet()
        assertTrue(columns.any { it >= ExplorationGrid.COLUMN_COUNT - 5 })
        assertTrue(columns.any { it < 5 })
        assertTrue(columns.all { it in 0 until ExplorationGrid.COLUMN_COUNT })
    }

    @Test
    fun `columns near 180 and -180 are neighbours`() {
        assertEquals(ExplorationGrid.COLUMN_COUNT - 1, ExplorationGrid.columnOf(179.99999))
        assertEquals(0, ExplorationGrid.columnOf(-180.0))
        assertEquals(0, ExplorationGrid.columnOf(180.0))
    }

    @Test
    fun `polar point stays within valid rows`() {
        val cells = ExplorationGrid.cellsWithinRadius(89.99999, 0.0, 30.0)
        assertTrue(cells.all { it.row in 0 until ExplorationGrid.ROW_COUNT })
        assertEquals(listOf(ExplorationGrid.cellOf(89.99999, 0.0)), cells)
        // Just inside the Mercator limit circles still work and stay bounded.
        val nearLimit = ExplorationGrid.cellsWithinRadius(85.0, 0.0, 100.0)
        assertTrue(nearLimit.size in 100..20_000)
    }

    @Test
    fun `revisiting the same place yields the same cells`() {
        val a = ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0).toSet()
        val b = ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0).toSet()
        assertEquals(a, b)
    }

    @Test
    fun `parent cell at level shifts both indices`() {
        assertEquals(CellKey(3, 7), CellKey(3 * 16 + 5, 7 * 16 + 15).parent(4))
    }
}
