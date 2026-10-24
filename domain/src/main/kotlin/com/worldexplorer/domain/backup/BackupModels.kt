package com.worldexplorer.domain.backup

enum class ImportMode {
    /** Keep local data and add what is missing; for the same track id the newer version wins. */
    MERGE,
    /** Delete all local tracks, points and territory and use the backup instead. */
    REPLACE,
}

data class BackupInfo(
    val formatVersion: Int,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAtUtc: Long,
    val trackCount: Int,
    val pointCount: Long,
    val cellCount: Long,
    val includesSettings: Boolean,
)

data class ImportSummary(
    val mode: ImportMode,
    val tracksInserted: Int,
    val tracksUpdated: Int,
    val tracksSkipped: Int,
    val pointsInserted: Long,
    val pointsSkipped: Long,
    val cellsInserted: Long,
    val cellsSkipped: Long,
    val settingsApplied: Boolean,
    /** Path of the automatic local backup taken before REPLACE, if any. */
    val safetyBackupPath: String?,
)
