package com.example.d750bridge.track

import android.graphics.Bitmap
import com.example.d750bridge.ai.Det
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 追跡状態。遮蔽中に別の物体へ勝手に乗り換えないため、TRACKING 以外では対象IDを変更しない。 */
enum class TrackState(val label: String) {
    TRACKING("追跡中"),
    OCCLUDED("遮蔽中"),
    PREDICTING("予測追跡中"),
    REACQUIRING("再認識中"),
    LOST("見失い")
}

data class TrackCfg(
    val occlusionTolerance: Float = 3f,   // 遮蔽物が重なっているとき、位置を予測し続ける秒数
    val predictMax: Float = 6f,           // 遮蔽物が見えなくても予測で追う最大秒数（画面外へ出た場合を含む）
    val loseAfter: Float = 12f,           // この秒数を超えたら再捕捉を諦めて削除
    val reacqThreshold: Float = 0.45f,    // 外観の違い(0=同一,1=別物)がこれ未満なら同一とみなす（大きいほど再捕捉しやすいが誤認も増える）
    val minReacqFrames: Int = 2           // 再認識を確定するのに必要な連続フレーム数
)

/** 画面に出す1件分の追跡情報。box は観測値ではなく、追跡側の推定（予測を含む）。 */
data class TrackView(
    val id: Int, val label: String, val state: TrackState, val box: Det,
    val vx: Float, val vy: Float, val vh: Float, val ageSec: Float, val hits: Int, val isTarget: Boolean
)

class Track(val id: Int, d: Det, hist: FloatArray?, t: Long) {
    var label: String = d.label
    var score: Float = d.score
    var cx: Float = d.cx
    var cy: Float = d.cy
    var w: Float = d.x1 - d.x0
    var h: Float = d.y1 - d.y0
    var vx = 0f; var vy = 0f; var vh = 0f        // 1秒あたり
    var state = TrackState.TRACKING
    var lastSeenNs = t
    val firstNs = t
    var app: FloatArray? = hist                   // 外観（色ヒストグラム）
    var hits = 1
    var reacqCount = 0
    var lastDt = 0f

    fun box() = Det(label, score, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}

/** 外観特徴（色ヒストグラム 4x4x4）。遮蔽後に「同じ物体か」を判断するのに使う。 */
object Appearance {
    fun hist(bmp: Bitmap, d: Det): FloatArray? {
        val w = bmp.width; val h = bmp.height
        val bw = d.x1 - d.x0; val bh = d.y1 - d.y0
        if (bw <= 0f || bh <= 0f) return null
        val x0 = (d.x0 + 0.1f * bw) * w; val x1 = (d.x1 - 0.1f * bw) * w
        val y0 = (d.y0 + 0.1f * bh) * h; val y1 = (d.y1 - 0.1f * bh) * h
        if (x1 - x0 < 4f || y1 - y0 < 4f) return null
        val hist = FloatArray(64)
        var n = 0
        val grid = 10
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val x = (x0 + (x1 - x0) * (gx + 0.5f) / grid).toInt().coerceIn(0, w - 1)
            val y = (y0 + (y1 - y0) * (gy + 0.5f) / grid).toInt().coerceIn(0, h - 1)
            val p = bmp.getPixel(x, y)
            val bin = (((p shr 16) and 0xFF) shr 6) * 16 + (((p shr 8) and 0xFF) shr 6) * 4 + ((p and 0xFF) shr 6)
            hist[bin] += 1f
            n++
        }
        if (n == 0) return null
        for (i in hist.indices) hist[i] /= n
        return hist
    }

    /** 0=同一, 1=全く別。ヒストグラム交差。どちらかが無ければ 0.5（不明）。 */
    fun dist(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null) return 0.5f
        var s = 0f
        for (i in a.indices) s += min(a[i], b[i])
        return 1f - s
    }
}

fun iou(a: Det, b: Det): Float {
    val ix = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0))
    val iy = max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
    val inter = ix * iy
    val uni = a.area + b.area - inter
    return if (uni <= 0f) 0f else inter / uni
}

/**
 * 複数物体の追跡（ID維持）と、遮蔽・画面外・再出現への対応。
 *  - 毎フレーム、各トラックを速度で予測し、検出と対応付ける（位置/重なり/外観）。
 *  - 検出が取れないトラックは、重なる別物体(occluder)があれば OCCLUDED、なければ PREDICTING（柱・木・画面外など）。
 *  - 予測位置の近くに外観が似た検出が現れたら REACQUIRING → 連続で確認できたら TRACKING に復帰。
 *  - 遮蔽・予測・再認識中は対象(target)を変更しない。loseAfter を超えたら諦めて別の対象を選ぶ。
 * 注意: occluder として使えるのは物体検出器が知っているクラス（人・車・バス・自転車など）だけ。
 *       柱や木はクラスが無いため「遮蔽中」ではなく「予測追跡中」として扱う。
 */
class TrackManager {
    val tracks = ArrayList<Track>()
    var targetId = -1
        private set
    private var nextId = 1
    private var lastT = 0L

    fun reset() { tracks.clear(); targetId = -1; lastT = 0L }

    private class Cand(val tr: Track, val di: Int, val cost: Float)

    fun update(dets: List<Det>, hists: List<FloatArray?>, occluders: List<Det>, t: Long, cfg: TrackCfg): List<TrackView> {
        val dt = if (lastT == 0L) 0f else ((t - lastT) / 1e9f).coerceIn(0f, 1.5f)
        lastT = t
        // 1. 予測（観測済みの速度で位置とサイズを進める）
        for (tr in tracks) {
            tr.lastDt = dt
            tr.cx += tr.vx * dt; tr.cy += tr.vy * dt
            val dh = tr.vh * dt
            if (tr.h > 0.001f) { val k = (tr.h + dh).coerceAtLeast(0.01f) / tr.h; tr.h *= k; tr.w *= k }
        }
        val used = BooleanArray(dets.size)
        val matched = HashSet<Int>()

        // 2. 直前まで追えていたトラック: 通常の対応付け
        val c1 = ArrayList<Cand>()
        for (tr in tracks) {
            if (tr.state != TrackState.TRACKING) continue
            val pb = tr.box()
            for (di in dets.indices) {
                val d = dets[di]
                if (d.label != tr.label) continue
                val io = iou(pb, d)
                val cd = hypot(d.cx - tr.cx, d.cy - tr.cy)
                val app = Appearance.dist(tr.app, hists.getOrNull(di))
                if (!(io > 0.05f || cd < 0.08f)) continue
                if (app > 0.7f) continue
                c1.add(Cand(tr, di, (1f - io) * 0.6f + min(1f, cd * 3f) * 0.4f + app * 0.3f))
            }
        }
        c1.sortBy { it.cost }
        for (c in c1) {
            if (used[c.di] || matched.contains(c.tr.id)) continue
            used[c.di] = true; matched.add(c.tr.id)
            observe(c.tr, dets[c.di], hists.getOrNull(c.di), t, dt, true)
        }

        // 3. 見失い中のトラック: 予測位置の近く＋外観が似ている検出だけを再捕捉候補にする
        val gotCandidate = HashSet<Int>()
        val c2 = ArrayList<Cand>()
        for (tr in tracks) {
            if (tr.state == TrackState.TRACKING || matched.contains(tr.id)) continue
            val since = (t - tr.lastSeenNs) / 1e9f
            val gate = max(0.12f, hypot(tr.vx, tr.vy) * since * 0.6f + tr.w)
            for (di in dets.indices) {
                if (used[di]) continue
                val d = dets[di]
                if (d.label != tr.label) continue
                val cd = hypot(d.cx - tr.cx, d.cy - tr.cy)
                if (cd > gate) continue
                val app = Appearance.dist(tr.app, hists.getOrNull(di))
                if (app > cfg.reacqThreshold) continue
                val sizeRatio = (d.y1 - d.y0) / max(0.001f, tr.h)
                if (sizeRatio < 0.5f || sizeRatio > 2.0f) continue
                c2.add(Cand(tr, di, cd + app))
            }
        }
        c2.sortBy { it.cost }
        for (c in c2) {
            if (used[c.di] || gotCandidate.contains(c.tr.id)) continue
            used[c.di] = true; gotCandidate.add(c.tr.id)
            val tr = c.tr
            tr.reacqCount = if (tr.state == TrackState.REACQUIRING) tr.reacqCount + 1 else 1
            if (tr.reacqCount >= cfg.minReacqFrames) {
                observe(tr, dets[c.di], hists.getOrNull(c.di), t, dt, false)   // 復帰。外観は更新しない（遮蔽物の色を取り込まない）
            } else {
                tr.state = TrackState.REACQUIRING
                matched.add(tr.id)   // 候補確認中はこのフレームで状態遷移させない
            }
        }

        // 4. 対応が無かったトラックの状態遷移
        for (tr in tracks) {
            if (matched.contains(tr.id) || gotCandidate.contains(tr.id)) continue
            tr.reacqCount = 0
            val since = (t - tr.lastSeenNs) / 1e9f
            val pb = tr.box()
            val occluded = occluders.any { o -> iou(o, pb) > 0.10f || (pb.cx in o.x0..o.x1 && pb.cy in o.y0..o.y1) }
            tr.state = when {
                since > cfg.loseAfter -> TrackState.LOST
                since > cfg.predictMax -> TrackState.LOST
                occluded && since <= cfg.occlusionTolerance -> TrackState.OCCLUDED
                else -> TrackState.PREDICTING
            }
        }

        // 5. 新規トラック（再捕捉に使われなかった検出）
        for (di in dets.indices) {
            if (used[di]) continue
            val d = dets[di]
            if (d.area < 0.002f || tracks.size >= 16) continue
            tracks.add(Track(nextId++, d, hists.getOrNull(di), t))
        }

        // 6. 古いトラックを削除
        tracks.removeAll { (t - it.lastSeenNs) / 1e9f > cfg.loseAfter }

        // 7. 対象の選択（遮蔽・予測・再認識中は変更しない）
        val cur = tracks.firstOrNull { it.id == targetId }
        if (cur == null) {
            val best = tracks.filter { it.state == TrackState.TRACKING }
                .maxByOrNull { it.w * it.h * (1.2f - hypot(it.cx - 0.5f, it.cy - 0.5f)) }
            targetId = best?.id ?: -1
        }

        return tracks.map {
            TrackView(it.id, it.label, it.state, it.box(), it.vx, it.vy, it.vh, ((t - it.firstNs) / 1e9f), it.hits, it.id == targetId)
        }
    }

    /** 検出で更新。smooth=true なら外観も更新する。 */
    private fun observe(tr: Track, d: Det, hist: FloatArray?, t: Long, dt: Float, updateApp: Boolean) {
        val pcx = tr.cx; val pcy = tr.cy
        // tr.cx/cy は予測済みの位置。速度は「検出 − 予測」の差分から補正する
        val a = 0.5f
        val nw = d.x1 - d.x0; val nh = d.y1 - d.y0
        if (dt > 0.001f) {
            tr.vx = (0.6f * tr.vx + 0.4f * ((d.cx - pcx) / dt + tr.vx)).coerceIn(-1.5f, 1.5f)
            tr.vy = (0.6f * tr.vy + 0.4f * ((d.cy - pcy) / dt + tr.vy)).coerceIn(-1.5f, 1.5f)
            tr.vh = (0.6f * tr.vh + 0.4f * ((nh - tr.h) / dt + tr.vh)).coerceIn(-1.0f, 1.0f)
        }
        tr.cx = pcx + a * (d.cx - pcx)
        tr.cy = pcy + a * (d.cy - pcy)
        tr.w = tr.w + 0.4f * (nw - tr.w)
        tr.h = tr.h + 0.4f * (nh - tr.h)
        tr.score = d.score
        tr.lastSeenNs = t
        tr.hits++
        tr.state = TrackState.TRACKING
        tr.reacqCount = 0
        if (updateApp && hist != null) {
            val old = tr.app
            tr.app = if (old == null) hist else FloatArray(64) { 0.9f * old[it] + 0.1f * hist[it] }
        }
    }
}
