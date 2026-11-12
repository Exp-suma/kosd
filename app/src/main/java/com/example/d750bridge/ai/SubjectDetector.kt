package com.example.d750bridge.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.d750bridge.AppLog
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable
import kotlin.math.min

// Android版の検出器（MediaPipe）。PC版は desktop/ 側に同じAPIのONNX Runtime実装がある。

/**
 * 端末内で動く検出器。
 *  - 物体検出: MediaPipe Object Detector + EfficientDet-Lite0（COCO 80クラス: person / train / bus など）
 *  - 顔検出  : MediaPipe Face Detector（BlazeFace short range）
 * モデルファイルは assets に必要。GitHub Actions のビルドで取得する。
 * 無い場合は error に理由が入り、そのモデルを使う検出は空を返す（動いているふりはしない）。
 */
class SubjectDetector(context: Context) : Closeable {
    private var objDet: ObjectDetector? = null
    private var faceDet: FaceDetector? = null
    val objError: String?
    val faceError: String?

    init {
        var oe: String? = null
        var fe: String? = null
        try {
            context.assets.open(OBJ_MODEL).close()
            val base = BaseOptions.builder().setModelAssetPath(OBJ_MODEL).build()
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(12)
                .setScoreThreshold(0.15f)
                .build()
            objDet = ObjectDetector.createFromOptions(context, opts)
        } catch (e: Throwable) {
            oe = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.e("物体検出モデルを初期化できません: $oe")
        }
        try {
            context.assets.open(FACE_MODEL).close()
            val base = BaseOptions.builder().setModelAssetPath(FACE_MODEL).build()
            val opts = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.25f)
                .build()
            faceDet = FaceDetector.createFromOptions(context, opts)
        } catch (e: Throwable) {
            fe = "${e.javaClass.simpleName}: ${e.message}"
            AppLog.e("顔検出モデルを初期化できません: $fe")
        }
        objError = oe
        faceError = fe
    }

    /** bmp は ARGB_8888 であること。 */
    fun detectObjects(bmp: Bitmap, labels: Set<String>, minScore: Float): List<Det> {
        val d = objDet ?: return emptyList()
        val result = d.detect(BitmapImageBuilder(bmp).build())
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val out = ArrayList<Det>()
        for (det in result.detections()) {
            val cat = det.categories().firstOrNull() ?: continue
            val name = cat.categoryName() ?: continue
            if (name !in labels || cat.score() < minScore) continue
            val b = det.boundingBox()
            out.add(
                Det(
                    name, cat.score(),
                    (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                    (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f)
                )
            )
        }
        return out
    }

    /** 画面全体から顔を探す（人物枠が取れない近接・暗所用）。 */
    fun detectFaces(bmp: Bitmap, minScore: Float): List<Det> {
        val d = faceDet ?: return emptyList()
        val result = d.detect(BitmapImageBuilder(bmp).build())
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val out = ArrayList<Det>()
        for (det in result.detections()) {
            val score = det.categories().firstOrNull()?.score() ?: 0f
            if (score < minScore) continue
            val b = det.boundingBox()
            out.add(
                Det(
                    "face", score,
                    (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                    (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f)
                )
            )
        }
        return out
    }

    /**
     * 人物枠の上部（頭〜肩）だけを切り出して拡大し、顔検出にかける。
     * 画面全体では小さすぎて顔検出が効かない遠めの人物でも、顔を拾いやすくするため。
     */
    fun faceInPerson(bmp: Bitmap, p: Det, minScore: Float): Det? {
        val fd = faceDet ?: return null
        val w = bmp.width; val h = bmp.height
        val bw = (p.x1 - p.x0) * w; val bh = (p.y1 - p.y0) * h
        val l = (p.x0 * w - 0.1f * bw).toInt().coerceIn(0, w - 2)
        val t = (p.y0 * h - 0.05f * bh).toInt().coerceIn(0, h - 2)
        val r = (p.x1 * w + 0.1f * bw).toInt().coerceIn(l + 1, w)
        val b = (p.y0 * h + 0.40f * bh).toInt().coerceIn(t + 1, h)
        val cw = r - l; val ch = b - t
        if (cw < 12 || ch < 12) return null
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            crop = Bitmap.createBitmap(bmp, l, t, cw, ch)
            val s = (MIN_FACE_CROP / min(cw, ch).toFloat()).coerceIn(1f, 4f)
            scaled = if (s > 1.01f) Bitmap.createScaledBitmap(crop, (cw * s).toInt(), (ch * s).toInt(), true) else crop
            val result = fd.detect(BitmapImageBuilder(scaled).build())
            var best: Det? = null
            for (det in result.detections()) {
                val score = det.categories().firstOrNull()?.score() ?: 0f
                if (score < minScore) continue
                if (best == null || score > best.score) {
                    val bb = det.boundingBox()
                    best = Det(
                        "face", score,
                        ((l + bb.left / s) / w).coerceIn(0f, 1f), ((t + bb.top / s) / h).coerceIn(0f, 1f),
                        ((l + bb.right / s) / w).coerceIn(0f, 1f), ((t + bb.bottom / s) / h).coerceIn(0f, 1f)
                    )
                }
            }
            return best
        } finally {
            if (scaled != null && scaled !== crop && scaled !== bmp) scaled.recycle()
            if (crop != null && crop !== bmp) crop.recycle()
        }
    }

    /** 人物のAF点: 顔が見つかれば顔の中心（目〜鼻のあたりを狙って少し上）。見つからなければ人物枠の頭部位置で代用。 */
    fun personAnchor(src: Bitmap, d: Det, cfg: AiConfig, faceMinScore: Float): Anchor {
        if (d.label == "face") {
            return Anchor(d.cx, d.y0 + 0.45f * (d.y1 - d.y0), "顔", Region(d.x0, d.y0, d.x1, d.y1))
        }
        val f = faceInPerson(src, d, faceMinScore)
        if (f != null) {
            return Anchor(f.cx, f.y0 + 0.45f * (f.y1 - f.y0), "顔", Region(f.x0, f.y0, f.x1, f.y1))
        }
        val w = d.x1 - d.x0; val h = d.y1 - d.y0
        return Anchor(
            d.cx, d.y0 + cfg.pointYRatio * h, "頭部推定(顔は未検出)",
            Region(d.cx - 0.25f * w, d.y0, d.cx + 0.25f * w, d.y0 + 0.25f * h)
        )
    }

    /** 夜間の検出用に画像を明るくしたコピーを返す（表示・AF座標には影響しない）。 */
    fun brighten(src: Bitmap, gain: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix(
            floatArrayOf(
                gain, 0f, 0f, 0f, 0f,
                0f, gain, 0f, 0f, 0f,
                0f, 0f, gain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    override fun close() {
        try { objDet?.close() } catch (_: Throwable) {}
        try { faceDet?.close() } catch (_: Throwable) {}
        objDet = null
        faceDet = null
    }

    companion object {
        const val OBJ_MODEL = "efficientdet_lite0.tflite"
        const val FACE_MODEL = "blaze_face_short_range.tflite"
        private const val MIN_FACE_CROP = 320f
    }
}

