package com.worldexplorer.data.backup

import androidx.room.withTransaction
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.ExploredCellEntity
import com.worldexplorer.data.database.LocationPointEntity
import com.worldexplorer.data.database.SyncState
import com.worldexplorer.data.database.TrackEntity
import com.worldexplorer.data.database.TrackStats
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.sync.SyncCursors
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.backup.ImportSummary
import com.worldexplorer.domain.model.TrackStatus
import javax.inject.Inject

/**
 * Applies a validated backup in ONE database transaction: on any error everything is rolled back,
 * so a failed import never leaves partial data.
 *
 * Conflict rules (documented in BACKUP_FORMAT.md):
 * - Track with the same id: the version with the later updatedAt wins; on a tie the local one stays.
 * - Point with the same id: points are immutable, the existing one is kept (duplicate ignored).
 * - Cell already explored: kept; territory is a union.
 * - Track statistics (distance, point count, last point) are recomputed from the merged points.
 * - A track that was ACTIVE in the backup is imported as INTERRUPTED: it is not recording here.
 */
class BackupImporter @Inject constructor(
    private val db: WorldExplorerDatabase,
    private val reader: BackupReader,
    private val exploration: ExplorationStore,
) {
    suspend fun apply(backup: ValidatedBackup, mode: ImportMode, nowUtc: Long): Counters = db.withTransaction {
        val c = Counters()
        if (mode == ImportMode.REPLACE) {
            db.pointDao().deleteAll()
            db.trackDao().deleteAll()
            exploration.clear()
            // Local data now differs from what was pulled before: pull everything again next sync.
            db.syncMetadataDao().deleteByPrefix(SyncCursors.PREFIX)
        }

        val touched = HashSet<String>()
        reader.withRecords(backup, BackupFormat.TRACKS, reader::parseTrack) { records ->
            for (t in records) {
                val imported = t.toEntity()
                val local = db.trackDao().getById(t.id)
                when {
                    local == null -> { db.trackDao().insert(imported); c.tracksInserted++; touched += t.id }
                    imported.updatedAt > local.updatedAt && local.status != TrackStatus.ACTIVE.name -> {
                        db.trackDao().update(imported); c.tracksUpdated++; touched += t.id
                    }
                    else -> c.tracksSkipped++
                }
            }
        }

        reader.withRecords(backup, BackupFormat.POINTS, reader::parsePoint) { records ->
            val batch = ArrayList<LocationPointEntity>(BATCH)
            suspend fun flush() {
                val ids = db.pointDao().insertIgnore(batch)
                ids.forEachIndexed { i, id -> if (id != -1L) { c.pointsInserted++; touched += batch[i].trackId } else c.pointsSkipped++ }
                batch.clear()
            }
            for (p in records) {
                batch += p.toEntity(nowUtc)
                if (batch.size >= BATCH) flush()
            }
            if (batch.isNotEmpty()) flush()
        }

        reader.withRecords(backup, BackupFormat.CELLS, reader::parseCell) { records ->
            val batch = ArrayList<ExploredCellEntity>(BATCH)
            suspend fun flush() {
                val added = exploration.addEntities(batch, keepEarliestVisit = true)
                c.cellsInserted += added
                c.cellsSkipped += batch.size - added
                batch.clear()
            }
            for (cell in records) {
                batch += ExploredCellEntity(cell.row, cell.col, cell.firstVisitedAtUtc, SyncState.PENDING)
                if (batch.size >= BATCH) flush()
            }
            if (batch.isNotEmpty()) flush()
        }

        for (id in touched) {
            val track = db.trackDao().getById(id) ?: continue
            val stats = TrackStats.of(db.pointDao().getForTrack(id))
            db.trackDao().update(track.copy(distanceMeters = stats.distanceMeters, pointCount = stats.pointCount, lastPointAt = stats.lastPointAt ?: track.lastPointAt))
        }
        c
    }

    class Counters {
        var tracksInserted = 0
        var tracksUpdated = 0
        var tracksSkipped = 0
        var pointsInserted = 0L
        var pointsSkipped = 0L
        var cellsInserted = 0L
        var cellsSkipped = 0L

        fun toSummary(mode: ImportMode, settingsApplied: Boolean, safetyBackupPath: String?) = ImportSummary(
            mode, tracksInserted, tracksUpdated, tracksSkipped, pointsInserted, pointsSkipped, cellsInserted, cellsSkipped, settingsApplied, safetyBackupPath,
        )
    }

    private companion object {
        const val BATCH = 1_000
    }
}

private fun BackupTrack.toEntity(): TrackEntity {
    val wasActive = status == TrackStatus.ACTIVE.name
    return TrackEntity(
        id = id,
        startedAt = startedAtUtc,
        endedAt = if (wasActive) (endedAtUtc ?: lastPointAtUtc ?: startedAtUtc) else endedAtUtc,
        status = if (wasActive) TrackStatus.INTERRUPTED.name else status,
        distanceMeters = distanceMeters,
        pointCount = pointCount,
        rejectedPointCount = rejectedPointCount,
        lastPointAt = lastPointAtUtc,
        createdAt = createdAtUtc,
        updatedAt = updatedAtUtc,
        syncState = SyncState.PENDING,
    )
}

private fun BackupPoint.toEntity(nowUtc: Long) = LocationPointEntity(
    id = id,
    trackId = trackId,
    latitude = latitude,
    longitude = longitude,
    timestampUtc = timestampUtc,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    source = source,
    exploreRadiusMeters = exploreRadiusMeters,
    createdAt = nowUtc,
    syncState = SyncState.PENDING,
)
