package app.narabuspulse.ui.kit

import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon

/** 地図上の印。 */
data class MapPin(
    val id: String,
    val lat: Double,
    val lon: Double,
    val title: String = "",
    val snippet: String = "",
    val color: Color = Color(0xFFE66116),
    val size: Dp = 14.dp,
    val label: String? = null,
)

/** 地図上の線（経路など）。 */
data class MapLine(val points: List<Pair<Double, Double>>, val color: Color = Color(0xFF2F6FB0), val width: Float = 6f, val dashed: Boolean = false)

/** 地図上の円（範囲など）。 */
data class MapCircle(val lat: Double, val lon: Double, val radiusM: Double, val color: Color = Color(0xFFE66116))

/**
 * OpenStreetMap の地図（Web 版の Leaflet + OSM タイルと同じ地図）。
 * pins/lines を渡すと描き直す。fitKey が変わったときだけ表示範囲を合わせる。
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier.fillMaxWidth().height(320.dp),
    pins: List<MapPin> = emptyList(),
    lines: List<MapLine> = emptyList(),
    circles: List<MapCircle> = emptyList(),
    center: Pair<Double, Double>? = null,
    zoom: Double = 13.0,
    fitKey: Any? = null,
    onPinClick: ((MapPin) -> Unit)? = null,
    onMapClick: ((Double, Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val map = remember {
        OsmConfig.ensure(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(zoom)
            controller.setCenter(GeoPoint(center?.first ?: 34.6851, center?.second ?: 135.8048))
            minZoomLevel = 5.0
            maxZoomLevel = 19.0
            // 一覧の中に置いたとき、地図の操作で一覧がスクロールしないようにする
            setOnTouchListener { v, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }
    val state = remember { object { var lastFit: Any? = Unit; var lastCenter: Pair<Double, Double>? = null } }

    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }

    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        mv.overlays.clear()
        if (onMapClick != null) {
            mv.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
                    val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
                    onMapClick(p.latitude, p.longitude)
                    return true
                }
            })
        }
        circles.forEach { c ->
            mv.overlays.add(Polygon(mv).apply {
                points = Polygon.pointsAsCircle(GeoPoint(c.lat, c.lon), c.radiusM)
                fillPaint.color = c.color.copy(alpha = 0.12f).toArgb()
                outlinePaint.color = c.color.toArgb()
                outlinePaint.strokeWidth = 3f
                setOnClickListener { _, _, _ -> false }
            })
        }
        lines.filter { it.points.size > 1 }.forEach { l ->
            mv.overlays.add(Polyline(mv).apply {
                setPoints(l.points.map { GeoPoint(it.first, it.second) })
                outlinePaint.color = l.color.toArgb()
                outlinePaint.strokeWidth = l.width
                outlinePaint.strokeCap = Paint.Cap.ROUND
                if (l.dashed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
                setOnClickListener { _, _, _ -> false }
            })
        }
        val density = mv.resources.displayMetrics.density
        pins.forEach { p ->
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(p.lat, p.lon)
                title = p.title
                snippet = p.snippet
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dotDrawable(p.color.toArgb(), (p.size.value * density).toInt())
                if (onPinClick != null) setOnMarkerClickListener { m, _ -> onPinClick(p); m.showInfoWindow(); true }
            })
        }
        // 表示範囲
        if (fitKey != null && fitKey != state.lastFit) {
            state.lastFit = fitKey
            val all = pins.map { it.lat to it.lon } + lines.flatMap { it.points }
            if (all.size == 1) mv.controller.setCenter(GeoPoint(all[0].first, all[0].second))
            else if (all.size > 1) {
                val box = BoundingBox.fromGeoPoints(all.map { GeoPoint(it.first, it.second) })
                mv.post { runCatching { mv.zoomToBoundingBox(box.increaseByScale(1.25f), false, 48) } }
            }
        } else if (center != null && center != state.lastCenter) {
            state.lastCenter = center
            mv.controller.setCenter(GeoPoint(center.first, center.second))
        }
        mv.invalidate()
    })
}

private fun dotDrawable(color: Int, sizePx: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke(maxOf(2, sizePx / 6), AColor.WHITE)
    setSize(sizePx, sizePx)
}

/** 地図の下に出す出典表示（OSM の利用条件）。 */
const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

/**
 * osmdroid の共通設定。地図タイルは端末の永続領域に置き（オフライン保存したタイルを消さない）、
 * 容量の上限を超えたら古いものから整理する。
 */
object OsmConfig {
    @Volatile private var done = false
    fun ensure(context: android.content.Context) {
        if (done) return
        synchronized(this) {
            if (done) return
            Configuration.getInstance().apply {
                userAgentValue = context.packageName
                osmdroidBasePath = context.filesDir.resolve("osm")
                osmdroidTileCache = context.filesDir.resolve("osm/tiles")
                tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
                tileFileSystemCacheTrimBytes = 240L * 1024 * 1024
            }
            done = true
        }
    }
}
