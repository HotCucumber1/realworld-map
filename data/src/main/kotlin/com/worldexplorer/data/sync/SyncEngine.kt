package com.worldexplorer.data.sync

import androidx.room.withTransaction
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.ExploredCellEntity
import com.worldexplorer.data.database.LocationPointEntity
import com.worldexplorer.data.database.SyncMetadataEntity
import com.worldexplorer.data.database.SyncState
import com.worldexplorer.data.database.TrackEntity
import com.worldexplorer.data.database.TrackStats
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.sync.SyncReport
import com.worldexplorer.domain.tracking.Clock
import javax.inject.Inject

object SyncCursors {
    const val PREFIX = "cursor."
    fun tracks(userId: String) = "${PREFIX}tracks.$userId"
    fun points(userId: String) = "${PREFIX}points.$userId"
    fun cells(userId: String) = "${PREFIX}cells.$userId"
}

/**
 * One synchronization round for a user. See SYNC.md.
 *
 * Push (local → cloud): rows with sync_state = PENDING, in batches, tracks before their points.
 * A track is uploaded only after recording stopped. Rows are marked SYNCED after the server
 * acknowledged them; a track edited meanwhile stays PENDING (updated_at guard).
 *
 * Pull (cloud → local): rows with a server sequence number above the stored cursor, in pages.
 * The cursor advances only after a page was applied in a local transaction, so an interrupted
 * round resumes where it stopped and nothing is downloaded twice in normal operation.
 *
 * Conflicts: points are immutable (same id = same point, duplicates ignored), territory is a
 * grow-only union of cells (earliest first visit wins), a track with the same id keeps the version with the later
 * updated_at (server enforces the same rule). Every operation is idempotent.
 */
class SyncEngine @Inject constructor(
    private val db: WorldExplorerDatabase,
    private val exploration: ExplorationStore,
    private val clock: Clock,
) {
    suspend fun run(userId: String, backend: SyncBackend): SyncReport {
        val report = Report()
        push(userId, backend, report)
        pull(userId, backend, report)
        return report.toReport()
    }

    private suspend fun push(userId: String, backend: SyncBackend, r: Report) {
        while (true) {
            val batch = db.syncDao().pendingTracks(TRACK_BATCH)
            if (batch.isEmpty()) break
            backend.pushTracks(batch.map { it.toRemote(userId) })
            db.withTransaction { batch.forEach { db.trackDao().markSynced(it.id, it.updatedAt) } }
            r.pushedTracks += batch.size
            if (batch.size < TRACK_BATCH) break
        }
        while (true) {
            val batch = db.syncDao().pendingPoints(POINT_BATCH)
            if (batch.isEmpty()) break
            backend.pushPoints(batch.map { it.toRemote(userId) })
            db.pointDao().markSynced(batch.map { it.id })
            r.pushedPoints += batch.size
        }
        while (true) {
            val batch = db.cellDao().pending(CELL_BATCH)
            if (batch.isEmpty()) break
            backend.pushCells(batch.map { RemoteCell(userId, it.row, it.column, it.firstVisitedAt) })
            db.withTransaction { batch.forEach { db.cellDao().markSynced(it.row, it.column) } }
            r.pushedCells += batch.size
        }
    }

    private suspend fun pull(userId: String, backend: SyncBackend, r: Report) {
        pages(SyncCursors.tracks(userId), { backend.pullTracks(userId, it, PULL_PAGE) }, { it.seq }) { page ->
            for (remote in page) {
                val local = db.trackDao().getById(remote.id)
                if (local != null && (local.status == TrackStatus.ACTIVE.name || local.updatedAt >= remote.updatedAt)) continue
                db.trackDao().upsert(remote.toEntity())
                r.pulledTracks++
            }
        }
        pages(SyncCursors.points(userId), { backend.pullPoints(userId, it, PULL_PAGE) }, { it.seq }) { page ->
            val touched = HashSet<String>()
            for (trackId in page.map { it.trackId }.toSet()) {
                if (db.trackDao().getById(trackId) == null) {
                    // The track row arrives in a later round; a placeholder keeps the foreign key valid
                    // and loses against the real row (updated_at = 0).
                    val first = page.first { it.trackId == trackId }
                    db.trackDao().insert(
                        TrackEntity(trackId, first.timestampUtc, first.timestampUtc, TrackStatus.COMPLETED.name, 0.0, 0, 0, null, first.timestampUtc, 0, SyncState.SYNCED),
                    )
                }
            }
            val ids = db.pointDao().insertIgnore(page.map { it.toEntity(clock.nowUtcMillis()) })
            ids.forEachIndexed { i, id -> if (id != -1L) { r.pulledPoints++; touched += page[i].trackId } }
            for (id in touched) {
                val track = db.trackDao().getById(id) ?: continue
                val stats = TrackStats.of(db.pointDao().getForTrack(id))
                db.trackDao().update(track.copy(distanceMeters = stats.distanceMeters, pointCount = stats.pointCount, lastPointAt = stats.lastPointAt))
            }
        }
        pages(SyncCursors.cells(userId), { backend.pullCells(userId, it, PULL_PAGE) }, { it.seq }) { page ->
            r.pulledCells += exploration.addEntities(page.map { ExploredCellEntity(it.row, it.column, it.firstVisitedAt, SyncState.SYNCED) }, keepEarliestVisit = true)
        }
    }

    private suspend fun <T> pages(cursorKey: String, fetch: suspend (Long) -> List<T>, seq: (T) -> Long, apply: suspend (List<T>) -> Unit) {
        var cursor = db.syncMetadataDao().get(cursorKey)?.toLongOrNull() ?: 0L
        while (true) {
            val page = fetch(cursor)
            if (page.isEmpty()) break
            val next = page.maxOf(seq)
            db.withTransaction {
                apply(page)
                db.syncMetadataDao().put(SyncMetadataEntity(cursorKey, next.toString()))
            }
            if (next <= cursor || page.size < PULL_PAGE) break
            cursor = next
        }
    }

    private class Report {
        var pushedTracks = 0
        var pushedPoints = 0L
        var pushedCells = 0L
        var pulledTracks = 0
        var pulledPoints = 0L
        var pulledCells = 0L
        fun toReport() = SyncReport(pushedTracks, pushedPoints, pushedCells, pulledTracks, pulledPoints, pulledCells)
    }

    companion object {
        const val TRACK_BATCH = 200
        const val POINT_BATCH = 1_000
        const val CELL_BATCH = 2_000
        const val PULL_PAGE = 1_000
    }
}

private fun TrackEntity.toRemote(userId: String) = RemoteTrack(
    userId, id, startedAt, endedAt, status, distanceMeters, pointCount, rejectedPointCount, lastPointAt, createdAt, updatedAt,
)

private fun RemoteTrack.toEntity() = TrackEntity(
    id = id,
    startedAt = startedAt,
    endedAt = endedAt ?: if (status == TrackStatus.ACTIVE.name) lastPointAt ?: startedAt else null,
    // A remote track is never recording on this device.
    status = if (status == TrackStatus.ACTIVE.name) TrackStatus.INTERRUPTED.name else status,
    distanceMeters = distanceMeters,
    pointCount = pointCount,
    rejectedPointCount = rejectedPointCount,
    lastPointAt = lastPointAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncState = SyncState.SYNCED,
)

private fun LocationPointEntity.toRemote(userId: String) = RemotePoint(
    userId, id, trackId, latitude, longitude, timestampUtc, accuracyMeters, altitudeMeters, speedMetersPerSecond, bearingDegrees, source, exploreRadiusMeters,
)

private fun RemotePoint.toEntity(now: Long) = LocationPointEntity(
    id, trackId, latitude, longitude, timestampUtc, accuracyMeters, altitudeMeters, speedMetersPerSecond, bearingDegrees, source, exploreRadiusMeters, now, SyncState.SYNCED,
)
