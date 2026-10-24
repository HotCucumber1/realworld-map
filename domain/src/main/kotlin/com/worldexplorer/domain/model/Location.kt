package com.worldexplorer.domain.model

/** Geographic coordinate in WGS84 degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double)

enum class LocationSource { GPS, FUSED, NETWORK, PASSIVE, UNKNOWN }

/** A raw measurement delivered by a location provider, before any filtering. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val timestampUtcMillis: Long,
    val accuracyMeters: Float?,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val source: LocationSource = LocationSource.UNKNOWN,
)

/** Last position known to the app, with the moment it was measured (to show its age). */
data class CurrentLocation(
    val point: GeoPoint,
    val accuracyMeters: Float?,
    val timestampUtcMillis: Long,
)
