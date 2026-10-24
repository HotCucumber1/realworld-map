package com.worldexplorer.domain.geo

import com.worldexplorer.domain.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    /** Mean Earth radius (IUGG), metres. */
    const val EARTH_RADIUS_METERS = 6_371_008.8
    const val METERS_PER_DEGREE_LATITUDE = Math.PI * EARTH_RADIUS_METERS / 180.0

    /** Web-Mercator latitude limit: the base map does not render beyond it. */
    const val MAX_MERCATOR_LATITUDE = 85.05112878

    /** Great-circle distance by the haversine formula, metres. Works across the antimeridian. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Total length of a polyline, metres. */
    fun pathLengthMeters(points: List<GeoPoint>): Double =
        points.zipWithNext { a, b -> haversineMeters(a, b) }.sum()

    fun metersPerDegreeLongitude(latitude: Double): Double =
        METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(latitude))

    /** Normalizes a longitude to [-180, 180). */
    fun normalizeLongitude(longitude: Double): Double {
        var lon = (longitude + 180.0) % 360.0
        if (lon < 0) lon += 360.0
        return lon - 180.0
    }

    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        !latitude.isNaN() && !longitude.isNaN() && !latitude.isInfinite() && !longitude.isInfinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}
