package com.jellemax.maproulette.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.jellemax.maproulette.R
import com.jellemax.maproulette.data.LatLon
import com.jellemax.maproulette.data.SpeedCameras
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** OpenFreeMap hosted vector styles: bright "liberty" by day, "dark" by night.
 *  Both are free and keyless — same privacy profile as the old raster tiles. */
fun openFreeMapStyleUrl(darkTheme: Boolean): String =
    if (darkTheme) "https://tiles.openfreemap.org/styles/dark"
    else "https://tiles.openfreemap.org/styles/liberty"

/** Source/layer ids. One GeoJSON source per overlay kind; [MapOverlays.render]
 *  swaps only the data so the layers themselves are set up once. */
private const val SRC_REACH = "mr-reach"
private const val SRC_WEDGE = "mr-wedge"
private const val SRC_ROUTE = "mr-route"
private const val SRC_CANDIDATES = "mr-candidates"
private const val SRC_DEST = "mr-dest"
private const val SRC_POSITION = "mr-position"
private const val SRC_CAMERAS = "mr-cameras"
private const val IMG_DEST = "mr-img-dest"
private const val IMG_POSITION = "mr-img-position"
private const val IMG_CAMERA = "mr-img-camera"
const val LAYER_CANDIDATES = "mr-candidates-dot"

/**
 * Owns the runtime sources and layers drawn on top of the basemap: the reach
 * circle and direction wedge, the route line, the spin candidates, and the
 * destination + own-position markers. Created once per [Style]; [render] only
 * pushes new GeoJSON, so overlays update without rebuilding the map.
 */
class MapOverlays(private val style: Style, context: Context) {

    init {
        ContextCompat.getDrawable(context, R.drawable.ic_map_pin)?.let {
            style.addImage(IMG_DEST, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_dot)?.let {
            style.addImage(IMG_POSITION, it.toBitmap())
        }
        ContextCompat.getDrawable(context, R.drawable.ic_map_camera)?.let {
            style.addImage(IMG_CAMERA, it.toBitmap())
        }
        listOf(SRC_REACH, SRC_WEDGE, SRC_ROUTE, SRC_CANDIDATES, SRC_DEST, SRC_POSITION, SRC_CAMERAS)
            .forEach { style.addSource(GeoJsonSource(it)) }

        // Bottom-to-top: fills, then the route (dark casing under the colored
        // line), then markers, with the tappable candidates on top.
        style.addLayer(FillLayer("mr-reach-fill", SRC_REACH).withProperties(
            PropertyFactory.fillColor("#2196F3"), PropertyFactory.fillOpacity(0.09f)))
        style.addLayer(LineLayer("mr-reach-line", SRC_REACH).withProperties(
            PropertyFactory.lineColor("#2196F3"), PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.7f)))
        style.addLayer(FillLayer("mr-wedge-fill", SRC_WEDGE).withProperties(
            PropertyFactory.fillColor("#FF9800"), PropertyFactory.fillOpacity(0.11f)))
        style.addLayer(LineLayer("mr-route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor("#0B1220"), PropertyFactory.lineWidth(11f),
            PropertyFactory.lineOpacity(0.85f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(LineLayer("mr-route-line", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(ROUTE_COLOR), PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(SymbolLayer("mr-position", SRC_POSITION).withProperties(
            PropertyFactory.iconImage(IMG_POSITION),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        style.addLayer(SymbolLayer("mr-dest", SRC_DEST).withProperties(
            PropertyFactory.iconImage(IMG_DEST), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        // Speed cameras: static markers fed by the prefetch loop. Sit under the
        // candidate dots so a spin result is never hidden behind a camera.
        style.addLayer(SymbolLayer("mr-cameras", SRC_CAMERAS).withProperties(
            PropertyFactory.iconImage(IMG_CAMERA),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
        // Candidates as colored discs with a white ring; the color matches the
        // card row, and a tap is resolved by querying this layer.
        style.addLayer(CircleLayer(LAYER_CANDIDATES, SRC_CANDIDATES).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f)))
    }

    private fun setData(sourceId: String, fc: FeatureCollection) {
        (style.getSource(sourceId) as? GeoJsonSource)?.setGeoJson(fc)
    }

    /** Replace the speed-camera markers. Fed by the prefetch loop, not [render],
     *  because cameras refresh only as you near the edge of the fetched area. */
    fun setCameras(cameras: List<SpeedCameras.Camera>) {
        setData(SRC_CAMERAS, FeatureCollection.fromFeatures(
            cameras.map { Feature.fromGeometry(Point.fromLngLat(it.at.lon, it.at.lat)) }))
    }

    /** Push the current world state to the overlay sources. Pass [reachMeters]
     *  null to hide the reach circle/wedge (e.g. while navigating). */
    fun render(
        myLocation: LatLon?,
        destination: LatLon?,
        routePolyline: List<LatLon>?,
        reachMeters: Double?,
        directionDeg: Int?,
        candidates: List<CandidatePin>,
        showPosition: Boolean,
    ) {
        setData(SRC_REACH, if (myLocation != null && reachMeters != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(circle(myLocation, reachMeters)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_WEDGE, if (myLocation != null && reachMeters != null && directionDeg != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(wedge(myLocation, reachMeters, directionDeg)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_ROUTE, if (routePolyline != null && routePolyline.size >= 2)
            FeatureCollection.fromFeature(Feature.fromGeometry(
                LineString.fromLngLats(routePolyline.map { Point.fromLngLat(it.lon, it.lat) })))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_CANDIDATES, FeatureCollection.fromFeatures(
            candidates.mapIndexed { i, c ->
                Feature.fromGeometry(Point.fromLngLat(c.at.lon, c.at.lat)).apply {
                    addNumberProperty("index", i)
                    addStringProperty("color", String.format("#%06X", 0xFFFFFF and c.colorArgb))
                }
            }))

        setData(SRC_DEST, if (destination != null)
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(destination.lon, destination.lat)))
        else FeatureCollection.fromFeatures(emptyList()))

        setData(SRC_POSITION, if (myLocation != null && showPosition)
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(myLocation.lon, myLocation.lat)))
        else FeatureCollection.fromFeatures(emptyList()))
    }

    companion object {
        const val ROUTE_COLOR = "#00B3A4" // teal — Waze-family, our own identity
    }
}

/** A spin candidate rendered as a colored map dot. */
data class CandidatePin(val at: LatLon, val colorArgb: Int)

private fun circle(center: LatLon, radiusMeters: Double, steps: Int = 64): Polygon {
    val ring = (0..steps).map { i -> offset(center, radiusMeters, i * 360.0 / steps) }
        .map { Point.fromLngLat(it.lon, it.lat) }
    return Polygon.fromLngLats(listOf(ring))
}

private fun wedge(center: LatLon, radiusMeters: Double, directionDeg: Int): Polygon {
    val arc = (-45..45 step 5).map { d -> offset(center, radiusMeters, (directionDeg + d).toDouble()) }
    val ring = (listOf(center) + arc + center).map { Point.fromLngLat(it.lon, it.lat) }
    return Polygon.fromLngLats(listOf(ring))
}

/** Point [meters] from [from] along [bearingDeg], flat-earth (fine at map scale). */
private fun offset(from: LatLon, meters: Double, bearingDeg: Double): LatLon {
    val rad = Math.toRadians(bearingDeg)
    val dLat = meters * cos(rad) / 111_320.0
    val dLon = meters * sin(rad) / (111_320.0 * cos(Math.toRadians(from.lat)))
    return LatLon(from.lat + dLat, from.lon + dLon)
}

/** Camera bounds fitted to [points] with a fraction of padding. */
fun cameraForPoints(map: MapLibreMap, points: List<LatLon>, paddingPx: Int) {
    if (points.isEmpty()) return
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lon)) }
    val bounds = if (points.size == 1)
        LatLngBounds.Builder()
            .include(LatLng(points[0].lat + 0.005, points[0].lon + 0.005))
            .include(LatLng(points[0].lat - 0.005, points[0].lon - 0.005)).build()
    else builder.build()
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
}

/** Camera position for the follow loop: target/zoom/bearing in one shot. */
fun setCamera(map: MapLibreMap, lat: Double, lon: Double, zoom: Double, bearingDeg: Float) {
    map.cameraPosition = CameraPosition.Builder()
        .target(LatLng(lat, lon)).zoom(zoom).bearing(bearingDeg.toDouble()).tilt(0.0).build()
}

/**
 * Fog-of-war overlay: a dark scrim over the whole map with a clear corridor
 * punched along every driven trace and around the current position. Sits as a
 * child View over the GL surface and reprojects through [map] each time the
 * camera moves, so it stays glued to the map in heading-up mode.
 */
class FogView(context: Context) : View(context) {
    var map: MapLibreMap? = null
    var traces: List<List<LatLon>> = emptyList()
    var currentLocation: LatLon? = null
    var corridorMeters: Float = 200f
    var active: Boolean = false

    init {
        setWillNotDraw(false)
    }

    private val fogColor = Color.argb(150, 8, 10, 26)
    private val clearPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val clearFillPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private var buffer: Bitmap? = null

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val m = map ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val buf = buffer?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffer = it }
        buf.eraseColor(fogColor)
        val bufCanvas = Canvas(buf)

        val proj = m.projection
        val lat = currentLocation?.lat ?: m.cameraPosition.target?.latitude ?: 0.0
        val metersPerPx = proj.getMetersPerPixelAtLatitude(lat).toFloat()
        val corridorPx = max(18f, corridorMeters / metersPerPx)
        clearPaint.strokeWidth = corridorPx

        // toScreenLocation is a per-point JNI call, so projecting every trace
        // every frame is what made panning lag. Cull whole traces whose bounding
        // box doesn't touch the padded viewport first — most are off-screen when
        // zoomed in, and the bbox test is cheap arithmetic with no projection.
        val vb = proj.visibleRegion.latLngBounds
        val padDeg = (corridorMeters * 2.0) / 111_000.0
        val north = vb.latitudeNorth + padDeg
        val south = vb.latitudeSouth - padDeg
        val east = vb.longitudeEast + padDeg
        val west = vb.longitudeWest - padDeg

        val pt = PointF()
        for (trace in traces) {
            if (trace.isEmpty()) continue
            var tN = -90.0; var tS = 90.0; var tE = -180.0; var tW = 180.0
            for (p in trace) {
                if (p.lat > tN) tN = p.lat
                if (p.lat < tS) tS = p.lat
                if (p.lon > tE) tE = p.lon
                if (p.lon < tW) tW = p.lon
            }
            if (tS > north || tN < south || tW > east || tE < west) continue
            val path = Path()
            var first = true
            for (p in trace) {
                val sp = proj.toScreenLocation(LatLng(p.lat, p.lon))
                if (first) { path.moveTo(sp.x, sp.y); first = false } else path.lineTo(sp.x, sp.y)
            }
            bufCanvas.drawPath(path, clearPaint)
        }
        currentLocation?.let { loc ->
            val sp = proj.toScreenLocation(LatLng(loc.lat, loc.lon))
            pt.set(sp.x, sp.y)
            bufCanvas.drawCircle(pt.x, pt.y, max(corridorPx, corridorMeters * 1.75f / metersPerPx),
                clearFillPaint)
        }
        canvas.drawBitmap(buf, 0f, 0f, null)
    }
}
