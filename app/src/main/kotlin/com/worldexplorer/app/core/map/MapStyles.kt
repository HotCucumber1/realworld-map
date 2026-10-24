package com.worldexplorer.app.core.map

import com.worldexplorer.domain.model.MapStyle

/** Where a base map style comes from: a style URL or an inline style JSON. */
sealed interface StyleSource {
    data class Url(val url: String) : StyleSource
    data class Json(val json: String) : StyleSource
}

/**
 * Free base maps without API keys:
 * - OpenFreeMap (https://openfreemap.org): free vector tiles from OpenStreetMap data, no key, no
 *   registration; attribution "OpenFreeMap © OpenMapTiles Data from OpenStreetMap".
 * - OpenStreetMap Standard raster tiles: subject to the OSMF tile usage policy (light personal
 *   use only, valid User-Agent, no bulk download). Kept as a fallback style.
 */
object MapStyles {
    const val LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val POSITRON = "https://tiles.openfreemap.org/styles/positron"
    const val BRIGHT = "https://tiles.openfreemap.org/styles/bright"

    val OSM_RASTER_JSON = """
        {
          "version": 8,
          "name": "OpenStreetMap",
          "sources": {
            "osm": {
              "type": "raster",
              "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "maxzoom": 19,
              "attribution": "© OpenStreetMap contributors"
            }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "#e8e4d8" } },
            { "id": "osm", "type": "raster", "source": "osm" }
          ]
        }
    """.trimIndent()

    fun resolve(style: MapStyle, customUrl: String): StyleSource = when (style) {
        MapStyle.OPENFREEMAP_LIBERTY -> StyleSource.Url(LIBERTY)
        MapStyle.OPENFREEMAP_POSITRON -> StyleSource.Url(POSITRON)
        MapStyle.OPENFREEMAP_BRIGHT -> StyleSource.Url(BRIGHT)
        MapStyle.OSM_RASTER -> StyleSource.Json(OSM_RASTER_JSON)
        MapStyle.CUSTOM -> if (isAllowedCustomUrl(customUrl)) StyleSource.Url(customUrl) else StyleSource.Url(LIBERTY)
    }

    /** Only HTTPS (or bundled asset) styles are accepted. */
    fun isAllowedCustomUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("asset://")
}
