package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.model.CurrentLocation
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackDetails
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.repository.TrackRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

class ObserveTrackHistoryUseCase @Inject constructor(private val tracks: TrackRepository) {
    operator fun invoke(): Flow<List<Track>> = tracks.observeTracks()
}

class ObserveTrackDetailsUseCase @Inject constructor(private val tracks: TrackRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(trackId: String): Flow<TrackDetails?> =
        combine(tracks.observeTrack(trackId), tracks.observePoints(trackId)) { track, points -> track to points }
            .mapLatest { (track, points) -> track?.let { TrackDetails(it, points, tracks.getQualityStats(trackId)) } }
}

data class ActiveRoute(val track: Track, val points: List<TrackPoint>)

/** Points of the active track (to draw the route being recorded); null when not recording. */
class ObserveActiveRouteUseCase @Inject constructor(private val tracks: TrackRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<ActiveRoute?> = tracks.observeActiveTrack().flatMapLatest { track ->
        if (track == null) flowOf(null) else tracks.observePoints(track.id).map { ActiveRoute(track, it) }
    }
}

/**
 * Current position for the map marker. While collected it keeps a display-rate location
 * subscription open (the recording service has its own) and emits the last known fix, so the UI can
 * show its age. The flow fails with SecurityException if location permission is missing.
 */
class ObserveCurrentLocationUseCase @Inject constructor(private val location: LocationRepository) {
    operator fun invoke(): Flow<CurrentLocation?> = channelFlow {
        launch { location.locationUpdates(DISPLAY_INTERVAL_MILLIS).collect { } }
        location.lastLocation.collect { send(it) }
    }

    companion object {
        const val DISPLAY_INTERVAL_MILLIS = 3_000L
    }
}
