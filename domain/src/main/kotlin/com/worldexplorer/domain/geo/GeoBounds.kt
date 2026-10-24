package com.worldexplorer.domain.geo

/**
 * A visible map region. [west] may be greater than [east] when the region crosses the antimeridian;
 * longitudes outside ±180 (as reported by a map that wrapped around the world) are accepted and
 * normalized by [longitudeRanges].
 */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south <= north) { "south must not exceed north" }
    }

    /** Splits the region into at most two longitude ranges inside [-180, 180]. */
    fun longitudeRanges(): List<ClosedFloatingPointRange<Double>> {
        val span = if (east >= west) east - west else east + 360.0 - west
        if (span >= 360.0) return listOf(-180.0..180.0)
        val w = GeoMath.normalizeLongitude(west)
        val e = w + span
        return if (e <= 180.0) listOf(w..e) else listOf(w..180.0, -180.0..(e - 360.0))
    }

    /** Region grown by [factor] of its size on each side, clamped to the valid latitude range. */
    fun expandedBy(factor: Double): GeoBounds {
        val latPad = (north - south) * factor
        val span = if (east >= west) east - west else east + 360.0 - west
        val lonPad = span * factor
        return if (span + 2 * lonPad >= 360.0) {
            GeoBounds((south - latPad).coerceAtLeast(-90.0), -180.0, (north + latPad).coerceAtMost(90.0), 180.0)
        } else {
            GeoBounds((south - latPad).coerceAtLeast(-90.0), west - lonPad, (north + latPad).coerceAtMost(90.0), east + lonPad)
        }
    }
}
