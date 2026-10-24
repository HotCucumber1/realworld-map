package com.worldexplorer.domain.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Global geographic grid used to represent explored territory.
 *
 * The grid is an equirectangular lattice with a fixed step of [CELL_DEGREES] (1/20000°) on both axes:
 * a cell is ≈5.56 m tall everywhere and ≈5.56·cos(lat) m wide (≈3.2 m at 55°N). It is defined in
 * degrees, never in screen pixels, so it is independent of zoom and identical on every device —
 * which makes explored territory a deterministic, mergeable set of integer cell keys.
 *
 * Row 0 starts at latitude −90°, column 0 at longitude −180°. Columns wrap around the antimeridian.
 */
object ExplorationGrid {
    const val CELLS_PER_DEGREE = 20_000
    const val CELL_DEGREES = 1.0 / CELLS_PER_DEGREE
    const val ROW_COUNT = 180 * CELLS_PER_DEGREE
    const val COLUMN_COUNT = 360 * CELLS_PER_DEGREE

    /** Pre-aggregated coarse levels (cell = 2^level base cells per side) used to render at low zoom. */
    val SUMMARY_LEVELS = intArrayOf(4, 8)
    const val MAX_LEVEL = 14

    private const val CELL_HEIGHT_METERS = GeoMath.METERS_PER_DEGREE_LATITUDE * CELL_DEGREES

    fun rowOf(latitude: Double): Int =
        floor((latitude + 90.0) * CELLS_PER_DEGREE).toInt().coerceIn(0, ROW_COUNT - 1)

    fun columnOf(longitude: Double): Int =
        Math.floorMod(floor((GeoMath.normalizeLongitude(longitude) + 180.0) * CELLS_PER_DEGREE).toLong(), COLUMN_COUNT.toLong()).toInt()

    /** Column for a longitude in [-180, 180] without wrapping 180° back to column 0 (for range ends). */
    fun columnOfUnwrapped(longitude: Double): Long =
        floor((longitude + 180.0) * CELLS_PER_DEGREE).toLong().coerceIn(0L, COLUMN_COUNT - 1L)

    fun cellOf(latitude: Double, longitude: Double): CellKey = CellKey(rowOf(latitude), columnOf(longitude))

    fun rowCenterLatitude(row: Int): Double = (row + 0.5) * CELL_DEGREES - 90.0
    fun columnCenterLongitude(column: Int): Double = (column + 0.5) * CELL_DEGREES - 180.0

    fun rowSouthLatitude(row: Long, level: Int = 0): Double = (row shl level) * CELL_DEGREES - 90.0
    fun columnWestLongitude(column: Long, level: Int = 0): Double = (column shl level) * CELL_DEGREES - 180.0

    /** Ground area of one base cell in the given row, m². */
    fun cellAreaSquareMeters(row: Int): Double =
        CELL_HEIGHT_METERS * CELL_HEIGHT_METERS * cos(rowCenterLatitude(row) * PI / 180.0)

    /**
     * All base cells whose centre lies within [radiusMeters] of the point. The cell containing the
     * point is always included. Handles the antimeridian (columns wrap); rows beyond ±85.05° (not
     * displayable in Web Mercator) contribute only the point's own cell.
     */
    fun cellsWithinRadius(latitude: Double, longitude: Double, radiusMeters: Double): List<CellKey> {
        require(radiusMeters > 0) { "radius must be positive" }
        val result = ArrayList<CellKey>()
        val centerRow = rowOf(latitude)
        val rowSpan = ceil(radiusMeters / CELL_HEIGHT_METERS).toInt() + 1
        val lon = GeoMath.normalizeLongitude(longitude)
        for (row in (centerRow - rowSpan)..(centerRow + rowSpan)) {
            if (row < 0 || row >= ROW_COUNT) continue
            val rowLat = rowCenterLatitude(row)
            // Beyond the Web-Mercator limit the map cannot show territory and a circle would span
            // every longitude; only the point's own cell is kept there.
            if (abs(rowLat) > GeoMath.MAX_MERCATOR_LATITUDE) continue
            val dy = (rowLat - latitude) * GeoMath.METERS_PER_DEGREE_LATITUDE
            val remaining = radiusMeters * radiusMeters - dy * dy
            if (remaining < 0) continue
            val dxMeters = sqrt(remaining)
            val metersPerDegLon = GeoMath.metersPerDegreeLongitude(rowLat).coerceAtLeast(1e-3)
            val dxDegrees = (dxMeters / metersPerDegLon).coerceAtMost(180.0)
            // Column c has its centre at (c + 0.5)/CPD − 180; include it if |centre − lon| ≤ dxDegrees.
            val first = ceil((lon - dxDegrees + 180.0) * CELLS_PER_DEGREE - 0.5).toLong()
            val last = floor((lon + dxDegrees + 180.0) * CELLS_PER_DEGREE - 0.5).toLong()
            val count = (last - first + 1).coerceAtMost(COLUMN_COUNT.toLong())
            for (i in 0 until count) {
                val column = Math.floorMod(first + i, COLUMN_COUNT.toLong()).toInt()
                result += CellKey(row, column)
            }
        }
        val own = cellOf(latitude, longitude)
        if (own !in result) result += own
        return result
    }
}

/** Integer address of a base grid cell. */
data class CellKey(val row: Int, val column: Int) {
    fun parent(level: Int): CellKey = CellKey(row shr level, column shr level)
}
