package com.example.d750bridge.ai

import android.graphics.Bitmap
import com.example.d750bridge.AppLog
import com.example.d750bridge.track.TrackState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 被写体の種類。defaultPointY は「検出枠の上端(0)〜下端(1)のどこにAF点を置くか」の初期値。 */
enum class Subject(val label: String, val defaultPointY: Float) {
    PERSON("人", 0.12f),      // 顔が見つからないときの頭部推定位置（人物枠の上から12%）
    TRAIN("電車", 0.45f),
    BUS("バス", 0.55f),
    LANDSCAPE("風景", 0.5f)
}

/** 夜間補正。AUTO はライブビューの平均輝度が暗いときだけ有効。 */
enum class NightMode(val label: String) { AUTO("自動"), ON("常にON"), OFF("OFF") }

object AfConst {
    /** 平均輝度(0..255)がこれ未満なら「夜・暗所」とみなす（AUTO時）。 */
    const val NIGHT_LUMA = 65f
    /** 夜間、検出用に明るさをこの平均輝度へ持ち上げる（ゲイン1.5〜5倍）。 */
    const val NIGHT_TARGET_LUMA = 110f

    fun isNight(mode: NightMode, meanLuma: Float): Boolean = when (mode) {
        NightMode.ON -> true
        NightMode.OFF -> false
        NightMode.AUTO -> meanLuma < NIGHT_LUMA
    }
}

/** AI AF支援の設定。値はすべて画面から変更できる。 */
data class AiConfig(
    val subject: Subject = Subject.BUS,
    val detectEnabled: Boolean = false,
    val autoAf: Boolean = false,             // true: 検出位置が動く/大きさが変わったら自動でAFを送る
    val includeTruckCar: Boolean = false,    // バス選択時のみ: false なら "bus" のみ
    val nightMode: NightMode = NightMode.AUTO,
    val pinMode: Boolean = false,            // 置きピン: true の間は被写体が来てもAFを追従させない
    val pinLockMf: Boolean = true,           // 置きピン設定後にカメラをMFへ切り替えて、撮影時のAFも止める
    val minScore: Float = 0.40f,
    val pointYRatio: Float = Subject.BUS.defaultPointY,
    val leadMs: Int = 150,                   // 映像の遅延ぶん、動きの先を狙う
    val minIntervalMs: Int = 1000,           // 自動AFの最小間隔
    val moveThreshold: Float = 0.06f,        // 前回のAF点からこれ以上動いたら再AF（画像幅比）
    val areaThreshold: Float = 0.25f         // 枠の面積が前回AF時よりこの割合以上変わったら再AF（近づく/遠ざかる）
)

/** 検出結果。座標は画像に対する相対値(0..1)。 */
data class Det(val label: String, val score: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val cx: Float get() = (x0 + x1) / 2f
    val cy: Float get() = (y0 + y1) / 2f
    val area: Float get() = (x1 - x0) * (y1 - y0)
}

data class Region(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** AF点の候補。region は夜間に「この範囲の中で一番コントラストの高い点」を探すための範囲。 */
data class Anchor(val x: Float, val y: Float, val note: String, val region: Region)

/** AF点の計画。nx, ny は画像に対する相対位置(0..1)。vx, vy は1秒あたりの移動量。 */
data class AiPlan(
    val nx: Float, val ny: Float, val det: Det, val vx: Float, val vy: Float, val continued: Boolean,
    val note: String, val region: Region,
    val state: TrackState = TrackState.TRACKING, val trackId: Int = -1
)

/**
 * 輝度グリッド（ライブビューを縮小したもの）。
 *  - mean: 平均輝度。夜間判定に使う。
 *  - contrastPoints: 指定範囲の中でエッジ（輝度変化）が集中している点を探す。
 *    コントラストAFは「模様のない暗い面」では迷うので、夜はエッジのある所（ヘッドライト縁・看板・顔の輪郭など）に置く。
 */
class LumaGrid private constructor(val w: Int, val h: Int, private val y: IntArray) {
    val mean: Float = if (y.isEmpty()) 0f else y.average().toFloat()

    fun contrastPoints(rx0: Float, ry0: Float, rx1: Float, ry1: Float, n: Int): List<Pair<Float, Float>> {
        if (w < 8 || h < 8) return emptyList()
        val x0 = (rx0 * w).toInt().coerceIn(1, w - 3)
        val x1 = (rx1 * w).toInt().coerceIn(x0 + 1, w - 2)
        val y0 = (ry0 * h).toInt().coerceIn(1, h - 3)
        val y1 = (ry1 * h).toInt().coerceIn(y0 + 1, h - 2)
        val ew = x1 - x0 + 1
        val eh = y1 - y0 + 1
        // 勾配エネルギー（ノイズ分を差し引く）
        val e = IntArray(ew * eh)
        for (yy in y0..y1) for (xx in x0..x1) {
            val i = yy * w + xx
            val g = abs(y[i + 1] - y[i - 1]) + abs(y[i + w] - y[i - w])
            e[(yy - y0) * ew + (xx - x0)] = max(0, g - NOISE_FLOOR)
        }
        // 積分画像で (2*WIN+1)^2 の窓合計を高速計算
        val ii = LongArray((ew + 1) * (eh + 1))
        for (yy in 0 until eh) {
            var row = 0L
            for (xx in 0 until ew) {
                row += e[yy * ew + xx]
                ii[(yy + 1) * (ew + 1) + (xx + 1)] = ii[yy * (ew + 1) + (xx + 1)] + row
            }
        }
        fun boxSum(cx: Int, cy: Int): Long {
            val ax = max(0, cx - WIN); val bx = min(ew - 1, cx + WIN)
            val ay = max(0, cy - WIN); val by = min(eh - 1, cy + WIN)
            return ii[(by + 1) * (ew + 1) + (bx + 1)] - ii[ay * (ew + 1) + (bx + 1)] -
                ii[(by + 1) * (ew + 1) + ax] + ii[ay * (ew + 1) + ax]
        }
        val taken = BooleanArray(ew * eh)
        val out = ArrayList<Pair<Float, Float>>()
        repeat(n) {
            var best = -1L; var bx = -1; var by = -1
            for (cy in 0 until eh) for (cx in 0 until ew) {
                if (taken[cy * ew + cx]) continue
                val s = boxSum(cx, cy)
                if (s > best) { best = s; bx = cx; by = cy }
            }
            if (bx < 0 || best < MIN_ENERGY) return out
            out.add(((x0 + bx + 0.5f) / w) to ((y0 + by + 0.5f) / h))
            val r = WIN * 2 + 1
            for (cy in max(0, by - r)..min(eh - 1, by + r)) for (cx in max(0, bx - r)..min(ew - 1, bx + r)) taken[cy * ew + cx] = true
        }
        return out
    }

    companion object {
        private const val NOISE_FLOOR = 8
        private const val WIN = 3
        private const val MIN_ENERGY = 60L

        fun of(bmp: Bitmap, targetW: Int = 160): LumaGrid {
            val th = max(8, (bmp.height * targetW / bmp.width.toFloat()).toInt())
            val small = Bitmap.createScaledBitmap(bmp, targetW, th, true)
            val px = IntArray(targetW * th)
            small.getPixels(px, 0, targetW, 0, 0, targetW, th)
            if (small !== bmp) small.recycle()
            val ys = IntArray(px.size) {
                val p = px[it]
                (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8
            }
            return LumaGrid(targetW, th, ys)
        }
    }
}

/** バス・電車のAF点。電車が横方向に動いているときは進行方向の先頭側へ寄せる（正面から来る場合は中央）。 */
fun vehicleAnchor(d: Det, subject: Subject, cfg: AiConfig, vx: Float): Anchor {
    val w = d.x1 - d.x0
    val h = d.y1 - d.y0
    val y = d.y0 + cfg.pointYRatio * h
    val movingSide = subject == Subject.TRAIN && abs(vx) > 0.05f
    if (movingSide) {
        return if (vx > 0) {
            Anchor(d.x1 - 0.12f * w, y, "先頭側(右)", Region(d.x1 - 0.30f * w, d.y0, d.x1, d.y1))
        } else {
            Anchor(d.x0 + 0.12f * w, y, "先頭側(左)", Region(d.x0, d.y0, d.x0 + 0.30f * w, d.y1))
        }
    }
    return Anchor(d.cx, y, "車体中央", Region(d.x0, d.y0 + 0.10f * h, d.x1, d.y1 - 0.05f * h))
}

/** 風景: 検出は使わず、画面中央寄り（空を避けた範囲）でコントラストが最も高い点を選ぶ。 */
fun landscapePlan(grid: LumaGrid): AiPlan {
    val region = Region(0.15f, 0.25f, 0.85f, 0.85f)
    val p = grid.contrastPoints(region.x0, region.y0, region.x1, region.y1, 1).firstOrNull()
    val nx = p?.first ?: 0.5f
    val ny = p?.second ?: 0.55f
    val d = Det("風景", 1f, region.x0, region.y0, region.x1, region.y1)
    return AiPlan(nx, ny, d, 0f, 0f, false, if (p != null) "高コントラスト点" else "中央(コントラスト不足)", region)
}

/**
 * 検出結果から「どこにAFを合わせるか」を決める。
 *  1. 目標の選択: 直前の目標に近い検出を優先（追従）。見失う/遠すぎる場合は最も大きい（=近い）もの。
 *  2. AF点: anchorOf（被写体ごとの決め方）が返す位置。
 *  3. 先読み: 中心の移動速度（指数平滑）× leadMs だけ進行方向にずらす。
 */
class TargetTracker {
    private var last: Det? = null
    private var lastT = 0L
    private var vx = 0f
    private var vy = 0f

    fun reset() { last = null; lastT = 0L; vx = 0f; vy = 0f }

    fun update(dets: List<Det>, tNanos: Long, cfg: AiConfig, anchorOf: (Det, Float) -> Anchor): AiPlan? {
        val prev = last
        val fresh = prev != null && (tNanos - lastT) < 700_000_000L
        if (dets.isEmpty()) {
            if (!fresh) reset()
            return null
        }
        var continued = false
        val pick: Det
        if (fresh && prev != null) {
            val near = dets.minByOrNull { hypot(it.cx - prev.cx, it.cy - prev.cy) }!!
            if (hypot(near.cx - prev.cx, near.cy - prev.cy) < 0.25f) { pick = near; continued = true }
            else pick = dets.maxByOrNull { it.area }!!
        } else {
            pick = dets.maxByOrNull { it.area }!!
        }
        if (continued && prev != null) {
            val dt = ((tNanos - lastT) / 1e9f).coerceAtLeast(0.001f)
            vx = (0.6f * vx + 0.4f * ((pick.cx - prev.cx) / dt)).coerceIn(-1.5f, 1.5f)
            vy = (0.6f * vy + 0.4f * ((pick.cy - prev.cy) / dt)).coerceIn(-1.5f, 1.5f)
        } else {
            vx = 0f; vy = 0f
        }
        last = pick; lastT = tNanos
        val a = anchorOf(pick, vx)
        val lead = cfg.leadMs / 1000f
        val px = (a.x + (if (abs(vx) > 0.02f) vx * lead else 0f)).coerceIn(0.02f, 0.98f)
        val py = (a.y + (if (abs(vy) > 0.02f) vy * lead else 0f)).coerceIn(0.02f, 0.98f)
        return AiPlan(px, py, pick, vx, vy, continued, a.note, a.region)
    }
}
