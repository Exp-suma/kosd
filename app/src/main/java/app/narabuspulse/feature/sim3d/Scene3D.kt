package app.narabuspulse.feature.sim3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.list
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 描画用の面（ローカル座標 m：x=東, y=上, z=北）。 */
class Face(val kind: Char, val id: String, val pts: FloatArray) {
    val normalY: Float
    val nx: Float; val ny: Float; val nz: Float
    init {
        var x = 0f; var y = 0f; var z = 0f
        if (pts.size >= 9) {
            val ax = pts[3] - pts[0]; val ay = pts[4] - pts[1]; val az = pts[5] - pts[2]
            val bx = pts[6] - pts[0]; val by = pts[7] - pts[1]; val bz = pts[8] - pts[2]
            x = ay * bz - az * by; y = az * bx - ax * bz; z = ax * by - ay * bx
            val l = sqrt(x * x + y * y + z * z).takeIf { it > 1e-6f } ?: 1f
            x /= l; y /= l; z /= l
        }
        nx = x; ny = y; nz = z; normalY = y
    }
}
class Sprite(val kind: String, val x: Float, val y: Float, val z: Float, val h: Float, val crown: Float)
class SceneModel(val faces: List<Face>, val sprites: List<Sprite>, val lines: List<FloatArray>)

private fun floats(e: JsonElement?): FloatArray = (e as? JsonArray)?.let { a -> FloatArray(a.size) { i -> (a[i] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f } } ?: FloatArray(0)

fun parseScene(e: JsonElement?): SceneModel = SceneModel(
    e.items("faces").map { f -> Face(f.str("k")?.firstOrNull() ?: 't', f.str("id").orEmpty(), floats(f.list("p").let(::JsonArray))) },
    e.items("sprites").map { s -> Sprite(s.str("kind").orEmpty(), (s.dbl("x") ?: 0.0).toFloat(), (s.dbl("y") ?: 0.0).toFloat(), (s.dbl("z") ?: 0.0).toFloat(), (s.dbl("h") ?: 6.0).toFloat(), (s.dbl("crown") ?: 2.5).toFloat()) },
    e.list("lines").map(::floats),
)

/** compute() の draw 部分。 */
class DrawModel(val busBody: List<FloatArray>, val busStripe: List<FloatArray>, val busPoint: FloatArray, val eye: FloatArray, val forward: FloatArray, val frustum: List<FloatArray>, val sunPoint: FloatArray, val occluders: Set<String>)

fun parseDraw(e: JsonElement?): DrawModel? {
    if (e == null) return null
    return DrawModel(e.list("busBody").map(::floats), e.list("busStripe").map(::floats), floats(e.list("busPoint").let(::JsonArray)), floats(e.list("eye").let(::JsonArray)), floats(e.list("forward").let(::JsonArray)),
        e.list("frustum").map(::floats), floats(e.list("sunPoint").let(::JsonArray)), e.list("occluders").map { it.text }.toSet())
}

private val C_BG = Color(0xFFE4E9E6); private val C_TERRAIN = Color(0xFF9AA88A); private val C_ROAD = Color(0xFF6F7784); private val C_FOOT = Color(0xFFA7ADAB); private val C_BRIDGE = Color(0xFF8B939F)
private val C_BUILDING = Color(0xFFC9C0AD); private val C_UNKNOWN = Color(0xFFDCD6C8); private val C_BLOCKED = Color(0xFFC4545F); private val C_TREE = Color(0xFF4F7A3F); private val C_TRUNK = Color(0xFF6B4F2A)
private val C_POLE = Color(0xFF8B8B8B); private val C_BUS = Color(0xFFE0662A); private val C_STRIPE = Color(0xFFF2EDE2); private val C_SUN = Color(0xFFFFD24A); private val C_RAY = Color(0xFFFFB020); private val C_HEAD = Color(0xFF2F6F8F)

private class Cam(val px: Float, val py: Float, val pz: Float, tx: Float, ty: Float, tz: Float, vFovDeg: Float, val w: Float, val h: Float) {
    val fx: Float; val fy: Float; val fz: Float; val rx: Float; val ry: Float; val rz: Float; val ux: Float; val uy: Float; val uz: Float
    val focal = (h / 2f) / tan(Math.toRadians(vFovDeg.toDouble() / 2)).toFloat()
    init {
        var a = tx - px; var b = ty - py; var c = tz - pz
        val l = sqrt(a * a + b * b + c * c).takeIf { it > 1e-6f } ?: 1f; a /= l; b /= l; c /= l
        fx = a; fy = b; fz = c
        // right = up × forward（上から見て北が上・東が右）
        var x = c; var z = -a; val rl = sqrt(x * x + z * z).takeIf { it > 1e-6f } ?: 1f; x /= rl; z /= rl
        rx = x; ry = 0f; rz = z
        ux = fy * rz - fz * ry; uy = fz * rx - fx * rz; uz = fx * ry - fy * rx
    }
    /** カメラ座標（x右, y上, z奥）へ。 */
    fun toCam(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val dx = x - px; val dy = y - py; val dz = z - pz
        out[o] = dx * rx + dy * ry + dz * rz; out[o + 1] = dx * ux + dy * uy + dz * uz; out[o + 2] = dx * fx + dy * fy + dz * fz
    }
    fun sx(cx: Float, cz: Float) = w / 2f + cx / cz * focal
    fun sy(cy: Float, cz: Float) = h / 2f - cy / cz * focal
    /** 画面上の点から地面（y=0）への交点。 */
    fun ground(sx: Float, sy: Float): Pair<Float, Float>? {
        val cx = (sx - w / 2f) / focal; val cy = -(sy - h / 2f) / focal
        val dx = rx * cx + ux * cy + fx; val dy = ry * cx + uy * cy + fy; val dz = rz * cx + uz * cy + fz
        if (dy > -1e-4f) return null
        val t = -py / dy
        return (px + dx * t) to (pz + dz * t)
    }
}

private const val NEAR = 0.6f
private class Item(val depth: Float, val draw: DrawScope.() -> Unit)

/** 面をカメラ座標にして手前の面で切り、画面上の多角形にする。 */
private fun project(cam: Cam, pts: FloatArray): Pair<Path, Float>? {
    val n = pts.size / 3
    if (n < 3) return null
    val c = FloatArray(n * 3)
    var depth = 0f; var front = 0
    for (i in 0 until n) { cam.toCam(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], c, i * 3); depth += c[i * 3 + 2]; if (c[i * 3 + 2] >= NEAR) front++ }
    if (front == 0) return null
    val path = Path(); var started = false
    fun emit(x: Float, y: Float, z: Float) { val sx = cam.sx(x, z); val sy = cam.sy(y, z); if (!started) { path.moveTo(sx, sy); started = true } else path.lineTo(sx, sy) }
    for (i in 0 until n) {
        val j = (i + 1) % n
        val az = c[i * 3 + 2]; val bz = c[j * 3 + 2]
        if (az >= NEAR) emit(c[i * 3], c[i * 3 + 1], az)
        if ((az >= NEAR) != (bz >= NEAR)) { val t = (NEAR - az) / (bz - az); emit(c[i * 3] + (c[j * 3] - c[i * 3]) * t, c[i * 3 + 1] + (c[j * 3 + 1] - c[i * 3 + 1]) * t, NEAR) }
    }
    path.close()
    return path to depth / n
}

private fun shade(color: Color, f: Face, lx: Float, ly: Float, lz: Float, day: Boolean): Color {
    val dot = kotlin.math.abs(f.nx * lx + f.ny * ly + f.nz * lz)
    val k = if (day) 0.62f + 0.38f * dot else 0.45f + 0.1f * dot
    return Color(color.red * k, color.green * k, color.blue * k, color.alpha)
}

/**
 * 地形・道路・建物・樹木・バス・画角・太陽の3D表示（Web: Scene3D.jsx の three.js 表示を Canvas の透視投影で描く）。
 * ドラッグで回転・ピンチで拡大縮小・地面をタップで立ち位置を移動。
 */
@Composable
fun Scene3D(scene: SceneModel?, draw: DrawModel?, layers: Map<String, Boolean>, viewMode: String, vFov: Float, sunAltitude: Double, modifier: Modifier = Modifier, onPickGround: (x: Float, z: Float) -> Unit) {
    var azimuth by remember { mutableFloatStateOf(205f) }; var elevation by remember { mutableFloatStateOf(42f) }; var distance by remember { mutableFloatStateOf(150f) }
    val mode by rememberUpdatedState(viewMode); val drawNow by rememberUpdatedState(draw); val fovNow by rememberUpdatedState(vFov); val pick by rememberUpdatedState(onPickGround)
    fun camera(w: Float, h: Float): Cam {
        val d = drawNow
        return when {
            mode == "eye" && d != null -> Cam(d.eye[0], d.eye[1], d.eye[2], d.eye[0] + d.forward[0] * 60, d.eye[1] + d.forward[1] * 60, d.eye[2] + d.forward[2] * 60, fovNow.coerceIn(4f, 120f), w, h)
            mode == "road" && d != null -> Cam(d.busPoint[0], d.busPoint[1] + 0.6f, d.busPoint[2], d.eye[0], d.eye[1], d.eye[2], 62f, w, h)
            mode == "top" -> Cam(0f, max(120f, distance * 1.5f), -1f, 0f, 0f, 0f, 55f, w, h)
            else -> { val el = Math.toRadians(elevation.toDouble()); val az = Math.toRadians(azimuth.toDouble()); Cam((sin(az) * cos(el) * distance).toFloat(), (sin(el) * distance).toFloat(), (cos(az) * cos(el) * distance).toFloat(), 0f, 2f, 0f, 50f, w, h) }
        }
    }
    Canvas(modifier.clipToBounds()
        .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> if (mode == "orbit" || mode == "top") { azimuth = (azimuth - pan.x * 0.35f) % 360f; elevation = (elevation + pan.y * 0.3f).coerceIn(6f, 88f); distance = (distance / zoom).coerceIn(25f, 520f) } } }
        .pointerInput(Unit) { detectTapGestures { p: Offset -> if (mode == "orbit" || mode == "top") camera(size.width.toFloat(), size.height.toFloat()).ground(p.x, p.y)?.let { (x, z) -> if (kotlin.math.abs(x) < 400 && kotlin.math.abs(z) < 400) pick(x, z) } } }) {
        drawRect(if (sunAltitude > 0) C_BG else Color(0xFF9CA6AE))
        val cam = camera(size.width, size.height)
        fun on(k: String) = layers[k] != false
        val day = sunAltitude > 0
        val sp = draw?.sunPoint; var lx = 0.3f; var ly = 0.8f; var lz = 0.5f
        if (sp != null && sp.size == 3) { val l = sqrt(sp[0] * sp[0] + sp[1] * sp[1] + sp[2] * sp[2]).takeIf { it > 1e-3f } ?: 1f; lx = sp[0] / l; ly = max(0.15f, sp[1] / l); lz = sp[2] / l }
        val ground = ArrayList<Item>(); val roads = ArrayList<Item>(); val solid = ArrayList<Item>()
        fun addFace(list: ArrayList<Item>, pts: FloatArray, color: Color, outline: Boolean = false) {
            val (path, depth) = project(cam, pts) ?: return
            list += Item(depth) { drawPath(path, color); if (outline) drawPath(path, Color.Black.copy(alpha = 0.18f), style = Stroke(1f)) }
        }
        scene?.faces?.forEach { f ->
            when (f.kind) {
                't' -> if (on("terrain")) addFace(ground, f.pts, shade(C_TERRAIN, f, lx, ly, lz, day), true)
                'r', 'f', 'g' -> if (on("roads")) addFace(roads, f.pts, shade(when (f.kind) { 'f' -> C_FOOT; 'g' -> C_BRIDGE; else -> C_ROAD }, f, lx, ly, lz, day))
                else -> if (on("buildings")) addFace(solid, f.pts, shade(if (draw?.occluders?.contains(f.id) == true) C_BLOCKED else if (f.kind == 'b') C_BUILDING else C_UNKNOWN.copy(alpha = 0.72f), f, lx, ly, lz, day), true)
            }
        }
        val tmp = FloatArray(6)
        fun line(a: FloatArray, ao: Int, b: FloatArray, bo: Int, color: Color, widthM: Float, minPx: Float, list: ArrayList<Item>) {
            cam.toCam(a[ao], a[ao + 1], a[ao + 2], tmp, 0); cam.toCam(b[bo], b[bo + 1], b[bo + 2], tmp, 3)
            var x0 = tmp[0]; var y0 = tmp[1]; var z0 = tmp[2]; var x1 = tmp[3]; var y1 = tmp[4]; var z1 = tmp[5]
            if (z0 < NEAR && z1 < NEAR) return
            if (z0 < NEAR) { val t = (NEAR - z0) / (z1 - z0); x0 += (x1 - x0) * t; y0 += (y1 - y0) * t; z0 = NEAR }
            if (z1 < NEAR) { val t = (NEAR - z1) / (z0 - z1); x1 += (x0 - x1) * t; y1 += (y0 - y1) * t; z1 = NEAR }
            val s = Offset(cam.sx(x0, z0), cam.sy(y0, z0)); val e = Offset(cam.sx(x1, z1), cam.sy(y1, z1)); val zm = (z0 + z1) / 2
            list += Item(zm) { drawLine(color, s, e, max(minPx, widthM * cam.focal / zm)) }
        }
        fun ball(x: Float, y: Float, z: Float, radius: Float, color: Color, list: ArrayList<Item>, scaleY: Float = 1f) {
            cam.toCam(x, y, z, tmp, 0); if (tmp[2] < NEAR) return
            val c = Offset(cam.sx(tmp[0], tmp[2]), cam.sy(tmp[1], tmp[2])); val r = max(1.5f, radius * cam.focal / tmp[2]); val depth = tmp[2]
            list += Item(depth) { drawOval(color, Offset(c.x - r, c.y - r * scaleY), androidx.compose.ui.geometry.Size(r * 2, r * 2 * scaleY)) }
        }
        if (on("obstacles") && scene != null) {
            scene.lines.forEach { l -> if (l.size >= 6) line(l, 0, l, 3, Color(0xFF8A7F6D), 0.15f, 1.2f, solid) }
            scene.sprites.forEach { s ->
                if (s.kind == "tree") {
                    val trunkH = minOf(3f, s.h * 0.4f)
                    line(floatArrayOf(s.x, s.y, s.z), 0, floatArrayOf(s.x, s.y + trunkH, s.z), 0, C_TRUNK, 0.4f, 1.5f, solid)
                    ball(s.x, s.y + max(trunkH, s.h - s.crown * 0.8f), s.z, s.crown, if (day) C_TREE else C_TREE.copy(red = C_TREE.red * 0.6f, green = C_TREE.green * 0.6f, blue = C_TREE.blue * 0.6f), solid, 1.15f)
                } else {
                    line(floatArrayOf(s.x, s.y, s.z), 0, floatArrayOf(s.x, s.y + s.h, s.z), 0, C_POLE, 0.25f, 1.5f, solid)
                    if (s.kind == "lamp" || s.kind == "signal") ball(s.x, s.y + s.h - 0.4f, s.z, if (s.kind == "signal") 0.6f else 0.4f, if (s.kind == "signal") Color(0xFF2F6F4F) else Color(0xFF4A4A4A), solid)
                }
            }
        }
        val overlay = ArrayList<Item>()
        if (draw != null) {
            if (on("bus")) {
                draw.busBody.forEach { addFace(solid, it, shade(C_BUS, Face('x', "", it), lx, ly, lz, day), true) }
                draw.busStripe.forEach { val (path, depth) = project(cam, it) ?: return@forEach; solid += Item(depth - 0.05f) { drawPath(path, C_STRIPE) } }
            }
            if (on("frustum")) {
                if (mode != "eye") ball(draw.eye[0], draw.eye[1], draw.eye[2], 0.45f, C_HEAD, solid)
                draw.frustum.forEachIndexed { i, c -> line(draw.eye, 0, c, 0, C_RAY, 0f, 2f, overlay); line(c, 0, draw.frustum[(i + 1) % draw.frustum.size], 0, C_RAY, 0f, 2f, overlay) }
            }
            if (on("sun") && draw.sunPoint.size == 3) {
                ball(draw.sunPoint[0], draw.sunPoint[1], draw.sunPoint[2], 7f, C_SUN, overlay)
                line(draw.sunPoint, 0, floatArrayOf(draw.sunPoint[0] * 0.25f, draw.sunPoint[1] * 0.25f, draw.sunPoint[2] * 0.25f), 0, C_SUN.copy(alpha = 0.5f), 0f, 2f, overlay)
            }
        }
        ground.sortByDescending { it.depth }; roads.sortByDescending { it.depth }; solid.sortByDescending { it.depth }
        ground.forEach { it.draw(this) }; roads.forEach { it.draw(this) }; solid.forEach { it.draw(this) }; overlay.forEach { it.draw(this) }
    }
}
