package com.example.d750bridge

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * アプリ内ログ。認証情報（Wi-Fiパスフレーズ等）は絶対に渡さないこと。
 *
 * 最適化: 以前は1行ごとに StateFlow へ 1500 要素のリストをコピーして流していたため、
 * ライブビュー中（毎秒数十行）に画面全体が再描画されていた。
 * 今はリングバッファに積むだけで、UI は必要なときに snapshot() を取得する。
 */
object AppLog {
    private const val MAX_LINES = 1500
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buf = ArrayDeque<String>(MAX_LINES + 8)

    /** true の間はパケット単位の詳細ログ（TX/RX の16進ダンプ）も記録する。通常は false。 */
    @Volatile var verbose: Boolean = false

    @Synchronized
    private fun add(level: String, msg: String) {
        val line = "${fmt.format(Date())} [$level] $msg"
        if (buf.size >= MAX_LINES) buf.removeFirst()
        buf.addLast(line)
        Log.d("D750Bridge", line)
    }

    fun i(msg: String) = add("INFO", msg)
    fun w(msg: String) = add("WARN", msg)
    fun e(msg: String, t: Throwable? = null) =
        add("ERROR", if (t == null) msg else "$msg (${t.javaClass.simpleName}: ${t.message})")

    /** 詳細ログ。verbose が false のときは文字列生成も行わない。 */
    inline fun v(msg: () -> String) { if (verbose) i(msg()) }

    @Synchronized
    fun snapshot(last: Int = 200): List<String> = buf.toList().takeLast(last)

    @Synchronized
    fun dump(): String = buf.joinToString("\n")
}
