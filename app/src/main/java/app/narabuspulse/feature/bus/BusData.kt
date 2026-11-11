package app.narabuspulse.feature.bus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import app.narabuspulse.core.Api
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Web 版 src/lib/bus/parse.js の移植（表示用の値への変換）。 */
object BusFormat {
    val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val dateTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.JAPAN).withZone(JST)
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN).withZone(JST)
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(JST)
    private val shortDate = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN).withZone(JST)
    private val weekday = DateTimeFormatter.ofPattern("E", Locale.JAPAN).withZone(JST)

    fun dateTime(ts: Long?): String = if (ts == null || ts == 0L) "—" else dateTime.format(Instant.ofEpochMilli(ts))
    fun time(ts: Long?): String = if (ts == null || ts == 0L) "—" else time.format(Instant.ofEpochMilli(ts))
    fun dayKey(ts: Long?): String = if (ts == null || ts == 0L) "" else day.format(Instant.ofEpochMilli(ts))
    fun monthKey(ts: Long?): String = dayKey(ts).take(7)
    fun shortDate(ts: Long?): String = if (ts == null || ts == 0L) "" else shortDate.format(Instant.ofEpochMilli(ts))
    fun weekday(ts: Long?): String = if (ts == null || ts == 0L) "" else weekday.format(Instant.ofEpochMilli(ts))
    fun isWeekend(ts: Long?): Boolean = weekday(ts) in setOf("土", "日")

    fun elapsed(ts: Long?, now: Long = System.currentTimeMillis()): String {
        if (ts == null || ts == 0L) return "記録なし"
        val min = maxOf(0L, Math.round((now - ts) / 60000.0))
        if (min < 1) return "たった今"
        if (min < 60) return "${min}分前"
        val h = min / 60
        if (h < 24) return "${h}時間前"
        return "${h / 24}日前"
    }
}

data class Operation(val raw: String, val route: String, val origin: String, val destination: String, val noOperation: Boolean)

private val NO_OPERATION = Regex("運行なし|運休")
private val ROUTE_HEAD = Regex("^((?=[0-9０-９/]*[0-9０-９])[0-9０-９A-Za-z/]{1,7})[\\s　]*(.+)$")

fun parseOperation(raw: String?): Operation {
    val text = raw.orEmpty().trim()
    if (text.isEmpty() || NO_OPERATION.containsMatchIn(text)) return Operation(text, "", "", "", true)
    val arrow = text.indexOf('→')
    val head = if (arrow >= 0) text.substring(0, arrow).trim() else text
    val tail = if (arrow >= 0) text.substring(arrow + 1).trim() else ""
    val m = ROUTE_HEAD.find(head)
    return if (m != null) Operation(text, Normalizer.normalize(m.groupValues[1], Normalizer.Form.NFKC), m.groupValues[2].trim(), tail, false)
    else Operation(text, "", head, tail, false)
}

fun isNoOperation(raw: String?): Boolean = raw.orEmpty().trim().let { it.isEmpty() || NO_OPERATION.containsMatchIn(it) }

fun isMonitoringOn(value: String?): Boolean = value.orEmpty().trim().uppercase() !in setOf("OFF", "FALSE", "停止", "×")

enum class VehicleState(val label: String, val color: Color) {
    Running("運行中", Brand.Success),
    Idle("運行なし", Brand.MutedFg),
    Stale("要確認", Brand.Warning),
    Error("取得エラー", Brand.Destructive),
    Off("監視OFF", Color(0xFF9AA3AB)),
}

data class Vehicle(
    val row: Int,
    val url: String,
    val operation: String,
    val operationBody: String,
    val name: String,
    val lastCheck: Long?,
    val lastRun: Long?,
    val onOff: String,
    val stop: String,
    val via: String,
    val nextTime: String,
    val nextOperation: String,
    val raw: JsonElement,
) {
    val key: String get() = if (name.isNotEmpty()) "n:$name" else "u:${url.ifEmpty { row.toString() }}"
    /** 奈良交通バスなびの carCode（URL の carCode=） */
    val carCode: String? get() = Regex("carCode=(\\d+)").find(url)?.groupValues?.get(1)

    fun state(staleMinutes: Int, now: Long = System.currentTimeMillis()): VehicleState = when {
        !isMonitoringOn(onOff) -> VehicleState.Off
        lastCheck == null || lastCheck == 0L -> VehicleState.Error
        now - lastCheck > staleMinutes * 60000L -> VehicleState.Stale
        isNoOperation(operation) -> VehicleState.Idle
        else -> VehicleState.Running
    }

    companion object {
        fun from(j: JsonElement) = Vehicle(
            row = j.int("row") ?: 0,
            url = j.str("url").orEmpty(),
            operation = j.str("operation").orEmpty(),
            operationBody = j.str("operationBody").orEmpty(),
            name = j.str("name").orEmpty().trim(),
            lastCheck = j.long("lastCheck"),
            lastRun = j.long("lastRun"),
            onOff = j.str("onOff").orEmpty(),
            stop = j.str("stop").orEmpty(),
            via = j.str("via").orEmpty(),
            nextTime = j.str("nextTime").orEmpty(),
            nextOperation = j.str("nextOperation").orEmpty(),
            raw = j,
        )
    }
}

data class OpLog(val row: Int, val at: Long?, val vehicle: String, val kind: String, val from: String, val to: String) {
    val kinds: List<String> get() = logKindLabels(kind)
    val primaryKind: String get() = logPrimaryKind(kind)

    companion object {
        fun from(j: JsonElement) = OpLog(
            row = j.int("row") ?: 0,
            at = j.long("at"),
            vehicle = j.str("vehicle").orEmpty().trim(),
            kind = j.str("kind").orEmpty().trim(),
            from = j.str("from").orEmpty().trim(),
            to = j.str("to").orEmpty().trim(),
        )
    }
}

val LOG_KINDS = listOf("運行開始", "運行終了", "系統変更", "行先変更", "経由変更", "運用変更")

fun logKindLabels(kind: String?): List<String> = LOG_KINDS.filter { kind.orEmpty().contains(it) }.ifEmpty { listOf("その他") }

fun logPrimaryKind(kind: String?): String = logKindLabels(kind).let { labels -> LOG_KINDS.firstOrNull { it in labels } ?: "その他" }

fun logKindColor(kind: String): Color = when {
    kind.contains("運行開始") -> Brand.Success
    kind.contains("運行終了") -> Brand.MutedFg
    else -> Color(0xFF2F6FB0)
}

data class BusSnapshot(
    val configured: Boolean,
    val fetchedAt: Long?,
    val updatedAt: Long?,
    val staleMinutes: Int,
    val mainSheet: String,
    val logSheet: String,
    val vehicles: List<Vehicle>,
    val logs: List<OpLog>,
    val raw: JsonElement,
) {
    companion object {
        fun from(j: JsonElement) = BusSnapshot(
            configured = j.str("configured") == "true",
            fetchedAt = j.long("fetchedAt"),
            updatedAt = j.long("updatedAt"),
            staleMinutes = j.int("staleMinutes") ?: 15,
            mainSheet = j.str("mainSheet").orEmpty(),
            logSheet = j.str("logSheet").orEmpty(),
            vehicles = j.list("vehicles").map(Vehicle::from),
            logs = j.list("logs").map(OpLog::from).sortedByDescending { it.at ?: 0L },
            raw = j,
        )
    }
}

/**
 * 監視スプレッドシートのスナップショット（Web の useBusData と同じく全画面で共有）。
 * 5 分ごとに自動更新し、画面を切り替えても取り直さない。
 */
object BusRepository {
    data class State(val data: BusSnapshot? = null, val loading: Boolean = false, val error: String? = null, val loadedAt: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val mutex = Mutex()

    /** 自動更新の間隔（通信モードに合わせる） */
    val INTERVAL_MS: Long get() = app.narabuspulse.core.Net.plan.value.intervalMs
    val logDays: Int get() = app.narabuspulse.core.Net.plan.value.logDays

    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            val s = _state.value
            if (!force && s.data != null && System.currentTimeMillis() - s.loadedAt < INTERVAL_MS) return
            _state.value = s.copy(loading = true, error = null)
            try {
                val res = Api.rpc("server-fns#getBusSnapshot", jsonOf("logDays" to logDays))
                _state.value = State(BusSnapshot.from(res), loading = false, loadedAt = System.currentTimeMillis())
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = _state.value.copy(loading = false, error = e.userMessage())
            }
        }
    }
}

/** 画面から使う：表示中は 5 分ごとに更新する。 */
@Composable
fun rememberBusData(): BusRepository.State {
    val state by BusRepository.state.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            BusRepository.refresh()
            delay(BusRepository.INTERVAL_MS)
        }
    }
    return state
}
