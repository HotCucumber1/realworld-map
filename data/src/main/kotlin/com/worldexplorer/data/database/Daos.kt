package com.worldexplorer.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY started_at DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeById(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1")
    fun observeActive(): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE status = 'ACTIVE' ORDER BY started_at DESC LIMIT 1")
    suspend fun getActive(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks ORDER BY started_at")
    suspend fun getAll(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(track: TrackEntity)

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("UPDATE tracks SET rejected_point_count = rejected_point_count + :count, sync_state = 0 WHERE id = :id")
    suspend fun addRejected(id: String, count: Int)

    @Query("SELECT * FROM tracks WHERE sync_state = 0 ORDER BY updated_at LIMIT :limit")
    suspend fun pending(limit: Int): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE sync_state = 0")
    fun observePendingCount(): Flow<Int>

    /** Only marks synced if the row was not modified after it was read for upload. */
    @Query("UPDATE tracks SET sync_state = 1 WHERE id = :id AND updated_at = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long)

    @Query("UPDATE tracks SET sync_state = 0")
    suspend fun markAllPending()

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface PointDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: LocationPointEntity)

    /** Returns -1 for rows that already existed (same id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(points: List<LocationPointEntity>): List<Long>

    @Query("SELECT * FROM location_points WHERE track_id = :trackId ORDER BY timestamp_utc")
    fun observeForTrack(trackId: String): Flow<List<LocationPointEntity>>

    @Query("SELECT * FROM location_points WHERE track_id = :trackId ORDER BY timestamp_utc")
    suspend fun getForTrack(trackId: String): List<LocationPointEntity>

    @Query("SELECT * FROM location_points WHERE track_id = :trackId ORDER BY timestamp_utc DESC LIMIT 1")
    suspend fun lastForTrack(trackId: String): LocationPointEntity?

    @Query("SELECT COUNT(*) FROM location_points WHERE track_id = :trackId")
    suspend fun countForTrack(trackId: String): Int

    @Query(
        """SELECT AVG(accuracy_meters) AS avg_acc, MIN(accuracy_meters) AS min_acc, MAX(accuracy_meters) AS max_acc,
           AVG(CASE WHEN accuracy_meters <= 10 THEN 1.0 ELSE 0.0 END) AS precise_share
           FROM location_points WHERE track_id = :trackId""",
    )
    suspend fun quality(trackId: String): QualityRow

    /** Keyset pagination by primary key, for streaming export. */
    @Query("SELECT * FROM location_points WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageAfter(afterId: String, limit: Int): List<LocationPointEntity>

    @Query("SELECT COUNT(*) FROM location_points")
    suspend fun count(): Long

    @Query("SELECT * FROM location_points WHERE sync_state = 0 ORDER BY timestamp_utc LIMIT :limit")
    suspend fun pending(limit: Int): List<LocationPointEntity>

    @Query("SELECT COUNT(*) FROM location_points WHERE sync_state = 0")
    fun observePendingCount(): Flow<Long>

    @Query("UPDATE location_points SET sync_state = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE location_points SET sync_state = 0")
    suspend fun markAllPending()

    @Query("DELETE FROM location_points")
    suspend fun deleteAll()
}

@Dao
interface CellDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(cells: List<ExploredCellEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSummariesIgnore(cells: List<ExploredCellSummaryEntity>)

    @Query(
        """SELECT DISTINCT (cell_row >> :shift) AS r, (cell_col >> :shift) AS c FROM explored_cells
           WHERE cell_row BETWEEN :firstRow AND :lastRow AND cell_col BETWEEN :firstCol AND :lastCol""",
    )
    suspend fun baseWindow(shift: Int, firstRow: Long, lastRow: Long, firstCol: Long, lastCol: Long): List<CellRow>

    @Query(
        """SELECT DISTINCT (cell_row >> :shift) AS r, (cell_col >> :shift) AS c FROM explored_cell_summaries
           WHERE level = :level AND cell_row BETWEEN :firstRow AND :lastRow AND cell_col BETWEEN :firstCol AND :lastCol""",
    )
    suspend fun summaryWindow(level: Int, shift: Int, firstRow: Long, lastRow: Long, firstCol: Long, lastCol: Long): List<CellRow>

    @Query(
        """SELECT * FROM explored_cells WHERE cell_row > :row OR (cell_row = :row AND cell_col > :col)
           ORDER BY cell_row, cell_col LIMIT :limit""",
    )
    suspend fun pageAfter(row: Int, col: Int, limit: Int): List<ExploredCellEntity>

    /** Merge rule for an already explored cell: keep the earliest first visit. */
    @Query("UPDATE explored_cells SET first_visited_at = :visitedAt WHERE cell_row = :row AND cell_col = :col AND first_visited_at > :visitedAt")
    suspend fun lowerFirstVisit(row: Int, col: Int, visitedAt: Long)

    @Query("SELECT cell_row, COUNT(*) AS n FROM explored_cells GROUP BY cell_row")
    suspend fun countsByRow(): List<RowCount>

    @Query("SELECT COUNT(*) FROM explored_cells")
    suspend fun count(): Long

    @Query("SELECT * FROM explored_cells WHERE sync_state = 0 LIMIT :limit")
    suspend fun pending(limit: Int): List<ExploredCellEntity>

    @Query("SELECT COUNT(*) FROM explored_cells WHERE sync_state = 0")
    fun observePendingCount(): Flow<Long>

    @Query("UPDATE explored_cells SET sync_state = 1 WHERE cell_row = :row AND cell_col = :col")
    suspend fun markSynced(row: Int, col: Int)

    @Query("UPDATE explored_cells SET sync_state = 0")
    suspend fun markAllPending()

    @Query("DELETE FROM explored_cells")
    suspend fun deleteAll()

    @Query("DELETE FROM explored_cell_summaries")
    suspend fun deleteAllSummaries()
}

@Dao
interface StatsDao {
    @Query("SELECT * FROM exploration_stats WHERE id = 0")
    fun observe(): Flow<ExplorationStatsEntity?>

    @Query("SELECT * FROM exploration_stats WHERE id = 0")
    suspend fun get(): ExplorationStatsEntity?

    @Upsert
    suspend fun upsert(stats: ExplorationStatsEntity)
}

@Dao
interface SyncMetadataDao {
    @Query("SELECT value FROM sync_metadata WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sync_metadata")
    fun observeAll(): Flow<List<SyncMetadataEntity>>

    @Upsert
    suspend fun put(entry: SyncMetadataEntity)

    @Query("DELETE FROM sync_metadata WHERE `key` LIKE :prefix || '%'")
    suspend fun deleteByPrefix(prefix: String)
}

/** Sync-specific queries: completed tracks only — a track is uploaded once it is no longer recording. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM tracks WHERE sync_state = 0 AND status != 'ACTIVE' ORDER BY updated_at LIMIT :limit")
    suspend fun pendingTracks(limit: Int): List<TrackEntity>

    @Query(
        """SELECT p.* FROM location_points p JOIN tracks t ON t.id = p.track_id
           WHERE p.sync_state = 0 AND t.status != 'ACTIVE' AND t.sync_state = 1
           ORDER BY p.timestamp_utc LIMIT :limit""",
    )
    suspend fun pendingPoints(limit: Int): List<LocationPointEntity>
}
