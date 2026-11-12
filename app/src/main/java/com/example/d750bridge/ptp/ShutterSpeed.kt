package com.example.d750bridge.ptp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * PTP ExposureTime (0x500D) は UINT32 で単位は 0.1ms（1/10000秒）。
 *   1/100秒 = 100, 1/250秒 = 40, 1秒 = 10000
 * 旧UIは「raw」を直接入力させていたため、「250」と打つと 1/40秒になっていた。
 * ここでは「1/250」「0.5」「2"」のような通常表記を扱い、
 * カメラが返した許容値(enum)にスナップして書き込む。
 */
object ShutterSpeed {
    const val BULB = 0xFFFFFFFFL

    /** D750 の 1/3 段表記（表示名の決定用）。raw 値の丸め誤差を吸収するための基準表。 */
    private val nominal: List<Pair<String, Double>> = buildList {
        val fast = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
        fast.forEach { add("1/$it" to 1.0 / it) }
        add("0.3\"" to 0.3); add("0.4\"" to 0.4); add("0.5\"" to 0.5); add("0.6\"" to 0.6); add("0.8\"" to 0.8)
        add("1\"" to 1.0); add("1.3\"" to 1.3); add("1.6\"" to 1.6)
        listOf(2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30).forEach {
            val d = it.toDouble()
            add((if (d % 1.0 == 0.0) "${d.toInt()}\"" else "$d\"") to d)
        }
    }

    fun rawToSeconds(raw: Long): Double = raw / 10000.0

    /** カメラ内部値 → 見た目の表記（丸め誤差を最寄りの標準段にスナップ）。 */
    fun format(raw: Long): String {
        if (raw == BULB) return "BULB"
        if (raw <= 0L) return "?"
        val sec = rawToSeconds(raw)
        val best = nominal.minByOrNull { abs(ln(it.second / sec)) }!!
        return best.first
    }

    /** 画面表示用: 表記 + raw を併記して、カメラ側表示との照合ができるようにする。 */
    fun label(raw: Long): String = "${format(raw)}  (raw $raw)"

    /** 入力文字列を秒に変換。受理: 1/250, 250 (※整数は秒ではなく「1/250」扱いにしない), 0.5, 2", 1.3s */
    fun parseSeconds(text: String): Double? {
        val t = text.trim().replace("秒", "").replace("\"", "").replace("”", "").replace("s", "", true).trim()
        if (t.isEmpty()) return null
        if (t.contains('/')) {
            val p = t.split('/')
            if (p.size != 2) return null
            val a = p[0].trim().toDoubleOrNull() ?: return null
            val b = p[1].trim().toDoubleOrNull() ?: return null
            if (a <= 0.0 || b <= 0.0) return null
            return a / b
        }
        val v = t.toDoubleOrNull() ?: return null
        return if (v > 0.0) v else null
    }

    /** 許容値(raw)の中から、入力秒に対数距離で最も近いものを選ぶ。 */
    fun snap(seconds: Double, allowedRaw: List<Long>): Long? {
        val cands = allowedRaw.filter { it in 1..0xFFFFFFFEL }
        if (cands.isEmpty()) return null
        return cands.minByOrNull { abs(ln(rawToSeconds(it) / seconds)) }
    }

    /** 許容値の一覧が得られない場合のフォールバック。 */
    fun toRaw(seconds: Double): Long = (seconds * 10000.0).roundToInt().coerceAtLeast(1).toLong()
}
