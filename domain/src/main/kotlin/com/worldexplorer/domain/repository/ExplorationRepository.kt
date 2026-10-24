package com.worldexplorer.domain.repository

import com.worldexplorer.domain.geo.WindowSpec
import com.worldexplorer.domain.model.ExplorationStats
import kotlinx.coroutines.flow.Flow

interface ExplorationRepository {
    fun observeStats(): Flow<ExplorationStats>

    /** Emits a new value whenever explored territory changes (cheap change signal). */
    fun observeVersion(): Flow<Long>

    /** Explored cells of the window, packed with [com.worldexplorer.domain.geo.packCell], at the window's level. */
    suspend fun loadWindow(spec: WindowSpec): LongArray
}
