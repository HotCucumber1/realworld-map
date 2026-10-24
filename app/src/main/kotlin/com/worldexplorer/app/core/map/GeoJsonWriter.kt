package com.worldexplorer.app.core.map

import com.worldexplorer.domain.geo.GeoPolygon
import com.worldexplorer.domain.geo.GeoRing
import com.worldexplorer.domain.model.GeoPoint

/**
 * Serializes domain geometry straight to GeoJSON text (faster than building SDK feature objects
 * for tens of thousands of vertices). Rings are closed as GeoJSON requires.
 */
object GeoJsonWriter {
    fun polygons(polygons: List<GeoPolygon>): String = StringBuilder(polygons.size * 512).apply {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        if (polygons.isNotEmpty()) {
            append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"MultiPolygon\",\"coordinates\":[")
            polygons.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append('[')
                ring(p.outer)
                p.holes.forEach { append(','); ring(it) }
                append(']')
            }
            append("]}}")
        }
        append("]}")
    }.toString()

    fun lines(rings: List<GeoRing>): String = StringBuilder(rings.size * 512).apply {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        if (rings.isNotEmpty()) {
            append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"MultiLineString\",\"coordinates\":[")
            rings.forEachIndexed { i, r -> if (i > 0) append(','); ring(r) }
            append("]}}")
        }
        append("]}")
    }.toString()

    fun lineString(points: List<GeoPoint>): String = StringBuilder(points.size * 40 + 100).apply {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        if (points.size >= 2) {
            append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            points.forEachIndexed { i, p -> if (i > 0) append(','); coord(p.longitude, p.latitude) }
            append("]}}")
        }
        append("]}")
    }.toString()

    /** Point features with a "kind" property (e.g. start/end markers, user location). */
    fun points(points: List<Pair<GeoPoint, String>>): String = StringBuilder().apply {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        points.forEachIndexed { i, (p, kind) ->
            if (i > 0) append(',')
            append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"").append(kind).append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":")
            coord(p.longitude, p.latitude)
            append("}}")
        }
        append("]}")
    }.toString()

    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    private fun StringBuilder.ring(r: GeoRing) {
        append('[')
        for (i in 0 until r.size) {
            coord(r.longitude(i), r.latitude(i))
            append(',')
        }
        if (r.size > 0) coord(r.longitude(0), r.latitude(0))
        append(']')
    }

    private fun StringBuilder.coord(lon: Double, lat: Double) {
        append('[').append(fmt(lon)).append(',').append(fmt(lat)).append(']')
    }

    // Rounded to 7 decimals (≈1 cm): enough for 5 m cells and keeps the payload small.
    // Double.toString may use exponent notation, which is valid JSON.
    private fun fmt(v: Double): Double = Math.round(v * 1e7) / 1e7
}
