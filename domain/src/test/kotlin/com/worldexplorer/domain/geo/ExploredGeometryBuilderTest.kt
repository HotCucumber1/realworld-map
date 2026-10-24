package com.worldexplorer.domain.geo

import com.worldexplorer.domain.fakes.TestGeo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExploredGeometryBuilderTest {
    private val originRow = 2_000_000L
    private val originCol = 3_000_000L

    /** Window 0..size-1 in both axes; [pattern] rows are listed north-to-south, '#' = explored. */
    private fun window(vararg pattern: String): CellWindow {
        val h = pattern.size
        val w = pattern.maxOf { it.length }
        val cells = ArrayList<Long>()
        pattern.forEachIndexed { i, line ->
            val y = h - 1 - i
            line.forEachIndexed { x, c -> if (c == '#') cells += packCell(originRow + y, originCol + x) }
        }
        return CellWindow(0, originRow, originRow + h - 1, originCol, originCol + w - 1, cells.toLongArray())
    }

    private fun ringArea(r: GeoRing): Double {
        var s = 0.0
        for (i in 0 until r.size) {
            val j = (i + 1) % r.size
            s += r.longitude(i) * r.latitude(j) - r.longitude(j) * r.latitude(i)
        }
        return s / 2
    }

    @Test
    fun `no cells gives a world covered by fog`() {
        val g = ExploredGeometryBuilder.build(listOf(window("...", "...")))
        assertTrue(g.isEmpty)
        assertEquals(1, g.fog.size)
        assertTrue(g.fog[0].holes.isEmpty())
    }

    @Test
    fun `single cell becomes one smooth explored polygon and one hole in the fog`() {
        val g = ExploredGeometryBuilder.build(listOf(window("...", ".#.", "...")))
        assertEquals(1, g.explored.size)
        assertEquals(1, g.fog[0].holes.size)
        // Chaikin x3 turns the 4 corners into 32 vertices.
        assertEquals(32, g.explored[0].outer.size)
        assertTrue(ringArea(g.explored[0].outer) > 0)
    }

    @Test
    fun `adjacent cells merge into one ring`() {
        val g = ExploredGeometryBuilder.build(listOf(window("####", "##..", "#...")))
        assertEquals(1, g.explored.size)
        assertEquals(1, g.boundaries.size)
    }

    @Test
    fun `diagonal neighbours stay separate rings without self intersection`() {
        val g = ExploredGeometryBuilder.build(listOf(window("#.", ".#")))
        assertEquals(2, g.explored.size)
        assertEquals(2, g.fog[0].holes.size)
    }

    @Test
    fun `explored ring around an unexplored block has a hole that is fogged`() {
        val g = ExploredGeometryBuilder.build(listOf(window("#####", "#...#", "#...#", "#####")))
        assertEquals(1, g.explored.size)
        assertEquals(1, g.explored[0].holes.size)
        // World fog with the ring cut out + the lake inside the ring is fog again.
        assertEquals(2, g.fog.size)
        assertEquals(1, g.fog[0].holes.size)
        assertTrue(g.fog[1].holes.isEmpty())
    }

    @Test
    fun `island inside a lake inside explored land is explored again`() {
        val g = ExploredGeometryBuilder.build(
            listOf(window("#######", "#.....#", "#..#..#", "#.....#", "#######")),
        )
        assertEquals(2, g.explored.size)
        val lake = g.fog.drop(1).single()
        assertEquals("island cut out of the lake fog", 1, lake.holes.size)
        assertEquals("only the outer ring is cut from the world", 1, g.fog[0].holes.size)
    }

    @Test
    fun `coordinates are converted to degrees inside the window`() {
        val g = ExploredGeometryBuilder.build(listOf(window("#")))
        val ring = g.explored[0].outer
        val south = ExplorationGrid.rowSouthLatitude(originRow)
        val west = ExplorationGrid.columnWestLongitude(originCol)
        for (i in 0 until ring.size) {
            val eps = 1e-9
            assertTrue(ring.latitude(i) in (south - eps)..(south + ExplorationGrid.CELL_DEGREES + eps))
            assertTrue(ring.longitude(i) in (west - eps)..(west + ExplorationGrid.CELL_DEGREES + eps))
        }
    }

    @Test
    fun `walking along a street produces one continuous strip`() {
        val exploration = LinkedHashSet<CellKey>()
        var lon = TestGeo.BASE_LON
        repeat(60) {
            exploration += ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, lon, 20.0)
            lon = TestGeo.east(TestGeo.BASE_LAT, lon, 8.0) // a point every 8 m
        }
        val rows = exploration.map { it.row.toLong() }
        val cols = exploration.map { it.column.toLong() }
        val w = CellWindow(0, rows.min() - 2, rows.max() + 2, cols.min() - 2, cols.max() + 2, exploration.map { packCell(it.row.toLong(), it.column.toLong()) }.toLongArray())
        val g = ExploredGeometryBuilder.build(listOf(w))
        assertEquals("one strip, not separate circles", 1, g.explored.size)
        assertTrue(g.explored[0].holes.isEmpty())
    }

    @Test
    fun `smoothed area stays close to the cell area`() {
        val g = ExploredGeometryBuilder.build(listOf(window("##########", "##########", "##########", "##########")))
        val cellDeg2 = ExplorationGrid.CELL_DEGREES * ExplorationGrid.CELL_DEGREES
        val area = abs(ringArea(g.explored[0].outer)) / cellDeg2
        // Only the four corners are rounded (≈0.75 cell each); uncapped Chaikin would lose ≈7 cells here.
        assertEquals(40.0, area, 3.5)
    }

    @Test
    fun `level grows as the viewport grows`() {
        val street = GeoBounds(TestGeo.BASE_LAT, TestGeo.BASE_LON, TestGeo.BASE_LAT + 0.005, TestGeo.BASE_LON + 0.005)
        val city = GeoBounds(TestGeo.BASE_LAT, TestGeo.BASE_LON, TestGeo.BASE_LAT + 0.3, TestGeo.BASE_LON + 0.3)
        val world = GeoBounds(-85.0, -180.0, 85.0, 180.0)
        val l0 = ExploredGeometryBuilder.chooseLevel(street)
        val l1 = ExploredGeometryBuilder.chooseLevel(city)
        val l2 = ExploredGeometryBuilder.chooseLevel(world)
        assertEquals(0, l0)
        assertTrue(l1 in 1..l2)
        val worldCols = ExplorationGrid.COLUMN_COUNT.toLong() shr l2
        assertTrue(worldCols <= ExploredGeometryBuilder.MAX_WINDOW_CELLS)
    }

    @Test
    fun `viewport crossing the antimeridian is split into two windows`() {
        val windows = ExploredGeometryBuilder.windowsFor(GeoBounds(0.0, 179.99, 0.01, -179.99), 0)
        assertEquals(2, windows.size)
        assertEquals(ExplorationGrid.COLUMN_COUNT - 1L, windows[0].lastColumn)
        assertEquals(0L, windows[1].firstColumn)
    }

    @Test
    fun `explored territory on both sides of the antimeridian is cut from the fog`() {
        val cells = ExplorationGrid.cellsWithinRadius(0.0, 179.99995, 20.0)
        val windows = ExploredGeometryBuilder.windowsFor(GeoBounds(-0.001, 179.998, 0.001, -179.998), 0).map { spec ->
            CellWindow(0, spec.firstRow, spec.lastRow, spec.firstColumn, spec.lastColumn,
                cells.filter { it.row.toLong() in spec.firstRow..spec.lastRow && it.column.toLong() in spec.firstColumn..spec.lastColumn }
                    .map { packCell(it.row.toLong(), it.column.toLong()) }.toLongArray())
        }
        val g = ExploredGeometryBuilder.build(windows)
        assertEquals(2, g.explored.size)
        assertEquals(2, g.fog[0].holes.size)
        assertTrue(g.explored.any { p -> (0 until p.outer.size).all { p.outer.longitude(it) > 179.0 } })
        assertTrue(g.explored.any { p -> (0 until p.outer.size).all { p.outer.longitude(it) < -179.0 } })
    }

    @Test
    fun `large window is processed quickly`() {
        val cells = ArrayList<Long>()
        val size = ExploredGeometryBuilder.MAX_WINDOW_CELLS
        val rnd = java.util.Random(42)
        for (y in 0 until size) for (x in 0 until size) if (rnd.nextInt(100) < 30) cells += packCell(originRow + y, originCol + x)
        val w = CellWindow(0, originRow, originRow + size - 1, originCol, originCol + size - 1, cells.toLongArray())
        val start = System.nanoTime()
        val g = ExploredGeometryBuilder.build(listOf(w))
        val ms = (System.nanoTime() - start) / 1e6
        assertTrue(g.explored.isNotEmpty())
        assertTrue("took $ms ms", ms < 5_000)
    }
}
