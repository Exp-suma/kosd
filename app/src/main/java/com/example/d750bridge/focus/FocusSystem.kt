package com.example.d750bridge.focus

import android.content.Context
import android.graphics.Bitmap
import com.example.d750bridge.ai.Region
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 局所シャープネス。score は相対値（明るさで正規化したTenengrad）。ex/ey は水平/垂直方向の勾配エネルギー。 */
data class Sharp(val score: Float, val ex: Float, val ey: Float, val luma: Float) {
    /** 片方向だけ極端に勾配が弱い → モーションブレの疑い（ピンぼけは両方向が同時に弱くなる）。 */
    val anisotropy: Float get() = if (min(ex, ey) <= 0f) 99f else max(ex, ey) / min(ex, ey)
}

/** 安定測定の結果。cv=フレーム間のばらつき（標準偏差/平均）。大きいほど不安定。 */
data class Meas(val s: Sharp, val cv: Float)

object FocusAnalyzer {
    private const val GRAD_FLOOR = 4f   // この未満の勾配はノイズとして無視

    /**
     * 指定領域のシャープネスを測る。2x2平均で縮小してから勾配を取る（高ISOノイズの影響を減らす）。
     * 領域が小さすぎる場合は中心を保ったまま最小サイズまで広げる。測れなければ null。
     */
    fun measure(bmp: Bitmap, region: Region): Sharp? {
        val w = bmp.width
        val h = bmp.height
        var l = (region.x0 * w).toInt(); var r = (region.x1 * w).toInt()
        var t = (region.y0 * h).toInt(); var b = (region.y1 * h).toInt()
        if (r - l < 64) { val c = (l + r) / 2; l = c - 32; r = c + 32 }
        if (b - t < 64) { val c = (t + b) / 2; t = c - 32; b = c + 32 }
        l = l.coerceAtLeast(0); t = t.coerceAtLeast(0)
        r = r.coerceAtMost(w); b = b.coerceAtMost(h)
        val cw = (r - l) and 1.inv()
        val ch = (b - t) and 1.inv()
        if (cw < 16 || ch < 16) return null
        val px = IntArray(cw * ch)
        bmp.getPixels(px, 0, cw, l, t, cw, ch)
        val dw = cw / 2
        val dh = ch / 2
        val y = FloatArray(dw * dh)
        var sumY = 0.0
        for (yy in 0 until dh) for (xx in 0 until dw) {
            var sum = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = px[(yy * 2 + dy) * cw + xx * 2 + dx]
                sum += (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8
            }
            val v = sum / 4f
            y[yy * dw + xx] = v
            sumY += v
        }
        var ex = 0.0
        var ey = 0.0
        for (yy in 1 until dh - 1) for (xx in 1 until dw - 1) {
            val i = yy * dw + xx
            val gx = y[i + 1] - y[i - 1]
            val gy = y[i + dw] - y[i - dw]
            if (abs(gx) > GRAD_FLOOR) ex += (gx * gx).toDouble()
            if (abs(gy) > GRAD_FLOOR) ey += (gy * gy).toDouble()
        }
        val n = ((dw - 2) * (dh - 2)).coerceAtLeast(1)
        val mean = sumY / (dw * dh)
        val norm = (mean + 16.0) * (mean + 16.0)
        val score = ((ex + ey) / n / norm * 1000.0).toFloat()
        return Sharp(score, (ex / n).toFloat(), (ey / n).toFloat(), mean.toFloat())
    }
}

/**
 * 焦点距離ごとの校正データ（SharedPreferences）。校正点は A025 の 70/85/100/135/150/180/200mm。
 * 校正値は他の焦点距離へ流用しない（bucketOf は校正点から±8mm以内のときだけ該当バケットを返す）。
 */
class FocusProfileStore(context: Context) {
    private val sp = context.getSharedPreferences("focus_profiles", Context.MODE_PRIVATE)

    /** 焦点距離 → 校正点。±8mm以内でなければ null（未校正扱い）。 */
    fun bucketOf(mm: Double?): Int? {
        if (mm == null) return null
        val b = BUCKETS.minByOrNull { abs(it - mm) } ?: return null
        return if (abs(b - mm) <= 8.0) b else null
    }

    /** 微調整ステップ。標準=5倍、大=20倍。未校正の既定値は仮の値（実機で校正すること）。 */
    fun fine(bucket: Int?): Int = sp.getInt("fine_${bucket ?: 0}", DEF_FINE)
    fun setFine(bucket: Int?, v: Int, calibrated: Boolean) {
        sp.edit().putInt("fine_${bucket ?: 0}", v).putBoolean("cal_${bucket ?: 0}", calibrated).apply()
    }
    fun calibrated(bucket: Int?): Boolean = sp.getBoolean("cal_${bucket ?: 0}", false)

    /** Near方向のMfDriveフラグ(1 or 2)。どちらがNearかは実機で確認するまで仮定値。 */
    var nearFlag: Int
        get() = sp.getInt("near_flag", 1)
        set(v) { sp.edit().putInt("near_flag", if (v == 2) 2 else 1).apply() }
    var dirConfirmed: Boolean
        get() = sp.getBoolean("dir_confirmed", false)
        set(v) { sp.edit().putBoolean("dir_confirmed", v).apply() }

    fun lastResult(bucket: Int?): String = sp.getString("last_${bucket ?: 0}", "") ?: ""
    fun setLastResult(bucket: Int?, text: String) { sp.edit().putString("last_${bucket ?: 0}", text).apply() }

    companion object {
        val BUCKETS = listOf(70, 85, 100, 135, 150, 180, 200)
        const val DEF_FINE = 20
    }
}

/** 画面から変更できるピント補正の設定。 */
data class FocusCfg(
    val analysisOn: Boolean = true,        // ライブビューのシャープネス表示
    val verdictOn: Boolean = true,         // 前ピン/後ピンの表記（方向確認済みのときだけ有効）
    val autoCorrectOn: Boolean = false,    // AF後に自動でピント補正を実行
    val sensitivity: Float = 0.08f,        // 「改善/悪化」とみなすシャープネスの相対変化
    val settleMs: Int = 250,               // 駆動後の安定待ち
    val maxIter: Int = 8,                  // 補正の最大ステップ回数
    val maxTotal: Int = 1000               // 補正開始位置からの移動量上限（ステップ数）
)

data class FocusUi(
    val running: Boolean = false,
    val status: String = "待機",
    val focalMm: Double? = null,
    val bucket: Int? = null,
    val fine: Int = FocusProfileStore.DEF_FINE,
    val calibrated: Boolean = false,
    val score: Float = 0f,
    val peak: Float = 0f,
    val blurHint: String = "",
    val verdict: String = "—",
    val confidence: Int = 0,
    val net: Int = 0,
    val nearFlag: Int = 1,
    val dirConfirmed: Boolean = false,
    val lastResult: String = ""
)
