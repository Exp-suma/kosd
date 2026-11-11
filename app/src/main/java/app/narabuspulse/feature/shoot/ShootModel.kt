package app.narabuspulse.feature.shoot

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.narabuspulse.core.Api
import app.narabuspulse.core.Json
import app.narabuspulse.core.Logic
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.core.toJson
import app.narabuspulse.feature.bus.BusSnapshot
import app.narabuspulse.feature.bus.RoadPathsState
import app.narabuspulse.feature.bus.RouteSpec
import app.narabuspulse.feature.bus.RouteStop
import app.narabuspulse.feature.bus.StopCoordsState
import app.narabuspulse.feature.bus.parseOperation
import app.narabuspulse.feature.bus.rememberRoadPaths
import app.narabuspulse.feature.bus.rememberStopCoords
import app.narabuspulse.ui.kit.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

private val JST = ZoneId.of("Asia/Tokyo")
internal fun jstToday(): String = Instant.now().atZone(JST).toLocalDate().toString()
internal fun jstNowTime(): String = Instant.now().atZone(JST).toLocalTime().toString().take(5)
internal fun hhmm(minutes: Int): String { val c = minutes.coerceIn(0, 1439); return "%02d:%02d".format(c / 60, c % 60) }

val LAYER_DEFS = listOf("path" to "経路", "light" to "光線の色分け", "shapes" to "道路形状（右左折・カーブ）", "spots" to "撮影候補地点", "vehicles" to "車両の進行方向", "camera" to "撮影位置・撮影方向", "sunRay" to "太陽光線", "sun" to "太陽位置")

/** AI撮影支援の選択状態（タブを移っても保つ。Web の ShootProvider の state）。 */
object ShootPrefs {
    var routeName by mutableStateOf("")
    var date by mutableStateOf(jstToday())
    var mode by mutableStateOf("now")
    var time by mutableStateOf(jstNowTime())
    var purpose by mutableStateOf("normal")
    var stepMin by mutableIntStateOf(30)
    var focusId by mutableStateOf("")
    var sunMode by mutableStateOf("actual")
    var manualAzimuth by mutableStateOf(180.0)
    var manualAltitude by mutableStateOf(30.0)
    var thresholds by mutableStateOf(mapOf<String, Double>())
    var playing by mutableStateOf(false)
    var layers by mutableStateOf(LAYER_DEFS.associate { it.first to true })
    var favorites by mutableStateOf(listOf<JsonObject>())
    private var loaded = false

    private fun prefs(c: Context) = c.getSharedPreferences("logic_storage", Context.MODE_PRIVATE)
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching { (Json.parse(prefs(c).getString("nara-bus-shoot-layers-v1", null) ?: "{}") as JsonObject).forEach { (k, v) -> layers = layers + (k to (v.text == "true")) } }
        favorites = runCatching { (Json.parse(prefs(c).getString("nara-shoot-favorites-v1", null) ?: "[]") as JsonArray).mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())
    }
    fun toggleLayer(c: Context, key: String) {
        layers = layers + (key to !(layers[key] ?: true))
        runCatching { prefs(c).edit().putString("nara-bus-shoot-layers-v1", JsonObject(layers.mapValues { toJson(it.value) }).toString()).apply() }
    }
    private fun writeFavorites(c: Context, list: List<JsonObject>) { favorites = list; runCatching { prefs(c).edit().putString("nara-shoot-favorites-v1", JsonArray(list.take(200)).toString()).apply() } }
    fun addFavorite(c: Context, entry: JsonObject) {
        val id = entry.str("id") ?: "fav-${System.currentTimeMillis()}"
        writeFavorites(c, listOf(JsonObject(entry + mapOf("id" to toJson(id), "savedAt" to toJson(System.currentTimeMillis())))) + favorites.filter { it.str("id") != id })
    }
    fun updateFavorite(c: Context, id: String, vararg patch: Pair<String, Any?>) = writeFavorites(c, favorites.map { if (it.str("id") == id) JsonObject(it + patch.associate { p -> p.first to toJson(p.second) }) else it })
    fun removeFavorite(c: Context, id: String) = writeFavorites(c, favorites.filter { it.str("id") != id })

    fun setMinutes(value: Int) { mode = "set"; time = hhmm(value) }
    fun setSunAngle(azimuth: Double) { sunMode = "manual"; manualAzimuth = ((azimuth % 360) + 360) % 360 }
    fun setSunAltitude(altitude: Double) { sunMode = "manual"; manualAltitude = altitude.coerceIn(-5.0, 85.0) }
}

data class RouteOption(val route: String, val stops: Int, val vehicles: Int)
class WeatherState(val loading: Boolean, val error: String, val reload: () -> Unit)

/** 画面に渡す計算結果一式。r は glue/shoot#compute の戻り値。 */
class ShootModel(val r: JsonElement?, val meta: JsonElement?, val routeOptions: List<RouteOption>, val stops: List<RouteStop>, val geo: StopCoordsState, val road: RoadPathsState, val weather: WeatherState, val computing: Boolean) {
    val spots: List<JsonElement> get() = r.items("spots")
    val focus: JsonElement? get() = spots.firstOrNull { it.str("id") == r.str("focusId") }
}

/**
 * 監視データ・停留所座標・道路形状・気象をそろえ、Web と同じ計算（glue/shoot）で撮影支援の状態を作る。
 * view には画面ごとの追加条件（絞り込み・条件検索・撮影チャンス）を渡す。
 */
@Composable
fun rememberShootModel(data: BusSnapshot, view: JsonObject? = null): ShootModel {
    val context = LocalContext.current
    val p = ShootPrefs
    LaunchedEffect(Unit) { p.load(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = System.currentTimeMillis() } }
    val meta = produceState<JsonElement?>(null) { value = Logic.call("glue/shoot", "meta") }.value

    val routeStops = data.raw.objAt("routeStops")
    val routeOptions = remember(data) {
        val counts = data.vehicles.groupingBy { parseOperation(it.operation).route }.eachCount()
        routeStops.orEmpty().mapNotNull { (route, stops) -> (stops as? JsonArray)?.takeIf { it.size > 1 }?.let { RouteOption(route, it.size, counts[route] ?: 0) } }.sortedBy { it.route }
    }
    LaunchedEffect(routeOptions) { if (p.routeName.isBlank() || routeOptions.none { it.route == p.routeName }) (routeOptions.firstOrNull { it.vehicles > 0 } ?: routeOptions.firstOrNull())?.let { p.routeName = it.route } }
    val stopNames = remember(routeStops, p.routeName) { routeStops.list(p.routeName).map { it.text } }
    val geo = rememberStopCoords(stopNames)
    val stops = remember(stopNames, geo.coords) { stopNames.mapNotNull { n -> geo.coords[n]?.let { RouteStop(n, it.lat, it.lon) } } }
    val road = rememberRoadPaths(remember(p.routeName, stops) { if (stops.size > 1) listOf(RouteSpec(p.routeName, stops)) else emptyList() }, stops.size > 1)

    var weatherDays by remember { mutableStateOf(mapOf<String, JsonElement>()) }
    var weatherLoading by remember { mutableStateOf(false) }
    var weatherError by remember { mutableStateOf("") }
    var weatherRetry by remember { mutableIntStateOf(0) }
    var handledRetry by remember { mutableIntStateOf(0) }

    val vehicles = remember(data, p.routeName) {
        data.vehicles.mapNotNull { v -> val op = parseOperation(v.operation); if (p.routeName.isNotBlank() && op.route == p.routeName) jsonOf("name" to v.name, "stop" to v.stop, "route" to op.route, "destination" to op.destination) else null }
    }
    val linesJson = remember(road.lines) { JsonArray(road.lines.map { l -> jsonOf("route" to l.route, "coords" to JsonArray(l.coords.map { (la, lo) -> JsonArray(listOf(toJson(la), toJson(lo))) })) }) }
    val stopsJson = remember(stops) { JsonArray(stops.map { jsonOf("name" to it.name, "lat" to it.lat, "lon" to it.lon) }) }
    val nowKey = if (p.mode == "now") now else 0L
    var computing by remember { mutableStateOf(true) }
    val result = produceState<JsonElement?>(null, linesJson, stopsJson, nowKey, p.mode, p.date, p.time, p.purpose, p.stepMin, p.sunMode, p.manualAzimuth, p.manualAltitude, p.thresholds, weatherDays, vehicles, p.focusId, view?.toString()) {
        computing = true
        val out = Logic.call("glue/shoot", "compute", JsonObject(mapOf(
            "lines" to linesJson, "stops" to stopsJson, "stopNames" to toJson(stopNames), "route" to toJson(p.routeName), "now" to toJson(now), "mode" to toJson(p.mode), "date" to toJson(p.date), "time" to toJson(p.time),
            "purpose" to toJson(p.purpose), "stepMin" to toJson(p.stepMin), "sunMode" to toJson(p.sunMode), "manualSun" to jsonOf("azimuth" to p.manualAzimuth, "altitude" to p.manualAltitude), "thresholds" to toJson(p.thresholds),
            "weatherDays" to JsonObject(weatherDays), "vehicles" to JsonArray(vehicles), "focusId" to toJson(p.focusId)) + (view ?: emptyMap())))
        if (out is JsonObject) value = out
        computing = false
    }.value

    // 候補地点の気象（地点×日付。取得できなかった地点は空のままにし、推測で補完しない）
    val pointsKey = result.items("weatherPoints").joinToString("|") { it.str("key").orEmpty() }
    LaunchedEffect(pointsKey, p.date, weatherRetry) {
        val force = weatherRetry != handledRetry; handledRetry = weatherRetry
        val missing = result.items("weatherPoints").filter { force || it.str("key") !in weatherDays }
        if (missing.isEmpty()) { weatherLoading = false; return@LaunchedEffect }
        weatherLoading = true; weatherError = ""
        val found = HashMap<String, JsonElement>(); var err = ""
        missing.chunked(8).forEach { chunk ->
            try {
                val res = Api.rpc("shoot/server-fns#getShootWeather", jsonOf("date" to p.date, "points" to JsonArray(chunk)))
                res.objAt("days")?.forEach { (k, v) -> found[k] = v }
                res.list("errors").firstOrNull()?.text?.takeIf { it.isNotBlank() && err.isBlank() }?.let { err = it }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { if (err.isBlank()) err = e.userMessage().ifBlank { "気象データを取得できませんでした" } }
        }
        weatherDays = weatherDays + found; weatherError = err; weatherLoading = false
    }

    // 時間スライダーの再生（1 秒ごとに stepMin 進める）
    LaunchedEffect(p.playing, p.stepMin) {
        while (p.playing) {
            delay(1000)
            val next = (result.int("minutes") ?: 0) + p.stepMin
            if (next > (result.int("dayRange.end") ?: 1140)) { p.playing = false; break }
            p.setMinutes(next)
        }
    }
    LaunchedEffect(result.str("focusId")) { result.str("focusId")?.takeIf { it.isNotBlank() && it != p.focusId }?.let { p.focusId = it } }

    return ShootModel(result, meta, routeOptions, stops, geo, road, WeatherState(weatherLoading, weatherError) { weatherRetry++ }, computing)
}

@Suppress("unused") private fun keepLong(e: JsonElement?) = e.long("x")
