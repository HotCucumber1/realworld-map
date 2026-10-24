package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.model.LocationSample
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.repository.RecordedPoint
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.tracking.Clock
import com.worldexplorer.domain.tracking.IdGenerator
import javax.inject.Inject

/**
 * Persists one already-filtered measurement: the point, the track statistics and the explored
 * cells around it, in one transaction (see [TrackRepository.recordPoint]).
 */
class RecordLocationUseCase @Inject constructor(
    private val tracks: TrackRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(
        trackId: String,
        sample: LocationSample,
        distanceFromPreviousMeters: Double,
        exploreRadiusMeters: Int,
    ): AppResult<RecordedPoint> {
        val point = TrackPoint(
            id = ids.newId(),
            trackId = trackId,
            latitude = sample.latitude,
            longitude = sample.longitude,
            timestampUtc = sample.timestampUtcMillis,
            accuracyMeters = sample.accuracyMeters ?: 0f,
            altitudeMeters = sample.altitudeMeters,
            speedMetersPerSecond = sample.speedMetersPerSecond,
            bearingDegrees = sample.bearingDegrees,
            source = sample.source,
            exploreRadiusMeters = exploreRadiusMeters,
        )
        val cells = ExplorationGrid.cellsWithinRadius(sample.latitude, sample.longitude, exploreRadiusMeters.toDouble())
        return tracks.recordPoint(point, distanceFromPreviousMeters, cells, clock.nowUtcMillis())
    }
}
