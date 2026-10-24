package com.worldexplorer.data.repository

import androidx.room.withTransaction
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.SyncState
import com.worldexplorer.data.database.TrackEntity
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.database.toDomain
import com.worldexplorer.data.database.toEntity
import com.worldexplorer.domain.geo.CellKey
import com.worldexplorer.domain.model.GpsQualityStats
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.repository.RecordedPoint
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepositoryImpl @Inject constructor(
    private val db: WorldExplorerDatabase,
    private val exploration: ExplorationStore,
) : TrackRepository {
    private val tracks get() = db.trackDao()
    private val points get() = db.pointDao()

    override fun observeTracks(): Flow<List<Track>> = tracks.observeAll().map { list -> list.map { it.toDomain() } }
    override fun observeTrack(trackId: String): Flow<Track?> = tracks.observeById(trackId).map { it?.toDomain() }
    override fun observeActiveTrack(): Flow<Track?> = tracks.observeActive().map { it?.toDomain() }.distinctUntilChanged()
    override fun observePoints(trackId: String): Flow<List<TrackPoint>> = points.observeForTrack(trackId).map { list -> list.map { it.toDomain() } }

    override suspend fun getActiveTrack(): Track? = tracks.getActive()?.toDomain()
    override suspend fun getTrack(trackId: String): Track? = tracks.getById(trackId)?.toDomain()
    override suspend fun getLastPoint(trackId: String): TrackPoint? = points.lastForTrack(trackId)?.toDomain()

    override suspend fun getQualityStats(trackId: String): GpsQualityStats {
        val q = points.quality(trackId)
        return GpsQualityStats(q.average, q.best, q.worst, q.preciseShare)
    }

    override suspend fun startTrack(trackId: String, startedAtUtc: Long): AppResult<Track> = dbCall("startTrack") {
        db.withTransaction {
            if (tracks.getActive() != null) {
                AppResult.Failure(AppError.AlreadyTracking)
            } else {
                val entity = TrackEntity(
                    id = trackId, startedAt = startedAtUtc, endedAt = null, status = TrackStatus.ACTIVE.name,
                    distanceMeters = 0.0, pointCount = 0, rejectedPointCount = 0, lastPointAt = null,
                    createdAt = startedAtUtc, updatedAt = startedAtUtc,
                )
                tracks.insert(entity)
                AppResult.Success(entity.toDomain())
            }
        }
    }

    override suspend fun finishTrack(trackId: String, endedAtUtc: Long, status: TrackStatus): AppResult<Track> = dbCall("finishTrack") {
        db.withTransaction {
            val track = tracks.getById(trackId)
            when {
                track == null -> AppResult.Failure(AppError.Validation("unknown track"))
                track.status != TrackStatus.ACTIVE.name -> AppResult.Success(track.toDomain())
                else -> {
                    val closed = track.copy(
                        endedAt = maxOf(endedAtUtc, track.startedAt),
                        status = status.name,
                        updatedAt = maxOf(endedAtUtc, track.updatedAt),
                        syncState = SyncState.PENDING,
                    )
                    tracks.update(closed)
                    AppResult.Success(closed.toDomain())
                }
            }
        }
    }

    override suspend fun recordPoint(
        point: TrackPoint,
        distanceIncrementMeters: Double,
        cells: Collection<CellKey>,
        nowUtc: Long,
    ): AppResult<RecordedPoint> = dbCall("recordPoint") {
        db.withTransaction {
            val track = tracks.getById(point.trackId)
            if (track == null || track.status != TrackStatus.ACTIVE.name) {
                AppResult.Failure(AppError.NotTracking)
            } else {
                points.insert(point.toEntity(createdAt = nowUtc))
                tracks.update(
                    track.copy(
                        distanceMeters = track.distanceMeters + distanceIncrementMeters,
                        pointCount = track.pointCount + 1,
                        lastPointAt = maxOf(track.lastPointAt ?: 0L, point.timestampUtc),
                        updatedAt = maxOf(nowUtc, track.updatedAt),
                        syncState = SyncState.PENDING,
                    ),
                )
                val added = exploration.addCells(cells, point.timestampUtc)
                AppResult.Success(RecordedPoint(point, added))
            }
        }
    }

    override suspend fun addRejectedPoints(trackId: String, count: Int) {
        dbCall("addRejected") { AppResult.Success(tracks.addRejected(trackId, count)) }
    }

    override suspend fun deleteTrackIfEmpty(trackId: String) {
        dbCall("deleteTrackIfEmpty") {
            db.withTransaction { if (points.countForTrack(trackId) == 0) tracks.delete(trackId) }
            AppResult.Success(Unit)
        }
    }
}
