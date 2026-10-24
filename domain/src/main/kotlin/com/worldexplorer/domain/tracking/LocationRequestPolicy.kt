package com.worldexplorer.domain.tracking

import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.model.LocationSample
import com.worldexplorer.domain.model.TrackingProfile

/**
 * Adaptive sampling: a profile's moving interval while the user moves, a longer interval while
 * standing still. Standing still is still recorded (it explores the spot) — just less often.
 */
object LocationRequestPolicy {
    const val STATIONARY_WINDOW_MILLIS = 60_000L
    const val STATIONARY_RADIUS_METERS = 10.0
    const val STATIONARY_MIN_SAMPLES = 4
    const val STATIONARY_MAX_SPEED = 0.7f

    fun isStationary(recent: List<LocationSample>, nowUtcMillis: Long): Boolean {
        val window = recent.filter { nowUtcMillis - it.timestampUtcMillis <= STATIONARY_WINDOW_MILLIS }
        if (window.size < STATIONARY_MIN_SAMPLES) return false
        if (window.any { (it.speedMetersPerSecond ?: 0f) > STATIONARY_MAX_SPEED }) return false
        val lat = window.sumOf { it.latitude } / window.size
        val lon = window.sumOf { it.longitude } / window.size
        return window.all { GeoMath.haversineMeters(lat, lon, it.latitude, it.longitude) <= STATIONARY_RADIUS_METERS }
    }

    fun intervalMillis(profile: TrackingProfile, stationary: Boolean): Long =
        if (stationary) profile.stationaryIntervalMillis else profile.movingIntervalMillis
}
