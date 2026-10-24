package com.worldexplorer.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        TrackEntity::class,
        LocationPointEntity::class,
        ExploredCellEntity::class,
        ExploredCellSummaryEntity::class,
        ExplorationStatsEntity::class,
        SyncMetadataEntity::class,
    ],
    version = WorldExplorerDatabase.VERSION,
    exportSchema = true,
)
abstract class WorldExplorerDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun pointDao(): PointDao
    abstract fun cellDao(): CellDao
    abstract fun statsDao(): StatsDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val VERSION = 1
        const val NAME = "world_explorer.db"
    }
}

/**
 * Explicit schema migrations. Exported schemas live in data/schemas; every version bump must add a
 * migration here (destructive migration is never enabled) and a MigrationTestHelper test.
 */
object DatabaseMigrations {
    val ALL: Array<Migration> = emptyArray()
}
