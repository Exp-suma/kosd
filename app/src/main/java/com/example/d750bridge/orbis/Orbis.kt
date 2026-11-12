package com.example.d750bridge.orbis

import android.content.Context
import android.graphics.Bitmap
import com.example.d750bridge.AppLog
import com.example.d750bridge.ai.Det
import org.json.JSONArray
import org.json.JSONObject

/**
 * オービスモードの設定。娯楽・技術検証用で、法的な速度取締りや違反判定には使えない。
 * 既定ではナンバーも画像も端末内の画面表示だけで、保存・外部送信はしない。
 */
data class OrbisCfg(
    val enabled: Boolean = false,
    val limitKmh: Int = 60,
    val sound: Boolean = false,
    val saveHistory: Boolean = false,      // ユーザーが明示的にONにしたときだけテキスト履歴を端末内に保存（画像は保存しない）
    val shootForOcr: Boolean = false,      // 検知時に1枚撮影し、高解像度のJPEGからOCRする（カメラのJPEG記録が必要）
    val minConf: Int = 50,                 // 速度の信頼度がこれ未満の車両は検知しない
    val confirmFrames: Int = 3             // しきい値超過がこのフレーム数続いたら検知（瞬間的な誤測定を除く）
)

data class OrbisHit(
    val id: Int,
    val timeMs: Long,
    val kmh: Float,
    val limit: Int,
    val conf: Int,
    val plate: String,            // 確定できない場合は「認識不能」
    val plateConf: Int,
    val plateNote: String,
    val thumb: Bitmap?
)

data class PlateRead(val text: String?, val conf: Int, val raw: String)

object PlateParser {
    // 例: 神戸 300 あ 12-34 / 神戸300あ1234 / 品川 530 さ ・・12
    private val rx = Regex("([\\u4E00-\\u9FFF]{1,4})\\s*([0-9A-Z]{2,3})\\s*([\\u3041-\\u3093A-Z])\\s*([0-9・.\\-]{2,6})")

    /** 日本のナンバープレートの形式に当てはまる部分だけを取り出す。当てはまらなければ null（推測で補完しない）。 */
    fun parse(raw: String): Pair<String, Boolean>? {
        val norm = raw.replace("\n", " ").replace("　", " ")
        val m = rx.find(norm) ?: return null
        val serial = m.groupValues[4]
        val text = "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]} $serial"
        val complete = serial.count { it.isDigit() } >= 3
        return text to complete
    }
}

/** 同じ車両の複数回の読み取りを集計する。2回以上一致した読み取りだけを「確定寄り」として扱う。 */
class PlateVotes {
    private val votes = HashMap<String, Int>()
    private val full = HashSet<String>()
    var reads = 0; private set

    fun add(r: PlateRead) {
        reads++
        val t = r.text ?: return
        votes[t] = (votes[t] ?: 0) + 1
        if (r.conf >= 70) full.add(t)
    }

    /** (文字列, 信頼度目安) — 読めていなければ null */
    fun best(): Pair<String, Int>? {
        val e = votes.maxByOrNull { it.value } ?: return null
        var conf = 30 + 25 * (e.value - 1)
        if (e.key in full) conf += 15
        return e.key to conf.coerceIn(0, 90)
    }
}

class OrbisHistoryStore(context: Context) {
    private val sp = context.getSharedPreferences("orbis_history", Context.MODE_PRIVATE)

    fun append(h: OrbisHit) {
        val arr = try { JSONArray(sp.getString("hits", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("t", h.timeMs).put("kmh", h.kmh.toDouble()).put("limit", h.limit).put("conf", h.conf)
            .put("plate", h.plate).put("plateConf", h.plateConf))
        while (arr.length() > 200) arr.remove(0)
        sp.edit().putString("hits", arr.toString()).apply()
    }

    fun count(): Int = try { JSONArray(sp.getString("hits", "[]")).length() } catch (_: Exception) { 0 }
    fun clear() { sp.edit().remove("hits").apply() }
}

/** 車両枠の「ナンバーがありそうな領域」（下側60%・左右に少し余裕）を LV 画像から切り出す。 */
fun cropVehicleForOcr(bmp: Bitmap, box: Det, scaleUpTo: Int = 1200): Bitmap? {
    val w = bmp.width; val h = bmp.height
    val bw = box.x1 - box.x0; val bh = box.y1 - box.y0
    val l = ((box.x0 - 0.05f * bw) * w).toInt().coerceIn(0, w - 2)
    val r = ((box.x1 + 0.05f * bw) * w).toInt().coerceIn(l + 1, w)
    val t = ((box.y0 + 0.40f * bh) * h).toInt().coerceIn(0, h - 2)
    val b = ((box.y1 + 0.02f * bh) * h).toInt().coerceIn(t + 1, h)
    val cw = r - l; val ch = b - t
    if (cw < 24 || ch < 12) return null
    val crop = Bitmap.createBitmap(bmp, l, t, cw, ch)
    val s = (scaleUpTo.toFloat() / cw).coerceIn(1f, 6f)
    if (s <= 1.05f) return crop
    val scaled = Bitmap.createScaledBitmap(crop, (cw * s).toInt(), (ch * s).toInt(), true)
    if (scaled !== crop) crop.recycle()
    return scaled
}
