package app.narabuspulse.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * この端末で発生した通信の記録（Web の lib/monitor/telemetry.js と同じ用途）。
 * システム状態モニターに表示する。サーバーには送らない。
 */
object Telemetry {
    data class ApiStat(val calls: Int = 0, val ok: Int = 0, val fail: Int = 0, val totalMs: Long = 0, val lastOkAt: Long = 0, val lastErrorAt: Long = 0, val lastStatus: Int = 0, val lastError: String = "") {
        val avgMs: Long? get() = if (calls > 0) totalMs / calls else null
        val successRate: Double? get() = if (calls > 0) ok.toDouble() / calls else null
    }
    data class DbStat(val entity: String, val reads: Int = 0, val writes: Int = 0, val ok: Int = 0, val fail: Int = 0, val totalMs: Long = 0, val error: String = "") {
        val calls get() = reads + writes
        val avgMs: Long? get() = if (calls > 0) totalMs / calls else null
    }
    data class ErrorRow(val at: Long, val kind: String, val source: String, val target: String, val message: String, val status: Int)
    data class Snapshot(val api: Map<String, ApiStat> = emptyMap(), val db: Map<String, DbStat> = emptyMap(), val errors: List<ErrorRow> = emptyList(), val ops: List<Long> = emptyList(), val gpsOk: Int = 0, val gpsFail: Int = 0, val gpsLastMs: Long? = null, val gpsLastError: String = "", val updatedAt: Long = 0) {
        val opsLastMinute: Int get() { val t = System.currentTimeMillis() - 60_000; return ops.count { it >= t } }
        val errorCounts: Map<String, Int> get() = errors.groupingBy { it.kind }.eachCount()
    }

    val KIND_LABEL = mapOf("http429" to "429（回数制限）", "http522" to "522（接続障害）", "http5xx" to "500系（サーバー）", "http4xx" to "400系（要求）", "timeout" to "タイムアウト", "json" to "JSON解析", "db" to "DBアクセス", "gps" to "GPS取得", "network" to "通信失敗")
    const val APP = "アプリ内API（server関数）"

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    private fun kindOf(status: Int, error: Throwable?): String = when {
        status == 429 -> "http429"; status == 522 -> "http522"; status >= 500 -> "http5xx"; status >= 400 -> "http4xx"
        error is java.net.SocketTimeoutException || error?.message?.contains("timeout", true) == true -> "timeout"
        error is kotlinx.serialization.SerializationException -> "json"
        else -> "network"
    }

    @Synchronized
    fun recordApi(label: String, ok: Boolean, ms: Long, status: Int = 0, error: Throwable? = null, target: String = "") {
        val now = System.currentTimeMillis()
        val s = _state.value
        val prev = s.api[label] ?: ApiStat()
        val next = prev.copy(calls = prev.calls + 1, ok = prev.ok + if (ok) 1 else 0, fail = prev.fail + if (ok) 0 else 1, totalMs = prev.totalMs + ms,
            lastOkAt = if (ok) now else prev.lastOkAt, lastErrorAt = if (ok) prev.lastErrorAt else now, lastStatus = if (status > 0) status else prev.lastStatus, lastError = if (ok) prev.lastError else (error?.message ?: "HTTP $status"))
        val errors = if (ok) s.errors else (listOf(ErrorRow(now, kindOf(status, error), label, target, error?.message ?: "HTTP $status", status)) + s.errors).take(60)
        _state.value = s.copy(api = s.api + (label to next), errors = errors, updatedAt = now)
    }

    @Synchronized
    fun recordDb(entity: String, write: Boolean, ok: Boolean, ms: Long, error: Throwable? = null) {
        val now = System.currentTimeMillis()
        val s = _state.value
        val prev = s.db[entity] ?: DbStat(entity)
        val next = prev.copy(reads = prev.reads + if (write) 0 else 1, writes = prev.writes + if (write) 1 else 0, ok = prev.ok + if (ok) 1 else 0, fail = prev.fail + if (ok) 0 else 1, totalMs = prev.totalMs + ms, error = if (ok) prev.error else error?.message.orEmpty())
        val errors = if (ok) s.errors else (listOf(ErrorRow(now, "db", "エンティティ", entity, error?.message ?: "失敗", 0)) + s.errors).take(60)
        _state.value = s.copy(db = s.db + (entity to next), ops = (s.ops + now).takeLast(500), errors = errors, updatedAt = now)
    }

    @Synchronized
    fun recordGps(ok: Boolean, ms: Long, error: String = "") {
        val now = System.currentTimeMillis()
        val s = _state.value
        val errors = if (ok) s.errors else (listOf(ErrorRow(now, "gps", "GPS取得", "", error, 0)) + s.errors).take(60)
        _state.value = s.copy(gpsOk = s.gpsOk + if (ok) 1 else 0, gpsFail = s.gpsFail + if (ok) 0 else 1, gpsLastMs = ms, gpsLastError = if (ok) s.gpsLastError else error, errors = errors, updatedAt = now)
    }

    fun reset() { _state.value = Snapshot(updatedAt = System.currentTimeMillis()) }
}
