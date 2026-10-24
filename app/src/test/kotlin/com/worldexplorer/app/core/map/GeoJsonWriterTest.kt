package com.worldexplorer.app.core.map

import com.worldexplorer.domain.geo.CellWindow
import com.worldexplorer.domain.geo.ExploredGeometryBuilder
import com.worldexplorer.domain.geo.packCell
import com.worldexplorer.domain.model.GeoPoint
import com.worldexplorer.domain.model.MapStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonWriterTest {
    private fun geometry() = ExploredGeometryBuilder.build(
        listOf(CellWindow(0, 100, 104, 200, 204, longArrayOf(packCell(101, 201), packCell(101, 202), packCell(102, 201), packCell(103, 203)))),
    )

    @Test
    fun `polygons are valid GeoJSON with closed rings`() {
        val json = Json.parseToJsonElement(GeoJsonWriter.polygons(geometry().explored)).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val coords = json["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(2, coords.size)
        coords.forEach { polygon ->
            (polygon as JsonArray).forEach { ring ->
                val r = ring.jsonArray
                assertTrue(r.size >= 4)
                assertEquals("ring is closed", r.first(), r.last())
            }
        }
    }

    @Test
    fun `fog contains the world ring with explored holes`() {
        val fog = Json.parseToJsonElement(GeoJsonWriter.polygons(geometry().fog)).jsonObject
        val world = fog["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals("outer world ring + 2 explored holes", 3, world.size)
        assertEquals(-180.0, world[0].jsonArray[0].jsonArray[0].jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `empty inputs produce empty collections`() {
        assertEquals(GeoJsonWriter.EMPTY, GeoJsonWriter.polygons(emptyList()))
        assertEquals(GeoJsonWriter.EMPTY, GeoJsonWriter.lineString(listOf(GeoPoint(1.0, 2.0))))
    }

    @Test
    fun `line string keeps longitude-latitude order`() {
        val json = Json.parseToJsonElement(GeoJsonWriter.lineString(listOf(GeoPoint(48.1, 11.5), GeoPoint(48.2, 11.6)))).jsonObject
        val first = json["features"]!!.jsonArray[0].jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(11.5, first[0].jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(48.1, first[1].jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `custom styles must be https`() {
        assertTrue(MapStyles.isAllowedCustomUrl("https://example.com/style.json"))
        assertFalse(MapStyles.isAllowedCustomUrl("http://example.com/style.json"))
        assertEquals(StyleSource.Url(MapStyles.LIBERTY), MapStyles.resolve(MapStyle.CUSTOM, "javascript:alert(1)"))
        assertTrue(MapStyles.resolve(MapStyle.OSM_RASTER, "") is StyleSource.Json)
    }
}
