package com.worldexplorer.domain.model

enum class TrackStatus {
    /** Recording right now (at most one track can be active). */
    ACTIVE,
    /** Stopped by the user. */
    COMPLETED,
    /** Closed automatically because recording could not be resumed (process killed long ago, permission revoked). */
    INTERRUPTED,
}

data class Track(
    val id: String,
    val startedAtUtc: Long,
    val endedAtUtc: Long?,
    val status: TrackStatus,
    val distanceMeters: Double,
    val pointCount: Int,
    val rejectedPointCount: Int,
    val lastPointAtUtc: Long?,
    val createdAtUtc: Long,
    val updatedAtUtc: Long,
) {
    fun durationMillis(nowUtc: Long): Long = ((endedAtUtc ?: if (status == TrackStatus.ACTIVE) nowUtc else lastPointAtUtc ?: startedAtUtc) - startedAtUtc).coerceAtLeast(0)
}

/** An accepted, persisted GPS measurement. */
data class TrackPoint(
    val id: String,
    val trackId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampUtc: Long,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
    val source: LocationSource,
    /** Radius that was used to explore territory around this point (kept so territory can be rebuilt). */
    val exploreRadiusMeters: Int,
)

data class GpsQualityStats(
    val averageAccuracyMeters: Double?,
    val bestAccuracyMeters: Double?,
    val worstAccuracyMeters: Double?,
    /** Share of points with accuracy ≤ 10 m, 0..1. */
    val preciseShare: Double?,
)

data class TrackDetails(
    val track: Track,
    val points: List<TrackPoint>,
    val quality: GpsQualityStats,
)

data class ExplorationStats(
    val exploredAreaSquareMeters: Double,
    val cellCount: Long,
)
