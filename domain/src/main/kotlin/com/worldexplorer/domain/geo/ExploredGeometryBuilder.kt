package com.worldexplorer.domain.geo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Closed ring in degrees: [coordinates] is lon0, lat0, lon1, lat1, … (first vertex is not repeated). */
class GeoRing(val coordinates: DoubleArray) {
    val size: Int get() = coordinates.size / 2
    fun longitude(i: Int) = coordinates[2 * i]
    fun latitude(i: Int) = coordinates[2 * i + 1]
}

class GeoPolygon(val outer: GeoRing, val holes: List<GeoRing>)

/**
 * Renderable geometry of explored territory for a map window. It is derived data: computed from
 * explored cells on demand and never persisted.
 *
 * - [explored]: polygons covering explored territory (Explorer Map highlight).
 * - [fog]: polygons covering everything that is *not* explored (Fog of War mask). The first polygon
 *   is the whole world with explored areas cut out; the rest fill unexplored "lakes" inside explored areas.
 * - [boundaries]: every boundary ring, used for soft (blurred) fog edges and highlight outlines.
 */
class ExploredGeometry(
    val level: Int,
    val explored: List<GeoPolygon>,
    val fog: List<GeoPolygon>,
    val boundaries: List<GeoRing>,
) {
    val isEmpty: Boolean get() = explored.isEmpty()

    companion object {
        val WORLD_RING = GeoRing(
            doubleArrayOf(
                -180.0, -GeoMath.MAX_MERCATOR_LATITUDE,
                180.0, -GeoMath.MAX_MERCATOR_LATITUDE,
                180.0, GeoMath.MAX_MERCATOR_LATITUDE,
                -180.0, GeoMath.MAX_MERCATOR_LATITUDE,
            ),
        )

        fun empty(level: Int = 0) = ExploredGeometry(level, emptyList(), listOf(GeoPolygon(WORLD_RING, emptyList())), emptyList())
    }
}

/**
 * A rectangular, non-wrapping block of grid cells at [level] with the explored cells inside it.
 * Cell keys are packed by [packCell]; rows/columns are in level units (base index shr level).
 */
class CellWindow(
    val level: Int,
    val firstRow: Long,
    val lastRow: Long,
    val firstColumn: Long,
    val lastColumn: Long,
    val cells: LongArray,
) {
    val width: Int get() = (lastColumn - firstColumn + 1).toInt()
    val height: Int get() = (lastRow - firstRow + 1).toInt()
}

fun packCell(row: Long, column: Long): Long = (row shl 32) or (column and 0xffffffffL)
fun unpackRow(packed: Long): Long = packed ushr 32
fun unpackColumn(packed: Long): Long = packed and 0xffffffffL

/**
 * Converts a set of explored grid cells into smooth polygons.
 *
 * Algorithm (see ARCHITECTURE.md, "Fog of War"):
 * 1. Rasterize the window into a bitmap (1-cell empty padding).
 * 2. Trace the boundary of the union of explored cells into closed rectilinear rings: every cell
 *    side between an explored and an unexplored cell becomes a directed edge with the explored cell
 *    on its left; edges are chained preferring left turns, so diagonal neighbours stay separate
 *    rings and no ring self-intersects. Outer boundaries come out counter-clockwise, holes clockwise.
 * 3. Resolve nesting (which hole belongs to which outer ring, which island lies in which hole) in
 *    linear time from connected-component labels of explored and unexplored cells.
 * 4. Smooth every ring with Chaikin corner cutting so the staircase of cells becomes a soft curve.
 * 5. Convert grid coordinates to degrees.
 */
object ExploredGeometryBuilder {
    /** Maximum window side in cells; [chooseLevel] coarsens the grid to stay below it. */
    const val MAX_WINDOW_CELLS = 768
    private const val SMOOTHING_ITERATIONS = 3
    private const val MAX_CUT_CELLS = 1.0

    /** Smallest grid level whose cells keep a window of the given size within [MAX_WINDOW_CELLS]. */
    fun chooseLevel(bounds: GeoBounds): Int {
        val latSpanCells = (bounds.north - bounds.south) * ExplorationGrid.CELLS_PER_DEGREE
        val lonSpanCells = bounds.longitudeRanges().sumOf { it.endInclusive - it.start } * ExplorationGrid.CELLS_PER_DEGREE
        val span = max(latSpanCells, lonSpanCells)
        var level = 0
        while (level < ExplorationGrid.MAX_LEVEL && span / (1L shl level) > MAX_WINDOW_CELLS) level++
        return level
    }

    /** Splits bounds (possibly crossing the antimeridian) into non-wrapping cell windows at [level]. */
    fun windowsFor(bounds: GeoBounds, level: Int): List<WindowSpec> {
        val maxRow = ((ExplorationGrid.ROW_COUNT - 1).toLong() shr level)
        val maxCol = ((ExplorationGrid.COLUMN_COUNT - 1).toLong() shr level)
        val firstRow = (ExplorationGrid.rowOf(bounds.south).toLong() shr level).coerceIn(0, maxRow)
        val lastRow = (ExplorationGrid.rowOf(bounds.north).toLong() shr level).coerceIn(0, maxRow)
        return bounds.longitudeRanges().map { range ->
            val firstCol = (ExplorationGrid.columnOfUnwrapped(range.start) shr level).coerceIn(0, maxCol)
            val lastCol = (ExplorationGrid.columnOfUnwrapped(range.endInclusive) shr level).coerceIn(0, maxCol)
            WindowSpec(level, firstRow, lastRow, firstCol, lastCol)
        }
    }

    fun build(windows: List<CellWindow>): ExploredGeometry {
        val level = windows.firstOrNull()?.level ?: 0
        val explored = ArrayList<GeoPolygon>()
        val fogIslands = ArrayList<GeoPolygon>()
        val worldHoles = ArrayList<GeoRing>()
        val boundaries = ArrayList<GeoRing>()
        for (window in windows) {
            if (window.cells.isEmpty()) continue
            val trace = traceRings(window)
            if (trace.rings.isEmpty()) continue
            val structure = resolveNesting(trace)
            val geo = trace.rings.map { toGeoRing(smooth(it), window) }
            boundaries += geo
            structure.outerIndices.forEach { o ->
                explored += GeoPolygon(geo[o], structure.holesOf.getValue(o).map { geo[it] })
            }
            structure.holeIndices.forEach { h ->
                fogIslands += GeoPolygon(geo[h], structure.islandsOf.getValue(h).map { geo[it] })
            }
            structure.topLevelOuters.forEach { worldHoles += geo[it] }
        }
        val fog = ArrayList<GeoPolygon>(fogIslands.size + 1)
        fog += GeoPolygon(ExploredGeometry.WORLD_RING, worldHoles)
        fog += fogIslands
        return ExploredGeometry(level, explored, fog, boundaries)
    }

    // ---- tracing ----------------------------------------------------------------------------

    /**
     * Ring in padded-grid integer coordinates (xs/ys are cell corners). [filledLabel] is the explored
     * component on the ring's left, [emptyLabel] the unexplored component on its right.
     */
    internal class IntRing(val xs: IntArray, val ys: IntArray, val filledLabel: Int, val emptyLabel: Int) {
        val size get() = xs.size
        val signedArea2: Long by lazy {
            var s = 0L
            for (i in 0 until size) {
                val j = (i + 1) % size
                s += xs[i].toLong() * ys[j] - xs[j].toLong() * ys[i]
            }
            s
        }
        val isOuter: Boolean get() = signedArea2 > 0
    }

    internal class TraceResult(val rings: List<IntRing>, val backgroundLabel: Int)

    private const val EAST = 0
    private const val NORTH = 1
    private const val WEST = 2
    private const val SOUTH = 3
    private val DX = intArrayOf(1, 0, -1, 0)
    private val DY = intArrayOf(0, 1, 0, -1)

    internal fun traceRings(window: CellWindow): TraceResult {
        // Padded bitmap: cell (x, y) of the window is stored at (x + 1, y + 1).
        val w = window.width + 2
        val h = window.height + 2
        val filled = BooleanArray(w * h)
        for (packed in window.cells) {
            val x = (unpackColumn(packed) - window.firstColumn).toInt()
            val y = (unpackRow(packed) - window.firstRow).toInt()
            if (x < 0 || y < 0 || x >= window.width || y >= window.height) continue
            filled[(y + 1) * w + (x + 1)] = true
        }
        fun isFilled(x: Int, y: Int) = x in 0 until w && y in 0 until h && filled[y * w + x]
        val labels = labelComponents(filled, w, h)

        // Vertex grid is (w + 1) × (h + 1); each vertex keeps a bit mask of outgoing edge directions.
        val vw = w + 1
        val out = ByteArray(vw * (h + 1))
        fun addEdge(x: Int, y: Int, dir: Int) {
            val i = y * vw + x
            out[i] = (out[i].toInt() or (1 shl dir)).toByte()
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (!filled[y * w + x]) continue
            if (!isFilled(x, y - 1)) addEdge(x, y, EAST)
            if (!isFilled(x + 1, y)) addEdge(x + 1, y, NORTH)
            if (!isFilled(x, y + 1)) addEdge(x + 1, y + 1, WEST)
            if (!isFilled(x - 1, y)) addEdge(x, y + 1, SOUTH)
        }

        val rings = ArrayList<IntRing>()
        val xs = IntArrayList()
        val ys = IntArrayList()
        for (start in out.indices) {
            while (out[start].toInt() != 0) {
                val startDir = Integer.numberOfTrailingZeros(out[start].toInt())
                val sx = start % vw
                val sy = start / vw
                // Cells on the left (explored) and right (unexplored) of the first edge.
                val (fx, fy, ex, ey) = when (startDir) {
                    EAST -> intArrayOf(sx, sy, sx, sy - 1)
                    NORTH -> intArrayOf(sx - 1, sy, sx, sy)
                    WEST -> intArrayOf(sx - 1, sy - 1, sx - 1, sy)
                    else -> intArrayOf(sx, sy - 1, sx - 1, sy - 1)
                }
                xs.clear(); ys.clear()
                var vx = sx
                var vy = sy
                var dir = startDir
                out[start] = (out[start].toInt() and (1 shl startDir).inv()).toByte()
                var lastDir = -1
                while (true) {
                    if (dir != lastDir) {
                        xs.add(vx); ys.add(vy)
                        lastDir = dir
                    }
                    vx += DX[dir]; vy += DY[dir]
                    val vi = vy * vw + vx
                    val mask = out[vi].toInt()
                    var next = -1
                    var closes = false
                    // Prefer left turn, then straight, then right: explored cells stay 4-connected.
                    for (turn in intArrayOf(1, 0, 3)) {
                        val candidate = (dir + turn) % 4
                        if (vi == start && candidate == startDir) { closes = true; break }
                        if (mask and (1 shl candidate) != 0) { next = candidate; break }
                    }
                    if (closes || next == -1) break
                    out[vi] = (mask and (1 shl next).inv()).toByte()
                    dir = next
                }
                // The start vertex is redundant if it lies in the middle of a straight run.
                var from = 0
                if (xs.size > 2 && lastDir == startDir) from = 1
                if (xs.size - from >= 4) {
                    rings += IntRing(xs.toArray(from), ys.toArray(from), labels[fy * w + fx], labels[ey * w + ex])
                }
            }
        }
        return TraceResult(rings, backgroundLabel = labels[0])
    }

    /**
     * Connected-component labels: explored cells with 4-connectivity, unexplored with
     * 8-connectivity (the dual pairing that matches the left-turn tracing rule).
     */
    private fun labelComponents(filled: BooleanArray, w: Int, h: Int): IntArray {
        val labels = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        var next = 0
        for (seed in labels.indices) {
            if (labels[seed] != -1) continue
            val value = filled[seed]
            val label = next++
            var head = 0
            var tail = 0
            queue[tail++] = seed
            labels[seed] = label
            while (head < tail) {
                val c = queue[head++]
                val cx = c % w
                val cy = c / w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (value && dx != 0 && dy != 0) continue
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (labels[n] == -1 && filled[n] == value) {
                        labels[n] = label
                        queue[tail++] = n
                    }
                }
            }
        }
        return labels
    }

    private class IntArrayList {
        private var data = IntArray(64)
        var size = 0
            private set
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun clear() { size = 0 }
        fun toArray(from: Int): IntArray = data.copyOfRange(from, size)
    }

    // ---- nesting ----------------------------------------------------------------------------

    internal class Nesting(
        val outerIndices: List<Int>,
        val holeIndices: List<Int>,
        val holesOf: Map<Int, List<Int>>,
        val islandsOf: Map<Int, List<Int>>,
        val topLevelOuters: List<Int>,
    )

    /**
     * Linear-time nesting from component labels: a hole ring belongs to the outer ring of the same
     * explored component; an outer ring lies inside the lake whose hole ring borders the same
     * unexplored component, or in the open world if that component is the background.
     */
    internal fun resolveNesting(trace: TraceResult): Nesting {
        val rings = trace.rings
        val outers = rings.indices.filter { rings[it].isOuter }
        val holes = rings.indices.filter { !rings[it].isOuter }
        val outerByFilled = HashMap<Int, Int>(outers.size * 2)
        outers.forEach { outerByFilled[rings[it].filledLabel] = it }
        val holeByEmpty = HashMap<Int, Int>(holes.size * 2)
        holes.forEach { holeByEmpty[rings[it].emptyLabel] = it }

        val holesOf = outers.associateWith { mutableListOf<Int>() }
        val islandsOf = holes.associateWith { mutableListOf<Int>() }
        val topLevel = ArrayList<Int>()
        for (h in holes) outerByFilled[rings[h].filledLabel]?.let { holesOf.getValue(it) += h }
        for (o in outers) {
            val lake = rings[o].emptyLabel
            val hole = if (lake == trace.backgroundLabel) null else holeByEmpty[lake]
            if (hole != null) islandsOf.getValue(hole) += o else topLevel += o
        }
        return Nesting(outers, holes, holesOf, islandsOf, topLevel)
    }

    // ---- smoothing & projection ---------------------------------------------------------------

    /**
     * Chaikin corner cutting on a closed ring; returns interleaved x, y in grid units. The cut is
     * a quarter of the edge but at most [MAX_CUT_CELLS], so staircases become curves while long
     * straight borders keep their extent (explored area is not visibly eaten at corners).
     */
    internal fun smooth(ring: IntRing): DoubleArray {
        var pts = DoubleArray(ring.size * 2) { if (it % 2 == 0) ring.xs[it / 2].toDouble() else ring.ys[it / 2].toDouble() }
        repeat(SMOOTHING_ITERATIONS) {
            val n = pts.size / 2
            val next = DoubleArray(n * 4)
            for (i in 0 until n) {
                val j = (i + 1) % n
                val x0 = pts[2 * i]; val y0 = pts[2 * i + 1]
                val x1 = pts[2 * j]; val y1 = pts[2 * j + 1]
                val length = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
                val t = if (length == 0.0) 0.25 else min(0.25, MAX_CUT_CELLS / length)
                next[4 * i] = x0 + (x1 - x0) * t
                next[4 * i + 1] = y0 + (y1 - y0) * t
                next[4 * i + 2] = x1 - (x1 - x0) * t
                next[4 * i + 3] = y1 - (y1 - y0) * t
            }
            pts = next
        }
        return pts
    }

    private fun toGeoRing(gridPoints: DoubleArray, window: CellWindow): GeoRing {
        val cellDeg = ExplorationGrid.CELL_DEGREES * (1L shl window.level)
        val out = DoubleArray(gridPoints.size)
        for (i in 0 until gridPoints.size / 2) {
            // Padded coordinate 1 corresponds to the window's first row/column.
            val x = window.firstColumn + gridPoints[2 * i] - 1.0
            val y = window.firstRow + gridPoints[2 * i + 1] - 1.0
            out[2 * i] = min(180.0, max(-180.0, x * cellDeg - 180.0))
            out[2 * i + 1] = min(GeoMath.MAX_MERCATOR_LATITUDE, max(-GeoMath.MAX_MERCATOR_LATITUDE, y * cellDeg - 90.0))
        }
        return GeoRing(out)
    }
}

/** Rows/columns (inclusive, in level units) of a window to load from storage. */
data class WindowSpec(
    val level: Int,
    val firstRow: Long,
    val lastRow: Long,
    val firstColumn: Long,
    val lastColumn: Long,
)
