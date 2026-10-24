package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.geo.CellWindow
import com.worldexplorer.domain.geo.ExploredGeometry
import com.worldexplorer.domain.geo.ExploredGeometryBuilder
import com.worldexplorer.domain.geo.GeoBounds
import com.worldexplorer.domain.model.ExplorationStats
import com.worldexplorer.domain.repository.ExplorationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Builds renderable explored-territory geometry for a map region. The region is padded so small
 * pans do not need a recomputation, and the grid level is chosen from the region size so the work
 * per call is bounded regardless of zoom.
 */
class CalculateExploredAreaUseCase @Inject constructor(
    private val exploration: ExplorationRepository,
    private val dispatchers: AppDispatchers,
) {
    suspend operator fun invoke(visible: GeoBounds): ExploredGeometry {
        val padded = visible.expandedBy(PADDING_FACTOR)
        val level = ExploredGeometryBuilder.chooseLevel(padded)
        val windows = ExploredGeometryBuilder.windowsFor(padded, level).map { spec ->
            CellWindow(spec.level, spec.firstRow, spec.lastRow, spec.firstColumn, spec.lastColumn, exploration.loadWindow(spec))
        }
        return withContext(dispatchers.default) { ExploredGeometryBuilder.build(windows) }
    }

    companion object {
        const val PADDING_FACTOR = 0.5
    }
}

/**
 * Recomputes geometry when the viewport settles or explored territory changes. Only the visible
 * window is processed — never the whole history on every frame.
 */
class ObserveExploredAreaUseCase @Inject constructor(
    private val exploration: ExplorationRepository,
    private val calculate: CalculateExploredAreaUseCase,
) {
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    operator fun invoke(viewport: Flow<GeoBounds>): Flow<ExploredGeometry> =
        combine(viewport.distinctUntilChanged().debounce(VIEWPORT_DEBOUNCE_MILLIS), exploration.observeVersion()) { bounds, _ -> bounds }
            .mapLatest { calculate(it) }

    companion object {
        const val VIEWPORT_DEBOUNCE_MILLIS = 120L
    }
}

class ObserveExplorationStatsUseCase @Inject constructor(private val exploration: ExplorationRepository) {
    operator fun invoke(): Flow<ExplorationStats> = exploration.observeStats().distinctUntilChanged()
}
