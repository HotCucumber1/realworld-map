package com.worldexplorer.data.database

import com.worldexplorer.domain.geo.CellKey
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.model.GeoPoint
import javax.inject.Inject

/**
 * Write side of explored territory. Must be called inside a database transaction: it inserts base
 * cells, the derived coarse summaries and the aggregate statistics together so they never diverge.
 */
class ExplorationStore @Inject constructor(private val db: WorldExplorerDatabase) {
    private val cells get() = db.cellDao()
    private val stats get() = db.statsDao()

    /** Adds cells (ignoring already explored ones) and returns how many were new. */
    suspend fun addCells(keys: Collection<CellKey>, visitedAt: Long, syncState: Int = SyncState.PENDING): Int =
        addEntities(keys.map { ExploredCellEntity(it.row, it.column, visitedAt, syncState) })

    /**
     * @param keepEarliestVisit for data merged from elsewhere (import, sync): an already explored
     * cell takes the earlier first-visit time, so every device converges to the same value.
     * Live recording skips it (time only moves forward there).
     */
    suspend fun addEntities(entities: List<ExploredCellEntity>, keepEarliestVisit: Boolean = false): Int {
        if (entities.isEmpty()) return 0
        val ids = cells.insertIgnore(entities)
        var added = 0
        var area = 0.0
        val summaries = HashSet<ExploredCellSummaryEntity>()
        ids.forEachIndexed { i, id ->
            if (id == -1L) {
                if (keepEarliestVisit) entities[i].let { cells.lowerFirstVisit(it.row, it.column, it.firstVisitedAt) }
                return@forEachIndexed
            }
            val e = entities[i]
            added++
            area += ExplorationGrid.cellAreaSquareMeters(e.row)
            for (level in ExplorationGrid.SUMMARY_LEVELS) summaries += ExploredCellSummaryEntity(level, e.row shr level, e.column shr level)
        }
        if (added == 0) return 0
        cells.insertSummariesIgnore(summaries.toList())
        val current = stats.get() ?: ExplorationStatsEntity(totalAreaSquareMeters = 0.0, cellCount = 0, version = 0)
        stats.upsert(current.copy(totalAreaSquareMeters = current.totalAreaSquareMeters + area, cellCount = current.cellCount + added, version = current.version + 1))
        return added
    }

    /** Rebuilds statistics from the cell table (after bulk replace). */
    suspend fun recomputeStats() {
        val rows = cells.countsByRow()
        val area = rows.sumOf { ExplorationGrid.cellAreaSquareMeters(it.row) * it.count }
        val count = rows.sumOf { it.count }
        val version = (stats.get()?.version ?: 0) + 1
        stats.upsert(ExplorationStatsEntity(totalAreaSquareMeters = area, cellCount = count, version = version))
    }

    suspend fun clear() {
        cells.deleteAll()
        cells.deleteAllSummaries()
        recomputeStats()
    }
}

/** Track statistics derived from its points (used after import/sync merged point sets). */
object TrackStats {
    data class Values(val distanceMeters: Double, val pointCount: Int, val lastPointAt: Long?)

    fun of(points: List<LocationPointEntity>): Values {
        val sorted = points.sortedBy { it.timestampUtc }
        val distance = GeoMath.pathLengthMeters(sorted.map { GeoPoint(it.latitude, it.longitude) })
        return Values(distance, sorted.size, sorted.lastOrNull()?.timestampUtc)
    }
}
