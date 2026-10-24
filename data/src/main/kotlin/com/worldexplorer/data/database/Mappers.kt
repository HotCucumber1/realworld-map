package com.worldexplorer.data.database

import com.worldexplorer.domain.model.LocationSource
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus

fun TrackEntity.toDomain() = Track(
    id = id,
    startedAtUtc = startedAt,
    endedAtUtc = endedAt,
    status = parseStatus(status),
    distanceMeters = distanceMeters,
    pointCount = pointCount,
    rejectedPointCount = rejectedPointCount,
    lastPointAtUtc = lastPointAt,
    createdAtUtc = createdAt,
    updatedAtUtc = updatedAt,
)

fun LocationPointEntity.toDomain() = TrackPoint(
    id = id,
    trackId = trackId,
    latitude = latitude,
    longitude = longitude,
    timestampUtc = timestampUtc,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    source = parseSource(source),
    exploreRadiusMeters = exploreRadiusMeters,
)

fun TrackPoint.toEntity(createdAt: Long, syncState: Int = SyncState.PENDING) = LocationPointEntity(
    id = id,
    trackId = trackId,
    latitude = latitude,
    longitude = longitude,
    timestampUtc = timestampUtc,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    source = source.name,
    exploreRadiusMeters = exploreRadiusMeters,
    createdAt = createdAt,
    syncState = syncState,
)

fun parseStatus(value: String): TrackStatus = runCatching { TrackStatus.valueOf(value) }.getOrDefault(TrackStatus.INTERRUPTED)
fun parseSource(value: String): LocationSource = runCatching { LocationSource.valueOf(value) }.getOrDefault(LocationSource.UNKNOWN)
