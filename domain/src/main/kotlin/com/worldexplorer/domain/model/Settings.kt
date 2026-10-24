package com.worldexplorer.domain.model

enum class MapMode { FOG_OF_WAR, EXPLORER }

/** Allowed exploration radii, metres. */
object ExploreRadius {
    val OPTIONS: List<Int> = listOf(10, 20, 30, 50, 100)
    const val DEFAULT = 20
    fun sanitize(value: Int): Int = if (value in OPTIONS) value else DEFAULT
}

/** GPS sampling profile: trade-off between precision and battery. */
enum class TrackingProfile(val movingIntervalMillis: Long, val stationaryIntervalMillis: Long) {
    PRECISE(movingIntervalMillis = 2_000, stationaryIntervalMillis = 6_000),
    BALANCED(movingIntervalMillis = 4_000, stationaryIntervalMillis = 15_000),
    BATTERY_SAVER(movingIntervalMillis = 10_000, stationaryIntervalMillis = 30_000),
}

enum class FogStyle { CLOUDS, DARK }

/** Built-in base map styles. All of them are free and require no API key. */
enum class MapStyle { OPENFREEMAP_LIBERTY, OPENFREEMAP_POSITRON, OPENFREEMAP_BRIGHT, OSM_RASTER, CUSTOM }

data class AppSettings(
    val mapMode: MapMode = MapMode.FOG_OF_WAR,
    val exploreRadiusMeters: Int = ExploreRadius.DEFAULT,
    val trackingProfile: TrackingProfile = TrackingProfile.BALANCED,
    val showTrack: Boolean = true,
    val showExploredHighlight: Boolean = true,
    val fogStyle: FogStyle = FogStyle.CLOUDS,
    val mapStyle: MapStyle = MapStyle.OPENFREEMAP_LIBERTY,
    val customStyleUrl: String = "",
    val syncEnabled: Boolean = false,
    val autoSync: Boolean = true,
    val syncOnlyOnUnmetered: Boolean = false,
    val onboardingCompleted: Boolean = false,
)
