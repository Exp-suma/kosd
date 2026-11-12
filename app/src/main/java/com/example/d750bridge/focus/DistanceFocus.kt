package com.example.d750bridge.focus

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 被写体までの距離の推定（枠の高さ + 公称の実寸 + 焦点距離）。
 *   距離[m] = 焦点距離[mm] × 実寸[m] ÷ (枠の高さ(画像比) × センサー高24mm)
 * 公称の実寸はクラスごとの仮定値（バス3.1m等）のため、絶対距離は±20%以上ずれうる。
 * ただし同じ仮定で「校正」と「予測」の両方を行えば、実寸の誤差は相殺される（距離の比は枠の高さの比で決まる）。
 * ライブビューがセンサー全面(FX)を写している前提。クロップされている場合は要補正（未検証）。
 */
object DistanceEst {
    const val SENSOR_H_MM = 24f

    fun nominalHeightM(label: String): Float = when (label) {
        "bus" -> 3.1f
        "train" -> 3.6f
        "truck" -> 3.3f
        "car" -> 1.5f
        "person" -> 1.7f
        "face" -> 0.22f
        else -> 2.0f
    }

    fun distanceM(label: String, hFrac: Float, focalMm: Double?): Float? {
        if (focalMm == null || hFrac < 0.01f) return null
        return (focalMm.toFloat() * nominalHeightM(label)) / (hFrac * SENSOR_H_MM)
    }
}

/**
 * 距離とレンズ位置の対応表（焦点距離の校正点 × 被写体クラスごと）。
 * 点は (逆距離 1/d, フォーカス位置)。フォーカス位置は「アプリがMfDriveで動かした累計」なので、
 * 記録から予測までの間にAF(AfDrive)でレンズが動くと無効になる（MF運用が前提）。
 * 逆距離とレンズ位置は概ね線形なので、傾き（ステップ/(1/m)）を最小二乗で求める。
 */
class DistanceFocusMap {
    data class Pt(val inv: Float, val pos: Int)
    private val pts = HashMap<String, ArrayList<Pt>>()

    fun record(key: String, inv: Float, pos: Int) {
        val list = pts.getOrPut(key) { ArrayList() }
        list.removeAll { abs(it.inv - inv) < 0.05f * inv }   // ほぼ同じ距離の古い点は置き換え
        list.add(Pt(inv, pos))
        if (list.size > 12) list.removeAt(0)
    }

    fun count(key: String): Int = pts[key]?.size ?: 0
    fun clear(key: String) { pts.remove(key) }

    /** 傾き。点が2つ未満・距離の広がりが小さい・直線性が低い場合は null（予測に使わない）。 */
    fun slope(key: String): Float? {
        val l = pts[key] ?: return null
        if (l.size < 2) return null
        val mean = l.map { it.inv }.average().toFloat()
        val spread = (l.maxOf { it.inv } - l.minOf { it.inv }) / mean
        if (spread < 0.15f) return null
        val mx = mean
        val my = l.map { it.pos.toFloat() }.average().toFloat()
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (p in l) { val dx = p.inv - mx; val dy = p.pos - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
        if (sxx <= 0f || syy <= 0f) return null
        val r = sxy / sqrt(sxx * syy)
        if (abs(r) < 0.7f) return null
        return sxy / sxx
    }
}
