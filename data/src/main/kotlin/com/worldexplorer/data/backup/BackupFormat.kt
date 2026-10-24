package com.worldexplorer.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * World Explorer backup, format version 1. Documented in BACKUP_FORMAT.md.
 *
 * ZIP archive:
 *   manifest.json   BackupManifest (written last; holds SHA-256 and line counts of the others)
 *   settings.json   BackupSettings
 *   tracks.jsonl    one BackupTrack per line
 *   points.jsonl    one BackupPoint per line
 *   cells.jsonl     one BackupCell per line
 * JSON Lines keep memory bounded: export and import stream record by record.
 */
object BackupFormat {
    const val FORMAT_ID = "world-explorer-backup"
    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val TRACKS = "tracks.jsonl"
    const val POINTS = "points.jsonl"
    const val CELLS = "cells.jsonl"
    val DATA_ENTRIES = listOf(SETTINGS, TRACKS, POINTS, CELLS)
    val ALLOWED_ENTRIES = DATA_ENTRIES.toSet() + MANIFEST

    /** Hard limits against oversized or malicious archives (zip bombs, huge lines). */
    const val MAX_ARCHIVE_BYTES = 1L shl 30            // 1 GiB compressed
    const val MAX_ENTRY_BYTES = 4L shl 30              // 4 GiB uncompressed per entry
    const val MAX_MANIFEST_BYTES = 1L shl 20           // 1 MiB
    const val MAX_LINE_BYTES = 64 * 1024
    const val MAX_TRACKS = 1_000_000

    val json = Json {
        ignoreUnknownKeys = true   // newer minor additions are tolerated within a format version
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAtUtc: Long,
    val trackCount: Int,
    val pointCount: Long,
    val cellCount: Long,
    val entries: Map<String, BackupEntryInfo>,
)

@Serializable
data class BackupEntryInfo(val sha256: String, val bytes: Long, val lines: Long)

@Serializable
data class BackupTrack(
    val id: String,
    val startedAtUtc: Long,
    val endedAtUtc: Long? = null,
    val status: String,
    val distanceMeters: Double,
    val pointCount: Int,
    val rejectedPointCount: Int = 0,
    val lastPointAtUtc: Long? = null,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
)

@Serializable
data class BackupPoint(
    val id: String,
    val trackId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampUtc: Long,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val source: String = "UNKNOWN",
    val exploreRadiusMeters: Int,
)

@Serializable
data class BackupCell(val row: Int, val col: Int, val firstVisitedAtUtc: Long)

/** Settings that make sense on another phone (no account, sync or onboarding state). */
@Serializable
data class BackupSettings(
    val mapMode: String,
    val exploreRadiusMeters: Int,
    val trackingProfile: String,
    val showTrack: Boolean,
    val showExploredHighlight: Boolean,
    val fogStyle: String,
    val mapStyle: String,
    val customStyleUrl: String,
)
