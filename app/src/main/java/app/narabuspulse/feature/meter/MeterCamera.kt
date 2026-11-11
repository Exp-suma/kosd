package app.narabuspulse.feature.meter

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.narabuspulse.ui.kit.SmallButton
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 1 コマの輝度統計（Web: lib/meter/analyze.js の analyzeFrame と同じ項目）。 */
data class FrameStats(
    val bins: IntArray, val mean255: Double, val meanRel: Double, val center255: Double, val centerRel: Double, val spot255: Double, val spotRel: Double,
    val rel: Double, val luma255: Double, val highlightRatio: Double, val shadowRatio: Double, val contrastStops: Double, val clipped: Boolean, val crushed: Boolean,
)

/** カメラが実際に適用している露出（取得できた値のみ）。 */
data class CameraExposure(val exposureTime: Double?, val iso: Double?, val aperture: Double?, val aeLocked: Boolean, val awbMode: String, val afMode: String)

private val LIN = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() }
private fun log2(v: Double) = ln(v) / ln(2.0)

/**
 * 背面カメラの映像を端末内で解析して輝度統計を返す（映像・画像は外部に送信しない）。
 * Web 版はブラウザのカメラ API を使うが、ネイティブ版は Camera2 で ISO・シャッター速度・絞り値を実測する。
 */
@Stable
class MeterCamera(private val context: Context) {
    var status by mutableStateOf("idle") // idle | starting | live | error
        private set
    var error by mutableStateOf("")
        private set
    var stats by mutableStateOf<FrameStats?>(null)
        private set
    var exposure by mutableStateOf<CameraExposure?>(null)
        private set
    var locked by mutableStateOf(false)
        private set
    var lockMessage by mutableStateOf("")
        private set
    var metering by mutableStateOf("center")
    var spot by mutableStateOf(Offset(0.5f, 0.5f))
    var facingBack by mutableStateOf(true)
    /** プレビューの縦横比（幅/高さ。縦持ちで表示する向き）。 */
    var previewRatio by mutableStateOf(3f / 4f)
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var builder: CaptureRequest.Builder? = null
    private var texture: SurfaceTexture? = null
    private var sensorOrientation = 90
    private var aeLockAvailable = false
    private var lastFrame = 0L
    private var lastMeta = 0L

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun attach(surface: SurfaceTexture?) { texture = surface; if (surface != null && status == "starting" && device == null) open() }

    fun start() {
        if (status == "live" || status == "starting") return
        if (!hasPermission()) { fail("カメラの使用が許可されていません。端末の設定でこのアプリのカメラを許可してください。"); return }
        status = "starting"; error = ""
        if (texture != null) open()
    }

    fun stop() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        thread?.quitSafely(); thread = null; handler = null
        status = "idle"; stats = null; locked = false; lockMessage = ""
    }

    private fun fail(message: String) { stop(); status = "error"; error = message }

    @SuppressLint("MissingPermission")
    private fun open() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val wanted = if (facingBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted } ?: manager.cameraIdList.firstOrNull()
            if (id == null) { fail("利用できるカメラが見つかりませんでした。背面カメラのある端末でお試しください。"); return }
            val ch = manager.getCameraCharacteristics(id)
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            aeLockAvailable = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val preview = previewSizes.filter { it.width * 3 == it.height * 4 && it.width <= 1440 }.maxByOrNull { it.width } ?: previewSizes.filter { it.width <= 1440 }.maxByOrNull { it.width } ?: Size(640, 480)
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val analysis = yuvSizes.filter { it.width >= 176 }.minByOrNull { it.width * it.height } ?: Size(320, 240)
            previewRatio = preview.height.toFloat() / preview.width

            val t = HandlerThread("meter-camera").also { it.start() }
            thread = t; val h = Handler(t.looper); handler = h
            val r = ImageReader.newInstance(analysis.width, analysis.height, ImageFormat.YUV_420_888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                val image = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrame >= 90) { lastFrame = now; stats = analyze(image.planes[0].buffer, image.planes[0].rowStride, image.width, image.height) }
                } catch (_: Throwable) { } finally { image.close() }
            }, h)

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val st = texture ?: run { fail("カメラの映像を表示できませんでした。"); return }
                    st.setDefaultBufferSize(preview.width, preview.height)
                    val surface = Surface(st)
                    try {
                        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(surface); b.addTarget(r.surface)
                        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        builder = b
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(surface, r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { session = s; repeat(); status = "live" }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail("カメラを起動できませんでした。") }
                        }, h)
                    } catch (e: Throwable) { fail("カメラを起動できませんでした。") }
                }
                override fun onDisconnected(camera: CameraDevice) { fail("カメラが他のアプリで使用中です。他のカメラアプリを閉じてから再試行してください。") }
                override fun onError(camera: CameraDevice, code: Int) {
                    fail(if (code == ERROR_CAMERA_IN_USE || code == ERROR_MAX_CAMERAS_IN_USE) "カメラが他のアプリで使用中です。他のカメラアプリを閉じてから再試行してください。" else "カメラを起動できませんでした。")
                }
            }, h)
        } catch (e: SecurityException) {
            fail("カメラの使用が許可されていません。端末の設定でこのアプリのカメラを許可してください。")
        } catch (e: Throwable) {
            fail(e.message ?: "カメラを起動できませんでした。")
        }
    }

    private fun repeat() {
        val s = session ?: return; val b = builder ?: return
        runCatching {
            s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastMeta < 500) return
                    lastMeta = now
                    exposure = CameraExposure(
                        exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.takeIf { it > 0 }?.let { it / 1e9 },
                        iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.takeIf { it > 0 }?.toDouble(),
                        aperture = result.get(CaptureResult.LENS_APERTURE)?.takeIf { it > 0 }?.toDouble(),
                        aeLocked = result.get(CaptureResult.CONTROL_AE_LOCK) == true,
                        awbMode = when (result.get(CaptureResult.CONTROL_AWB_MODE)) { CameraMetadata.CONTROL_AWB_MODE_AUTO -> "自動"; null -> ""; CameraMetadata.CONTROL_AWB_MODE_OFF -> "手動"; else -> "プリセット" },
                        afMode = when (result.get(CaptureResult.CONTROL_AF_MODE)) { CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "連続"; CameraMetadata.CONTROL_AF_MODE_OFF -> "固定"; null -> ""; else -> "自動" },
                    )
                }
            }, handler)
        }.onFailure { fail("カメラの映像を取得できませんでした。") }
    }

    /** 自動露出を固定して、画面の明るさ変化と被写体の明るさを切り離す。 */
    fun lockExposure() {
        if (!aeLockAvailable) { locked = false; lockMessage = "この端末は露出の固定に対応していません（自動露出の値で補正します）"; return }
        builder?.set(CaptureRequest.CONTROL_AE_LOCK, true); builder?.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        repeat(); locked = true; lockMessage = ""
    }

    fun unlockExposure() {
        builder?.set(CaptureRequest.CONTROL_AE_LOCK, false); builder?.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        repeat(); locked = false; lockMessage = ""
    }

    /** 表示上のタップ位置（0〜1）を、センサーの向きの画像座標に直す。 */
    private fun spotInSensor(): Pair<Float, Float> {
        val x = spot.x; val y = spot.y
        return when (sensorOrientation) { 90 -> y to (1 - x); 270 -> (1 - y) to x; 180 -> (1 - x) to (1 - y); else -> x to y }
    }

    private fun analyze(buf: java.nio.ByteBuffer, rowStride: Int, width: Int, height: Int): FrameStats {
        val step = max(1, width / 176)
        val bins = IntArray(64)
        var total = 0; var sum = 0.0; var sumLin = 0.0; var hi = 0; var lo = 0
        val cx0 = width * 0.38f; val cx1 = width * 0.62f; val cy0 = height * 0.38f; val cy1 = height * 0.62f
        var c55 = 0.0; var cLin = 0.0; var cN = 0
        val radius = min(width, height) * 0.11f; val r2 = radius * radius
        val (sxn, syn) = spotInSensor(); val sx = sxn * width; val sy = syn * height
        val isSpot = metering == "spot"
        var s55 = 0.0; var sLin = 0.0; var sN = 0
        val limit = buf.limit()
        var y = 0
        while (y < height) {
            val row = y * rowStride
            var x = 0
            while (x < width) {
                val idx = row + x
                if (idx >= limit) break
                val v = buf.get(idx).toInt() and 0xFF
                val lin = LIN[v]
                sum += v; sumLin += lin; bins[v shr 2]++; total++
                if (v >= 250) hi++
                if (v <= 6) lo++
                if (x >= cx0 && x <= cx1 && y >= cy0 && y <= cy1) { c55 += v; cLin += lin; cN++ }
                if (isSpot) { val dx = x - sx; val dy = y - sy; if (dx * dx + dy * dy <= r2) { s55 += v; sLin += lin; sN++ } }
                x += step
            }
            y += step
        }
        val mean255 = if (total > 0) sum / total else 0.0
        val meanRel = if (total > 0) sumLin / total else 0.0
        val center255 = if (cN > 0) c55 / cN else mean255
        val centerRel = if (cN > 0) cLin / cN else meanRel
        val spot255 = if (sN > 0) s55 / sN else center255
        val spotRel = if (sN > 0) sLin / sN else centerRel
        var acc = 0; var p5 = 0; var p95 = 255; var found = false
        for (i in bins.indices) {
            acc += bins[i]
            val ratio = if (total > 0) acc.toDouble() / total else 0.0
            if (!found && ratio >= 0.05) { p5 = i * 4; found = true }
            if (ratio >= 0.95) { p95 = i * 4; break }
        }
        val hr = if (total > 0) hi.toDouble() / total else 0.0
        val sr = if (total > 0) lo.toDouble() / total else 0.0
        return FrameStats(bins, mean255, meanRel, center255, centerRel, spot255, spotRel,
            rel = when (metering) { "average" -> meanRel; "spot" -> spotRel; else -> centerRel },
            luma255 = when (metering) { "average" -> mean255; "spot" -> spot255; else -> center255 },
            highlightRatio = hr, shadowRatio = sr, contrastStops = log2((LIN[p95] + 0.0005) / (LIN[p5] + 0.0005)), clipped = hr > 0.02, crushed = sr > 0.02)
    }
}

@Composable
fun rememberMeterCamera(): MeterCamera {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val camera = remember { MeterCamera(context.applicationContext) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) camera.stop() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); camera.stop() }
    }
    return camera
}

/** カメラ映像と測光位置（Web: MeterVideo.jsx）。タップした位置でスポット測光する。 */
@Composable
fun MeterPreview(camera: MeterCamera, onTap: (Offset) -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera.start() }
    val live = camera.status == "live"
    val begin = { if (camera.hasPermission()) camera.start() else permission.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxWidth().aspectRatio(camera.previewRatio).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        AndroidView(factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { camera.attach(s) }
                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { camera.attach(null); camera.stop(); return true }
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                }
            }
        }, modifier = Modifier.fillMaxSize())
        if (live) {
            val marker = Color(0xFFD94F2B)
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { p -> onTap(Offset((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))) } }) {
                drawLine(Color.White.copy(alpha = 0.15f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1f)
                drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                if (camera.metering != "average") {
                    val isSpot = camera.metering == "spot"
                    val c = if (isSpot) Offset(camera.spot.x * size.width, camera.spot.y * size.height) else Offset(size.width / 2, size.height / 2)
                    drawCircle(marker, radius = size.width * (if (isSpot) 0.09f else 0.13f), center = c, style = Stroke(width = 4f))
                }
            }
            Box(Modifier.align(Alignment.BottomStart).padding(10.dp)) { Text("● 計測中", color = Color.White, fontSize = 11.sp) }
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { SmallButton("停止", { camera.stop() }) }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                when (camera.status) {
                    "starting" -> Text("カメラを起動しています…", color = Color.White, fontSize = 12.sp)
                    "error" -> { Text(camera.error, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center); SmallButton("もう一度試す", { begin() }, modifier = Modifier.padding(top = 8.dp)) }
                    else -> {
                        Text("背面カメラを起動して周囲の明るさを測ります。\n映像は端末内で解析され、外部には送信しません。", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center)
                        SmallButton("カメラを起動", { begin() }, primary = true, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}
