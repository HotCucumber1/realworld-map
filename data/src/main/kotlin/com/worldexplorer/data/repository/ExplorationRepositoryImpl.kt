package com.worldexplorer.data.repository

import com.worldexplorer.data.database.CellRow
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.WindowSpec
import com.worldexplorer.domain.geo.packCell
import com.worldexplorer.domain.model.ExplorationStats
import com.worldexplorer.domain.repository.ExplorationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExplorationRepositoryImpl @Inject constructor(private val db: WorldExplorerDatabase) : ExplorationRepository {
    override fun observeStats(): Flow<ExplorationStats> = db.statsDao().observe().map {
        ExplorationStats(it?.totalAreaSquareMeters ?: 0.0, it?.cellCount ?: 0)
    }

    override fun observeVersion(): Flow<Long> = db.statsDao().observe().map { it?.version ?: 0L }.distinctUntilChanged()

    /**
     * Reads the window from the finest table that can answer it: base cells below level 4, the
     * level-4 summary up to level 8, the level-8 summary above. Coarse cells are "explored" if any
     * base cell inside them is.
     */
    override suspend fun loadWindow(spec: WindowSpec): LongArray {
        val source = ExplorationGrid.SUMMARY_LEVELS.filter { it <= spec.level }.maxOrNull() ?: 0
        val shift = spec.level - source
        val rows: List<CellRow> = run {
            val k = spec.level - source
            val firstRow = spec.firstRow shl k
            val lastRow = ((spec.lastRow + 1) shl k) - 1
            val firstCol = spec.firstColumn shl k
            val lastCol = ((spec.lastColumn + 1) shl k) - 1
            if (source == 0) {
                db.cellDao().baseWindow(shift, firstRow, lastRow, firstCol, lastCol)
            } else {
                db.cellDao().summaryWindow(source, shift, firstRow, lastRow, firstCol, lastCol)
            }
        }
        return LongArray(rows.size) { packCell(rows[it].row, rows[it].column) }
    }
}
