package app.narabuspulse.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 通信モード（Web の lib/net/NetContext.jsx と lib/net/network.js の planFor と同じ設定）。
 * profile: 利用者が選ぶ「auto / low / fast」、speedClass: 実測の回線区分。
 * 個別の上書き設定（パフォーマンス設定・詳細設定）は lib/monitor/settings.js をそのまま使い、
 * 端末に保存して通信モードの値へ重ねる。
 */
object Net {
    data class Plan(
        val key: String = "standard",
        val profile: String = "auto",
        val intervalMs: Long = 30_000,
        val gpsMs: Long = 60_000,
        val radarMs: Long = 300_000,
        val snowMs: Long = 600_000,
        val timeoutMs: Long = 35_000,
        val tiles: Boolean = true,
        val logDays: Int = 60,
        val low: Boolean = false,
        val short: String = "標準",
        val label: String = "自動",
        val radarDetail: String = "high",
        val radarFrames: Int = 0,
        val raw: JsonElement? = null,
    )

    /** 回線の実測状態（Web の NetContext の state と同じ項目）。 */
    data class Status(
        val kbps: Double? = null,
        val measuredAt: Long = 0,
        val latencyMs: Long? = null,
        val measuring: Boolean = false,
        val lastError: String? = null,
        val failures: Int = 0,
        val attempts: Int = 0,
    )

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _plan = MutableStateFlow(Plan())
    val plan: StateFlow<Plan> = _plan
    private val _resolved = MutableStateFlow<JsonElement>(JsonObject(emptyMap()))
    /** 通信モードの値に個別設定を重ねた最終的な設定（resolveSettings の結果）。 */
    val resolved: StateFlow<JsonElement> = _resolved
    private val _overrides = MutableStateFlow<JsonElement>(JsonObject(emptyMap()))
    val overrides: StateFlow<JsonElement> = _overrides
    private val _history = MutableStateFlow<List<JsonElement>>(emptyList())
    val history: StateFlow<List<JsonElement>> = _history
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private var pending = 0

    private fun prefs() = appContext.getSharedPreferences("net", Context.MODE_PRIVATE)
    val profile: String get() = prefs().getString("profile", "auto") ?: "auto"
    val speedClass: String get() = prefs().getString("speedClass", "standard") ?: "standard"

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = prefs().getFloat("kbps", -1f)
        if (saved > 0) _status.value = Status(kbps = saved.toDouble(), measuredAt = prefs().getLong("measuredAt", 0))
        scope.launch {
            _overrides.value = Logic.call("monitor/settings", "readOverrides").takeIf { it is JsonObject } ?: JsonObject(emptyMap())
            _history.value = (Logic.call("monitor/settings", "readHistory") as? JsonArray)?.toList().orEmpty()
            recomputeNow()
            // 自動最適化モードは定期的に回線を測る（低速時は間隔を空けて通信量を抑える）
            while (true) {
                val every = if (_plan.value.low) 15 * 60_000L else 5 * 60_000L
                if (profile == "auto" && System.currentTimeMillis() - _status.value.measuredAt > every) runCatching { measureNow() }
                delay(60_000)
            }
        }
    }

    fun setProfile(p: String) { prefs().edit().putString("profile", p).apply(); recompute() }
    fun setSpeedClass(c: String) { prefs().edit().putString("speedClass", c).apply(); recompute() }

    fun recompute() { scope.launch { recomputeNow() } }

    private suspend fun recomputeNow() {
        val p = Logic.call("net/network", "planFor", profile, speedClass)
        if (p.obj == null) return
        val r = Logic.call("monitor/settings", "resolveSettings", p, _overrides.value).takeIf { it is JsonObject } ?: p
        _resolved.value = r
        _plan.value = Plan(
            key = p.str("key") ?: "standard",
            profile = p.str("profile") ?: profile,
            intervalMs = r.long("intervalMs") ?: 30_000,
            gpsMs = r.long("gpsMs") ?: 60_000,
            radarMs = r.long("radarMs") ?: 300_000,
            snowMs = r.long("snowMs") ?: 600_000,
            timeoutMs = r.long("timeoutMs") ?: 35_000,
            tiles = r.bool("tiles") ?: true,
            logDays = r.int("logDays") ?: 60,
            low = p.bool("low") ?: false,
            short = p.str("short") ?: "標準",
            label = p.str("label") ?: "自動",
            radarDetail = r.str("radarDetail") ?: "high",
            radarFrames = r.int("radarFrames") ?: 0,
            raw = p,
        )
    }

    /** 個別設定を変更する（範囲外の値・不正な値は保存しない）。Web の setSetting と同じ。 */
    suspend fun setSetting(key: String, value: Any?): Boolean {
        val tune = Logic.constant("monitor/settings", "TUNE_MAP").obj?.containsKey(key) == true
        val bool = Logic.constant("monitor/settings", "BOOLEAN_MAP").obj?.containsKey(key) == true
        val next: JsonElement = when {
            tune -> Logic.call("monitor/settings", "clampTune", key, value).takeIf { it is JsonPrimitive && it !is JsonNull } ?: return false
            bool -> JsonPrimitive(value == true)
            key == "logLevel" && value in setOf("minimal", "standard", "detailed") -> JsonPrimitive(value as String)
            else -> return false
        }
        val before = _resolved.value.obj?.get(key) ?: JsonNull
        if (before.toString() == next.toString()) return true
        val merged = JsonObject((_overrides.value.obj ?: emptyMap()) + (key to next))
        save(merged, jsonOf("at" to System.currentTimeMillis(), "key" to key, "before" to before, "after" to next))
        return true
    }

    /** 1項目を通信モードの値に戻す。 */
    suspend fun clearSetting(key: String) {
        val cur = _overrides.value.obj ?: return
        if (key !in cur) return
        save(JsonObject(cur - key), jsonOf("at" to System.currentTimeMillis(), "key" to key, "before" to cur[key], "after" to "reset"))
    }

    /** 個別設定をすべて初期値（通信モードの値）に戻す。 */
    suspend fun resetSettings() = save(JsonObject(emptyMap()), jsonOf("at" to System.currentTimeMillis(), "key" to "*", "before" to "individual", "after" to "default"))

    suspend fun clearHistory() { _history.value = (Logic.call("monitor/settings", "clearHistory") as? JsonArray)?.toList().orEmpty() }

    private suspend fun save(merged: JsonObject, entry: JsonObject) {
        Logic.call("monitor/settings", "saveOverrides", merged)
        _history.value = (Logic.call("monitor/settings", "appendHistory", entry) as? JsonArray)?.toList().orEmpty()
        _overrides.value = merged
        recomputeNow()
    }

    private val speedClient by lazy { OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build() }

    /** 実サーバーからテストデータをダウンロードして回線速度を測る（Web の measureSpeed と同じ方法）。 */
    suspend fun measureNow(): Double? {
        if (_status.value.measuring) return _status.value.kbps
        _status.value = _status.value.copy(measuring = true, lastError = null)
        val prevKbps = _status.value.kbps
        val bytes = when { prevKbps == null || prevKbps <= 0 -> 16 * 1024; prevKbps < 300 -> 6 * 1024; prevKbps < 1000 -> 16 * 1024; else -> 32 * 1024 }
        return try {
            val (kbps, latency) = withContext(Dispatchers.IO) {
                val started = System.nanoTime()
                val req = Request.Builder().url("${Config.BASE_URL}/api/speedtest?bytes=$bytes&n=${(Math.random() * 1e6).toLong()}").header("accept", "application/json").build()
                speedClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) throw IllegalStateException("測定用データを取得できませんでした（HTTP ${res.code}）")
                    val src = res.body.byteStream()
                    val buf = ByteArray(4096)
                    var total = 0; var firstAt = 0L; var firstSize = 0; var lastAt = started
                    while (true) {
                        val n = src.read(buf); if (n < 0) break
                        total += n
                        if (firstAt == 0L) { firstAt = System.nanoTime(); firstSize = n }
                        lastAt = System.nanoTime()
                    }
                    val streamed = total - firstSize; val elapsedMs = (lastAt - firstAt) / 1e6
                    val k = if (streamed > 0 && elapsedMs >= 5) streamed * 8 / elapsedMs else total * 8 / maxOf((lastAt - started) / 1e6, 1.0)
                    k to ((firstAt - started) / 1_000_000)
                }
            }
            val now = System.currentTimeMillis()
            prefs().edit().putFloat("kbps", kbps.toFloat()).putLong("measuredAt", now).apply()
            _status.value = _status.value.copy(kbps = kbps, measuredAt = now, latencyMs = latency, measuring = false, attempts = _status.value.attempts + 1)
            // 自動最適化：頻繁な切り替えを避ける判定（decideMode）で回線区分を決める
            val d = Logic.call("net/network", "decideMode", speedClass, kbps, pending)
            pending = d.int("pending") ?: 0
            d.str("mode")?.let { if (it != speedClass) setSpeedClass(it) }
            kbps
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _status.value = _status.value.copy(measuring = false, lastError = e.message ?: "測定できませんでした", failures = _status.value.failures + 1, attempts = _status.value.attempts + 1)
            null
        }
    }
}
