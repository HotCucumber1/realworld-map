package com.worldexplorer.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Per-row sync metadata: PENDING rows still have to be uploaded. */
object SyncState {
    const val PENDING = 0
    const val SYNCED = 1
}

@Entity(
    tableName = "tracks",
    indices = [Index("status"), Index("started_at"), Index("sync_state")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    val status: String,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Double,
    @ColumnInfo(name = "point_count") val pointCount: Int,
    @ColumnInfo(name = "rejected_point_count") val rejectedPointCount: Int,
    @ColumnInfo(name = "last_point_at") val lastPointAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.PENDING,
)

@Entity(
    tableName = "location_points",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["track_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["track_id", "timestamp_utc"]), Index("timestamp_utc"), Index("sync_state")],
)
data class LocationPointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "timestamp_utc") val timestampUtc: Long,
    @ColumnInfo(name = "accuracy_meters") val accuracyMeters: Float,
    @ColumnInfo(name = "altitude_meters") val altitudeMeters: Double?,
    @ColumnInfo(name = "speed_mps") val speedMetersPerSecond: Float?,
    @ColumnInfo(name = "bearing_degrees") val bearingDegrees: Float?,
    val source: String,
    @ColumnInfo(name = "explore_radius_meters") val exploreRadiusMeters: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.PENDING,
)

/**
 * One explored base cell of [com.worldexplorer.domain.geo.ExplorationGrid]. The composite primary
 * key (row, column) doubles as the spatial index for viewport range queries. Territory is a
 * grow-only set: re-visiting a cell is an ignored insert.
 */
@Entity(
    tableName = "explored_cells",
    primaryKeys = ["cell_row", "cell_col"],
    indices = [Index("sync_state")],
)
data class ExploredCellEntity(
    @ColumnInfo(name = "cell_row") val row: Int,
    @ColumnInfo(name = "cell_col") val column: Int,
    @ColumnInfo(name = "first_visited_at") val firstVisitedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: Int = SyncState.PENDING,
)

/** Derived coarse cells (levels 4 and 8) for fast rendering at low zoom; rebuilt from explored_cells. */
@Entity(tableName = "explored_cell_summaries", primaryKeys = ["level", "cell_row", "cell_col"])
data class ExploredCellSummaryEntity(
    val level: Int,
    @ColumnInfo(name = "cell_row") val row: Int,
    @ColumnInfo(name = "cell_col") val column: Int,
)

/** Single-row aggregate, updated in the same transaction as cell inserts. */
@Entity(tableName = "exploration_stats")
data class ExplorationStatsEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "total_area_m2") val totalAreaSquareMeters: Double,
    @ColumnInfo(name = "cell_count") val cellCount: Long,
    /** Incremented on every change; observed as the "territory changed" signal. */
    val version: Long,
)

/** Key-value store for sync cursors and status. */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)

data class CellRow(
    @ColumnInfo(name = "r") val row: Long,
    @ColumnInfo(name = "c") val column: Long,
)

data class RowCount(
    @ColumnInfo(name = "cell_row") val row: Int,
    @ColumnInfo(name = "n") val count: Long,
)

data class QualityRow(
    @ColumnInfo(name = "avg_acc") val average: Double?,
    @ColumnInfo(name = "min_acc") val best: Double?,
    @ColumnInfo(name = "max_acc") val worst: Double?,
    @ColumnInfo(name = "precise_share") val preciseShare: Double?,
)
