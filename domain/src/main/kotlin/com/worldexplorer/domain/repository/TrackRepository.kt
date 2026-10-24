package com.worldexplorer.domain.repository

import com.worldexplorer.domain.geo.CellKey
import com.worldexplorer.domain.model.GpsQualityStats
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

data class RecordedPoint(val point: TrackPoint, val newCells: Int)

interface TrackRepository {
    fun observeTracks(): Flow<List<Track>>
    fun observeTrack(trackId: String): Flow<Track?>
    fun observeActiveTrack(): Flow<Track?>
    fun observePoints(trackId: String): Flow<List<TrackPoint>>

    suspend fun getActiveTrack(): Track?
    suspend fun getTrack(trackId: String): Track?
    suspend fun getLastPoint(trackId: String): TrackPoint?
    suspend fun getQualityStats(trackId: String): GpsQualityStats

    /** Creates a new ACTIVE track. Fails with AlreadyTracking if another track is active. */
    suspend fun startTrack(trackId: String, startedAtUtc: Long): AppResult<Track>

    /** Closes the track with [status]; no-op success if it is already closed. */
    suspend fun finishTrack(trackId: String, endedAtUtc: Long, status: TrackStatus): AppResult<Track>

    /**
     * Atomically stores the point, updates the track statistics and adds the explored [cells].
     * Fails with NotTracking if the track is no longer active (e.g. stopped concurrently).
     */
    suspend fun recordPoint(point: TrackPoint, distanceIncrementMeters: Double, cells: Collection<CellKey>, nowUtc: Long): AppResult<RecordedPoint>

    suspend fun addRejectedPoints(trackId: String, count: Int)

    /** Removes a track without points (e.g. the service failed to start right after creation). */
    suspend fun deleteTrackIfEmpty(trackId: String)
}
