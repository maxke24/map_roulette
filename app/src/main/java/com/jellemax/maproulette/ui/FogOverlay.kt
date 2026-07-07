package com.jellemax.maproulette.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max

/**
 * Fog-of-war: darkens the map except a corridor along every driven trace
 * (and around the current location). Explored roads stay clear.
 */
class FogOverlay(
    private val tracesProvider: () -> List<List<GeoPoint>>,
    private val currentLocationProvider: () -> GeoPoint?,
) : Overlay() {

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

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val w = mapView.width
        val h = mapView.height
        if (w <= 0 || h <= 0) return

        val buf = buffer?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffer = it }
        buf.eraseColor(fogColor)
        val bufCanvas = Canvas(buf)

        val projection = mapView.projection
        // Corridor of ~200 m around driven roads, but never thinner than 18 px.
        val corridorPx = max(18f, projection.metersToPixels(200f))
        clearPaint.strokeWidth = corridorPx

        val pt = Point()
        for (trace in tracesProvider()) {
            val path = Path()
            var first = true
            for (gp in trace) {
                projection.toPixels(gp, pt)
                if (first) {
                    path.moveTo(pt.x.toFloat(), pt.y.toFloat())
                    first = false
                } else {
                    path.lineTo(pt.x.toFloat(), pt.y.toFloat())
                }
            }
            bufCanvas.drawPath(path, clearPaint)
        }

        currentLocationProvider()?.let { loc ->
            projection.toPixels(loc, pt)
            bufCanvas.drawCircle(
                pt.x.toFloat(), pt.y.toFloat(),
                max(corridorPx, projection.metersToPixels(350f)),
                clearFillPaint,
            )
        }

        // Overlay canvas may be translated during scroll; anchor to screen.
        canvas.save()
        canvas.setMatrix(null)
        canvas.drawBitmap(buf, 0f, 0f, null)
        canvas.restore()
    }
}
