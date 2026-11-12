package com.example.d750bridge.speed

import android.content.Context
import com.example.d750bridge.focus.DistanceEst
import com.example.d750bridge.track.TrackState
import com.example.d750bridge.track.TrackView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 撮影地点ごとの速度校正。p1/p2 は道路上（地面）の2点を映像上で指定したもの（0..1）。realDistM はその2点間の実距離。
 * camHeightM / roadDistM / roadAngleDeg は記録のみで、現バージョンの計算には使っていない（未使用）。
 */
data class SpeedProfile(
    val name: String = "",
    val p1x: Float = 0.3f, val p1y: Float = 0.7f,
    val p2x: Float = 0.7f, val p2y: Float = 0.7f,
    val realDistM: Float = 0f,
    val camHeightM: Float = 0f,
    val roadDistM: Float = 0f,
    val roadAngleDeg: Float = 0f
) {
    val valid: Boolean get() = realDistM > 0f && hypot(p2x - p1x, p2y - p1y) > 0.02f
}

/** 車両1台ぶんの速度推定。kmh は推定値であり、法的に有効な測定値ではない。 */
data class SpeedInfo(
    val id: Int,
    val kmh: Float?,          // 採用値（ゲート方式 > 基準スケール方式 > 公称寸法方式）
    val method: String,
    val conf: Int,            // 0..95（目安）
    val dir: String,
    val gateKmh: Float?,      // ①基準2地点の通過時間
    val scaleKmh: Float?,     // ②基準2地点から求めた縮尺 × 画面上の移動量
    val nominalKmh: Float?,   // ③公称の車両寸法 × 画面上の移動量（＋接近速度）
    val note: String
)

class SpeedProfileStore(context: Context) {
    private val sp = context.getSharedPreferences("speed_profiles", Context.MODE_PRIVATE)

    fun names(): List<String> = readAll().keys.sorted()
    fun load(name: String): SpeedProfile? = readAll()[name]

    fun save(p: SpeedProfile) {
        if (p.name.isBlank()) return
        val all = readAll().toMutableMap()
        all[p.name] = p
        val arr = JSONArray()
        for (v in all.values) {
            arr.put(JSONObject().put("name", v.name).put("p1x", v.p1x.toDouble()).put("p1y", v.p1y.toDouble())
                .put("p2x", v.p2x.toDouble()).put("p2y", v.p2y.toDouble()).put("realDistM", v.realDistM.toDouble())
                .put("camHeightM", v.camHeightM.toDouble()).put("roadDistM", v.roadDistM.toDouble()).put("roadAngleDeg", v.roadAngleDeg.toDouble()))
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    fun delete(name: String) {
        val all = readAll().toMutableMap()
        all.remove(name)
        val arr = JSONArray()
        for (v in all.values) {
            arr.put(JSONObject().put("name", v.name).put("p1x", v.p1x.toDouble()).put("p1y", v.p1y.toDouble())
                .put("p2x", v.p2x.toDouble()).put("p2y", v.p2y.toDouble()).put("realDistM", v.realDistM.toDouble())
                .put("camHeightM", v.camHeightM.toDouble()).put("roadDistM", v.roadDistM.toDouble()).put("roadAngleDeg", v.roadAngleDeg.toDouble()))
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    private fun readAll(): Map<String, SpeedProfile> {
        val s = sp.getString("profiles", null) ?: return emptyMap()
        val out = LinkedHashMap<String, SpeedProfile>()
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = SpeedProfile(
                    o.getString("name"), o.getDouble("p1x").toFloat(), o.getDouble("p1y").toFloat(),
                    o.getDouble("p2x").toFloat(), o.getDouble("p2y").toFloat(), o.getDouble("realDistM").toFloat(),
                    o.optDouble("camHeightM", 0.0).toFloat(), o.optDouble("roadDistM", 0.0).toFloat(), o.optDouble("roadAngleDeg", 0.0).toFloat()
                )
                out[p.name] = p
            }
        } catch (_: Exception) { }
        return out
    }
}

/**
 * 映像からの速度推定。3方式を並行して計算し、比較できるようにする。
 *  ①ゲート方式: 道路上の基準2点を車両の接地点（枠の下辺中央）が通過する時刻の差で、実距離÷時間。
 *  ②基準スケール方式: 基準2点の距離から「1ピクセル=何m」を求め、道路方向の移動量に掛ける（基準点付近の奥行きでのみ有効）。
 *  ③公称寸法方式: 車両クラスの公称の高さ(バス3.1m等)から「1ピクセル=何m」を求める。校正不要だが精度は低い。
 *     焦点距離が分かれば、枠の高さの変化から接近・遠ざかりの速度も合成する。
 * 時刻は、ライブビューフレームを取り込んだ時刻（遅延・欠落フレームはあるが、時刻で補正している）。
 * 追跡が TRACKING のフレーム（実際に検出できたフレーム）だけを使い、予測位置は測定に使わない。
 */
class SpeedEstimator {
    private class Sample(val t: Float, val gx: Float, val gy: Float, val h: Float)
    private class Gate(var prevU: Float = Float.NaN, var t0: Float = Float.NaN, var t1: Float = Float.NaN, var kmh: Float? = null)

    private val hist = HashMap<Int, ArrayList<Sample>>()
    private val gates = HashMap<Int, Gate>()
    private var t0Ns = 0L

    fun reset() { hist.clear(); gates.clear(); t0Ns = 0L }

    private fun slope(xs: List<Float>, ys: List<Float>): Pair<Float, Float>? {   // (傾き, R^2)
        val n = xs.size
        if (n < 4) return null
        val mx = xs.average().toFloat(); val my = ys.average().toFloat()
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (i in 0 until n) { val dx = xs[i] - mx; val dy = ys[i] - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
        if (sxx < 1e-6f) return null
        val r2 = if (syy < 1e-9f) 1f else (sxy * sxy) / (sxx * syy)
        return (sxy / sxx) to r2
    }

    fun update(views: List<TrackView>, tNs: Long, prof: SpeedProfile, wPx: Int, hPx: Int, focalMm: Double?): Map<Int, SpeedInfo> {
        if (t0Ns == 0L) t0Ns = tNs
        val t = (tNs - t0Ns) / 1e9f
        val alive = views.map { it.id }.toSet()
        hist.keys.retainAll(alive); gates.keys.retainAll(alive)
        val out = HashMap<Int, SpeedInfo>()
        val W = wPx.toFloat(); val H = hPx.toFloat()

        // 基準線（ピクセル空間）
        val ax = prof.p1x * W; val ay = prof.p1y * H
        val bx = prof.p2x * W; val by = prof.p2y * H
        val L = hypot(bx - ax, by - ay)
        val dirx = if (L > 1f) (bx - ax) / L else 1f
        val diry = if (L > 1f) (by - ay) / L else 0f
        val scaleMPerPx = if (prof.valid && L > 1f) prof.realDistM / L else 0f

        for (v in views) {
            val list = hist.getOrPut(v.id) { ArrayList() }
            if (v.state == TrackState.TRACKING) {
                val g = Sample(t, v.box.cx * W, v.box.y1 * H, (v.box.y1 - v.box.y0))
                if (list.isEmpty() || t - list.last().t > 0.005f) list.add(g)
                while (list.size > 40 || (list.isNotEmpty() && t - list.first().t > 2.0f)) list.removeAt(0)
            }
            if (list.size < 2) continue

            // ① ゲート通過
            val gate = gates.getOrPut(v.id) { Gate() }
            if (prof.valid && v.state == TrackState.TRACKING) {
                val cur = list.last(); val prev = list[list.size - 2]
                val uc = ((cur.gx - ax) * dirx + (cur.gy - ay) * diry) / L
                val up = ((prev.gx - ax) * dirx + (prev.gy - ay) * diry) / L
                fun cross(level: Float): Float? {
                    if ((up - level) * (uc - level) > 0f || up == uc) return null
                    val f = (level - up) / (uc - up)
                    return prev.t + f * (cur.t - prev.t)
                }
                if (gate.t0.isNaN()) cross(0f)?.let { gate.t0 = it }
                if (gate.t1.isNaN()) cross(1f)?.let { gate.t1 = it }
                if (!gate.t0.isNaN() && !gate.t1.isNaN() && gate.kmh == null) {
                    val dtc = abs(gate.t1 - gate.t0)
                    if (dtc > 0.05f && dtc < 30f) gate.kmh = prof.realDistM / dtc * 3.6f
                }
            }

            // 直近約1秒の回帰
            val recent = list.filter { t - it.t <= 1.0f }
            val ts = recent.map { it.t }
            val sx = slope(ts, recent.map { it.gx })
            val sy = slope(ts, recent.map { it.gy })
            val sh = slope(ts, recent.map { it.h })
            val span = if (recent.size >= 2) recent.last().t - recent.first().t else 0f
            val enough = recent.size >= 4 && span >= 0.3f
            val r2 = if (sx != null && sy != null) min(sx.second, sy.second).coerceAtLeast(0f).let { (sx.second + sy.second) / 2f } else 0f
            val boxH = v.box.y1 - v.box.y0

            // ② 基準スケール（道路方向成分）
            var scaleKmh: Float? = null
            if (enough && sx != null && sy != null && scaleMPerPx > 0f) {
                val along = sx.first * dirx + sy.first * diry   // px/s
                scaleKmh = abs(along) * scaleMPerPx * 3.6f
            }
            // ③ 公称寸法（横方向 + 奥行き方向）
            var nominalKmh: Float? = null
            var radialNote = ""
            if (enough && sx != null && sy != null && boxH > 0.01f) {
                val mPerPx = DistanceEst.nominalHeightM(v.label) / (boxH * H)
                val lat = abs(sx.first) * mPerPx          // m/s（横）
                var rad = 0f
                if (focalMm != null && sh != null) {
                    // d = f*Hn/(h*24mm) → dd/dt = -d * (dh/dt)/h
                    val d = DistanceEst.distanceM(v.label, boxH, focalMm)
                    if (d != null) rad = abs(d * sh.first / boxH)
                } else radialNote = "焦点距離不明のため接近速度は含まず"
                nominalKmh = hypot(lat, rad) * 3.6f
            }

            val primary: Float?
            val method: String
            if (gate.kmh != null) { primary = gate.kmh; method = "基準2地点の通過時間" }
            else if (scaleKmh != null) { primary = scaleKmh; method = "基準スケール" }
            else if (nominalKmh != null) { primary = nominalKmh; method = "公称寸法(精度低)" }
            else { primary = null; method = "測定中" }

            var conf = 0
            if (primary != null) {
                conf = when (method) {
                    "基準2地点の通過時間" -> 60
                    "基準スケール" -> 40
                    else -> 20
                }
                conf += (r2 * 20f).toInt()
                if (boxH > 0.12f) conf += 10
                if (recent.size >= 8) conf += 5
                if (gate.kmh != null && scaleKmh != null && abs(gate.kmh!! - scaleKmh) / max(1f, gate.kmh!!) < 0.15f) conf += 10   // 方式間で一致
                conf = conf.coerceIn(0, 95)
            }

            val rel = if (boxH > 0.001f && sh != null) sh.first / boxH else 0f   // 枠の高さの相対変化/秒
            val dir = when {
                rel > 0.15f -> "接近"
                rel < -0.15f -> "遠ざかる"
                sx != null && sx.first > 5f -> "左→右"
                sx != null && sx.first < -5f -> "右→左"
                else -> "—"
            }
            val note = buildString {
                if (!prof.valid) append("校正なし（推定値）")
                if (radialNote.isNotEmpty()) { if (isNotEmpty()) append(" / "); append(radialNote) }
            }
            out[v.id] = SpeedInfo(v.id, primary, method, conf, dir, gate.kmh, scaleKmh, nominalKmh, note)
        }
        return out
    }
}
