package com.worldexplorer.app.core.map

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import com.worldexplorer.domain.geo.GeoBounds
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapMode
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos

/** Pre-serialized layer data produced off the main thread by the ViewModel. */
data class ExplorationLayerData(val fog: String, val explored: String, val edges: String)

data class UserMarker(val latitude: Double, val longitude: Double, val accuracyMeters: Float?, val stale: Boolean)

/**
 * Adapter between domain geometry and MapLibre. The base map stays untouched; exploration is
 * drawn as extra layers on top of it:
 *
 *   fog fill (opaque, world minus explored)  ─┐ Fog of War mode
 *   fog soft edge (blurred line on borders)   ─┘
 *   explored fill + outline                   ── Explorer Map mode
 *   route line, route markers, user location  ── both modes
 *
 * Switching the mode only toggles layer visibility: no data is reloaded or recomputed.
 * The fog is a real mask: it is fully opaque, and explored areas are holes in it, so the base map
 * is visible exactly there.
 *
 * Fail-closed updates: MapLibre re-tiles a GeoJSON source after every update and draws nothing from
 * it meanwhile, which would briefly expose the whole base map. The fog is therefore double-buffered:
 * new data goes to the idle buffer while the previous one keeps drawing (the union of two fog masks
 * never hides less than either), and the previous buffer is cleared only once the map reports it is
 * idle, i.e. the new buffer is fully rendered.
 */
class ExplorationMapController(private val context: Context, val map: MapLibreMap, private val view: MapView) {
    private var style: Style? = null
    private var styleKey: StyleSource? = null
    private var layerData: ExplorationLayerData? = null
    private var route: String = GeoJsonWriter.EMPTY
    private var markers: String = GeoJsonWriter.EMPTY
    private var user: UserMarker? = null
    private var mode = MapMode.FOG_OF_WAR
    private var fogStyle = FogStyle.CLOUDS
    private var showHighlight = true
    private var showRoute = true
    private var onStyleReady: (() -> Unit)? = null
    /** Index (0/1) of the fog buffer holding the newest data; the other one is retired on idle. */
    private var currentFog = 0
    private var retireFog: Int? = null
    private var swapStartedAt = 0L

    /**
     * Retires the previous fog buffer once the new one is drawn: on a fully rendered frame (no tile
     * of any source still loading) at least [MIN_SWAP_MILLIS] after the update, or when the map is idle.
     */
    private fun retireIfRendered(fully: Boolean) {
        val retired = retireFog ?: return
        if (!fully || SystemClock.uptimeMillis() - swapStartedAt < MIN_SWAP_MILLIS) return
        retireFog = null
        style?.getSourceAs<GeoJsonSource>(SRC_FOG[retired])?.setGeoJson(GeoJsonWriter.EMPTY)
    }
    private val frameListener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ -> retireIfRendered(fully) }
    private val idleListener = MapView.OnDidBecomeIdleListener { retireIfRendered(true) }

    init {
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = true
            setAttributionGravity(Gravity.TOP or Gravity.START)
            setCompassGravity(Gravity.TOP or Gravity.END)
            isTiltGesturesEnabled = false
        }
        map.setMaxZoomPreference(20.0)
        view.addOnDidBecomeIdleListener(idleListener)
        view.addOnDidFinishRenderingFrameListener(frameListener)
    }

    fun setUiInsets(topPx: Int, startPx: Int, endPx: Int) {
        map.uiSettings.setAttributionMargins(startPx, topPx, 0, 0)
        map.uiSettings.setCompassMargins(0, topPx, endPx, 0)
    }

    fun loadStyle(source: StyleSource, onReady: () -> Unit) {
        if (source == styleKey && style != null) { onReady(); return }
        styleKey = source
        onStyleReady = onReady
        val builder = when (source) {
            is StyleSource.Url -> Style.Builder().fromUri(source.url)
            is StyleSource.Json -> Style.Builder().fromJson(source.json)
        }
        style = null
        map.setStyle(builder) { loaded ->
            style = loaded
            install(loaded)
            onStyleReady?.invoke()
        }
    }

    private fun install(s: Style) {
        s.addImage(FOG_PATTERN, FogTexture.create())
        currentFog = 0
        retireFog = null
        s.addSource(GeoJsonSource(SRC_FOG[0], layerData?.fog ?: FULL_FOG))
        s.addSource(GeoJsonSource(SRC_FOG[1], GeoJsonWriter.EMPTY))
        s.addSource(GeoJsonSource(SRC_EXPLORED, layerData?.explored ?: GeoJsonWriter.EMPTY))
        s.addSource(GeoJsonSource(SRC_EDGES, layerData?.edges ?: GeoJsonWriter.EMPTY))
        s.addSource(GeoJsonSource(SRC_ROUTE, route))
        s.addSource(GeoJsonSource(SRC_MARKERS, markers))
        s.addSource(GeoJsonSource(SRC_USER, GeoJsonWriter.EMPTY))

        for (i in 0..1) {
            s.addLayer(FillLayer(L_FOG[i], SRC_FOG[i]).withProperties(PropertyFactory.fillAntialias(false), PropertyFactory.fillOpacity(1f), PropertyFactory.fillPattern(FOG_PATTERN)))
            s.addLayer(FillLayer(L_FOG_DARK[i], SRC_FOG[i]).withProperties(PropertyFactory.fillAntialias(false), PropertyFactory.fillOpacity(1f), PropertyFactory.fillColor(DARK_FOG)))
        }
        s.addLayer(
            LineLayer(L_FOG_EDGE, SRC_EDGES).withProperties(
                PropertyFactory.lineColor(FogTexture.AVERAGE),
                PropertyFactory.lineWidth(edgeWidth()),
                PropertyFactory.lineBlur(edgeWidth()),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(FillLayer(L_EXPLORED, SRC_EXPLORED).withProperties(PropertyFactory.fillColor(ACCENT), PropertyFactory.fillOpacity(0.32f)))
        s.addLayer(
            LineLayer(L_EXPLORED_OUTLINE, SRC_EDGES).withProperties(
                PropertyFactory.lineColor(ACCENT), PropertyFactory.lineOpacity(0.85f), PropertyFactory.lineWidth(1.6f), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            LineLayer(L_ROUTE, SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(ROUTE),
                PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 2f), Expression.stop(18, 5f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        s.addLayer(
            CircleLayer(L_MARKERS, SRC_MARKERS).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(WHITE),
                PropertyFactory.circleColor(Expression.match(Expression.get("kind"), Expression.color(ACCENT), Expression.stop("end", Expression.color(END)))),
            ),
        )
        s.addLayer(CircleLayer(L_USER_ACCURACY, SRC_USER).withProperties(PropertyFactory.circleColor(ACCENT), PropertyFactory.circleOpacity(0.15f), PropertyFactory.circleStrokeColor(ACCENT), PropertyFactory.circleStrokeOpacity(0.4f), PropertyFactory.circleStrokeWidth(1f)))
        s.addLayer(CircleLayer(L_USER, SRC_USER).withProperties(PropertyFactory.circleRadius(7f), PropertyFactory.circleStrokeWidth(3f), PropertyFactory.circleStrokeColor(WHITE), PropertyFactory.circleColor(ACCENT)))
        applyAppearance()
        user?.let { setUser(it) }
    }

    fun setExploration(data: ExplorationLayerData) {
        layerData = data
        val s = style ?: return
        if (retireFog != null) {
            // A swap is still in progress: the retiring buffer keeps covering; refresh the loading one.
            s.getSourceAs<GeoJsonSource>(SRC_FOG[currentFog])?.setGeoJson(data.fog)
        } else {
            val next = 1 - currentFog
            s.getSourceAs<GeoJsonSource>(SRC_FOG[next])?.setGeoJson(data.fog)
            retireFog = currentFog
            currentFog = next
            swapStartedAt = SystemClock.uptimeMillis()
        }
        s.getSourceAs<GeoJsonSource>(SRC_EXPLORED)?.setGeoJson(data.explored)
        s.getSourceAs<GeoJsonSource>(SRC_EDGES)?.setGeoJson(data.edges)
    }

    fun setAppearance(mode: MapMode, fogStyle: FogStyle, showHighlight: Boolean, showRoute: Boolean) {
        this.mode = mode
        this.fogStyle = fogStyle
        this.showHighlight = showHighlight
        this.showRoute = showRoute
        applyAppearance()
    }

    private fun applyAppearance() {
        val s = style ?: return
        val fog = mode == MapMode.FOG_OF_WAR
        s.getLayerAs<LineLayer>(L_FOG_EDGE)?.setProperties(PropertyFactory.lineColor(if (fogStyle == FogStyle.CLOUDS) FogTexture.AVERAGE else DARK_FOG))
        for (i in 0..1) {
            visible(s.getLayer(L_FOG[i]), fog && fogStyle == FogStyle.CLOUDS)
            visible(s.getLayer(L_FOG_DARK[i]), fog && fogStyle == FogStyle.DARK)
        }
        visible(s.getLayer(L_FOG_EDGE), fog)
        visible(s.getLayer(L_EXPLORED), !fog && showHighlight)
        visible(s.getLayer(L_EXPLORED_OUTLINE), !fog && showHighlight)
        visible(s.getLayer(L_ROUTE), showRoute)
    }

    fun setRoute(routeJson: String, markersJson: String) {
        route = routeJson
        markers = markersJson
        style?.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(routeJson)
        style?.getSourceAs<GeoJsonSource>(SRC_MARKERS)?.setGeoJson(markersJson)
    }

    fun setUser(marker: UserMarker?) {
        user = marker
        val s = style ?: return
        val source = s.getSourceAs<GeoJsonSource>(SRC_USER) ?: return
        if (marker == null) { source.setGeoJson(GeoJsonWriter.EMPTY); return }
        source.setGeoJson(GeoJsonWriter.points(listOf(com.worldexplorer.domain.model.GeoPoint(marker.latitude, marker.longitude) to "user")))
        s.getLayerAs<CircleLayer>(L_USER)?.setProperties(PropertyFactory.circleColor(if (marker.stale) STALE else ACCENT))
        // Accuracy circle in metres → pixels: m / (156543.03 · cos φ / 2^z), interpolated exponentially.
        val metersPerPixelZ0 = 156_543.03392 * cos(Math.toRadians(marker.latitude))
        val r = (marker.accuracyMeters ?: 0f).toDouble()
        s.getLayerAs<CircleLayer>(L_USER_ACCURACY)?.setProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(Expression.exponential(2), Expression.zoom(), Expression.stop(0, (r / metersPerPixelZ0).toFloat()), Expression.stop(22, (r * (1 shl 22) / metersPerPixelZ0).toFloat())),
            ),
        )
    }

    /**
     * Visible region as [GeoBounds]. Corner longitudes may wrap around ±180; a small region whose
     * corners span more than 180° crosses the antimeridian, so west/east are swapped.
     */
    fun visibleBounds(): GeoBounds? {
        val region = map.projection.visibleRegion
        val corners = listOf(region.farLeft, region.farRight, region.nearLeft, region.nearRight).filterNotNull()
        if (corners.isEmpty()) return null
        val south = corners.minOf { it.latitude }.coerceIn(-90.0, 90.0)
        val north = corners.maxOf { it.latitude }.coerceIn(-90.0, 90.0)
        var west = corners.minOf { it.longitude }
        var east = corners.maxOf { it.longitude }
        if (east - west > 180.0 && map.cameraPosition.zoom > 3) { val w = east; east = west; west = w }
        return GeoBounds(south, west, north, east)
    }

    fun camera(): CameraPosition = map.cameraPosition

    fun moveTo(latitude: Double, longitude: Double, zoom: Double, animate: Boolean) {
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom)
        if (animate) map.animateCamera(update, 600) else map.moveCamera(update)
    }

    fun restore(position: CameraPosition) = map.moveCamera(CameraUpdateFactory.newCameraPosition(position))

    fun fit(points: List<LatLng>, paddingPx: Int) {
        if (points.isEmpty()) return
        if (points.size == 1) { moveTo(points[0].latitude, points[0].longitude, 16.0, false); return }
        val bounds = LatLngBounds.Builder().includes(points).build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
    }

    private fun visible(layer: Layer?, show: Boolean) {
        layer?.setProperties(PropertyFactory.visibility(if (show) Property.VISIBLE else Property.NONE))
    }

    /** Soft edge ≈ 10 m wide at any zoom: doubles with every zoom level. */
    private fun edgeWidth(): Expression = Expression.interpolate(Expression.exponential(2), Expression.zoom(), Expression.stop(10, 0.75f), Expression.stop(14, 2.5f), Expression.stop(22, 640f))

    companion object {
        private const val FOG_PATTERN = "we-fog-pattern"
        private val SRC_FOG = arrayOf("we-fog-a", "we-fog-b")
        private const val MIN_SWAP_MILLIS = 250L
        private const val SRC_EXPLORED = "we-explored"
        private const val SRC_EDGES = "we-edges"
        private const val SRC_ROUTE = "we-route"
        private const val SRC_MARKERS = "we-markers"
        private const val SRC_USER = "we-user"
        val L_FOG = arrayOf("we-fog-fill-a", "we-fog-fill-b")
        val L_FOG_DARK = arrayOf("we-fog-fill-dark-a", "we-fog-fill-dark-b")
        const val L_FOG_EDGE = "we-fog-edge"
        const val L_EXPLORED = "we-explored-fill"
        const val L_EXPLORED_OUTLINE = "we-explored-outline"
        const val L_ROUTE = "we-route-line"
        const val L_MARKERS = "we-route-markers"
        const val L_USER_ACCURACY = "we-user-accuracy"
        const val L_USER = "we-user-dot"

        private const val ACCENT = 0xFF2EC4B6.toInt()
        private const val ROUTE = 0xFFFFB703.toInt()
        private const val END = 0xFFE5484D.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val STALE = 0xFF8A94A6.toInt()
        private const val DARK_FOG = 0xFF0E1522.toInt()

        /** The whole world fogged; used until the first geometry arrives (fail closed). */
        val FULL_FOG = GeoJsonWriter.polygons(listOf(com.worldexplorer.domain.geo.GeoPolygon(com.worldexplorer.domain.geo.ExploredGeometry.WORLD_RING, emptyList())))
    }
}
