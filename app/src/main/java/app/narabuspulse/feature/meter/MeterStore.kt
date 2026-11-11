package app.narabuspulse.feature.meter

import android.content.Context
import app.narabuspulse.core.Json
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.str
import app.narabuspulse.core.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 露出計の保存領域（Web: lib/meter/store.js）。
 * Web の localStorage "nara-meter-v1" と同じ形の JSON を、logic.js と共有の保存領域（logic_storage）に置く。
 * カメラ・レンズ・組み合わせ・プリセット・校正値・履歴・最後の設定を保持し、通信がなくても読み書きできる。
 */
object MeterStore {
    private const val KEY = "nara-meter-v1"
    private lateinit var ctx: Context
    private val _state = MutableStateFlow(JsonObject(emptyMap()))
    val state: StateFlow<JsonObject> get() = _state

    val MOUNTS = listOf("Nikon F", "Nikon Z", "Canon EF", "Canon RF", "Sony E", "Lマウント", "Micro Four Thirds", "PENTAX K", "その他")
    val SENSORS = listOf(Triple("ff", "フルサイズ", 1.0), Triple("apsc", "APS-C", 1.5), Triple("apsc_canon", "APS-C（キヤノン）", 1.6), Triple("m43", "マイクロフォーサーズ", 2.0), Triple("one", "1型", 2.7))

    private fun preset(id: String, name: String, mode: String, iso: Int, shutter: String, aperture: String, minShutter: String, evBias: Double, metering: String, note: String) =
        jsonOf("id" to id, "name" to name, "mode" to mode, "iso" to iso, "shutter" to shutter, "aperture" to aperture, "minShutter" to minShutter, "evBias" to evBias, "metering" to metering, "note" to note)

    private val DEFAULT_PRESETS = listOf(
        preset("rail", "鉄道撮影", "S", 400, "1/500", "5.6", "1/500", 0.0, "center", "被写体ブレを避けるためシャッター速度優先。編成の前面に測光。"),
        preset("bus", "バス撮影", "S", 400, "1/250", "5.6", "1/250", 0.0, "center", "バスは速度が低いため1/250でも止まる。側面を撮る場合は背景の明暗差に注意。"),
        preset("night", "夜間撮影", "A", 3200, "1/60", "2.8", "1/30", 0.0, "spot", "開放絞り＋高ISO。看板や街灯に引っ張られないようスポット測光で。"),
        preset("dark-rail", "闇鉄", "A", 6400, "1/30", "2.8", "1/15", -1.0, "spot", "黒つぶれを許容してでも前照灯を残す。露出補正はマイナス寄り。"),
        preset("pan", "流し撮り", "S", 100, "1/30", "11", "1/15", 0.0, "average", "背景を流すため意図的にスローSS。NDフィルターが必要な場合あり。"),
        preset("action", "動体撮影", "S", 800, "1/1000", "4", "1/1000", 0.0, "center", "高速SSを優先。暗い現場ではISOを上げてSSを確保。"),
        preset("landscape", "風景撮影", "A", 100, "1/125", "8", "1/30", 0.0, "average", "F8前後で周辺まで解像。三脚前提ならSSは気にしない。"),
        preset("rain", "雨天撮影", "S", 800, "1/250", "4", "1/250", 0.7, "average", "雨粒と濡れた路面は暗く写るためプラス補正。レンズの水滴に注意。"),
        preset("snow", "雪景色撮影", "A", 100, "1/500", "8", "1/125", 1.3, "average", "雪は明るく写るためプラス補正。白飛びはヒストグラムで確認。"),
        preset("station", "駅構内撮影", "S", 1600, "1/160", "2.8", "1/160", 0.0, "center", "構内は光源が混在。蛍光灯のちらつきに注意し、中央重点で測光。"),
    )

    fun defaultCalibration() = jsonOf("global" to 0, "sensitivity" to 1, "isoFactor" to 1, "byCamera" to JsonObject(emptyMap()), "byLens" to JsonObject(emptyMap()), "byMode" to JsonObject(emptyMap()))
    private val DEFAULT_LAST = jsonOf("cameraId" to "", "lensId" to "", "comboId" to "", "presetId" to "rail", "mode" to "S", "metering" to "center", "iso" to 400, "shutter" to "1/500", "aperture" to "2.8", "evComp" to 0)

    fun emptyState(): JsonObject = jsonOf("cameras" to JsonArray(emptyList()), "lenses" to JsonArray(emptyList()), "combos" to JsonArray(emptyList()), "presets" to JsonArray(DEFAULT_PRESETS),
        "calibration" to defaultCalibration(), "history" to JsonArray(emptyList()), "last" to DEFAULT_LAST)

    private fun merge(saved: JsonObject?): JsonObject {
        val base = emptyState()
        if (saved == null) return base
        val m = (base + saved).toMutableMap()
        m["calibration"] = JsonObject(defaultCalibration() + ((saved["calibration"] as? JsonObject) ?: emptyMap()))
        m["last"] = JsonObject(DEFAULT_LAST + ((saved["last"] as? JsonObject) ?: emptyMap()))
        listOf("cameras", "lenses", "combos", "history").forEach { k -> m[k] = saved[k] as? JsonArray ?: JsonArray(emptyList()) }
        m["presets"] = (saved["presets"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: base["presets"]!!
        return JsonObject(m)
    }

    private fun prefs() = ctx.getSharedPreferences("logic_storage", Context.MODE_PRIVATE)

    fun init(context: Context) {
        ctx = context.applicationContext
        _state.value = runCatching { merge(prefs().getString(KEY, null)?.let { Json.parse(it) as? JsonObject }) }.getOrDefault(emptyState())
    }

    fun reload(): JsonObject { init(ctx); return _state.value }

    fun commit(next: JsonObject) { _state.value = next; runCatching { prefs().edit().putString(KEY, next.toString()).apply() } }
    fun patch(vararg values: Pair<String, Any?>) = commit(JsonObject(_state.value + values.associate { it.first to toJson(it.second) }))
    fun patchLast(vararg values: Pair<String, Any?>) {
        val last = (_state.value["last"] as? JsonObject) ?: DEFAULT_LAST
        patch("last" to JsonObject(last + values.associate { it.first to toJson(it.second) }))
    }

    fun newId(prefix: String = "id") = "$prefix-${System.currentTimeMillis().toString(36)}-${(Math.random() * 6e7).toLong().toString(36)}"

    /** 一覧（cameras/lenses/combos/presets/history）に追加・更新する。返り値は id。 */
    fun upsert(key: String, item: JsonObject): String {
        val list = (_state.value[key] as? JsonArray)?.toList() ?: emptyList()
        val id = item.str("id")?.takeIf { it.isNotBlank() } ?: newId(key.take(3))
        val exists = list.any { it.str("id") == id }
        val merged = { row: JsonElement -> JsonObject(((row as? JsonObject) ?: emptyMap()) + item + ("id" to toJson(id))) }
        val next = if (exists) list.map { if (it.str("id") == id) merged(it) else it } else listOf(JsonObject(item + ("id" to toJson(id)))) + list
        patch(key to JsonArray(next))
        return id
    }

    fun remove(key: String, id: String) = patch(key to JsonArray(((_state.value[key] as? JsonArray)?.toList() ?: emptyList()).filter { it.str("id") != id }))

    // ---- 露出計画面の「いま測っている値」（Web: sessionStorage nara-meter-live-v1。30分で無効） ----
    @Volatile private var live: Pair<Long, JsonObject>? = null
    fun saveLive(values: JsonObject) { live = System.currentTimeMillis() to values }
    fun readLive(): JsonObject? = live?.takeIf { System.currentTimeMillis() - it.first <= 30 * 60_000 }?.let { JsonObject(it.second + ("at" to toJson(it.first))) }
}
