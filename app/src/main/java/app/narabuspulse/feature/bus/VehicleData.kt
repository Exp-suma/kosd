package app.narabuspulse.feature.bus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Net
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.toJson
import app.narabuspulse.ui.kit.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

data class Gps(val lat: Double, val lon: Double, val at: Long?)

/** GPS 値として使えるか（Web の isValidGps と同じ判定）。 */
fun Gps?.isValid(): Boolean {
    if (this == null) return false
    if (!lat.isFinite() || !lon.isFinite()) return false
    if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) return false
    return Math.abs(lat) <= 90 && Math.abs(lon) <= 180
}

class GpsState(val byCode: Map<String, Gps>, val loading: Boolean, val error: String?)

/** 車両ごとの最新 GPS（Web の useVehicleGps）。通信モードの gpsMs ごとに更新する。 */
@Composable
fun rememberVehicleGps(refreshKey: Any?): GpsState {
    var state by remember { mutableStateOf(GpsState(emptyMap(), true, null)) }
    val plan by Net.plan.collectAsState()
    LaunchedEffect(refreshKey, plan.gpsMs) {
        while (true) {
            try {
                val res = Api.rpc("server-fns#getVehicleGpsStates")
                val map = (res.obj ?: JsonObject(emptyMap())).mapNotNull { (code, v) ->
                    val lat = v.dbl("lat"); val lon = v.dbl("lon")
                    if (lat == null || lon == null) null else code to Gps(lat, lon, v.long("at"))
                }.toMap()
                state = GpsState(map, false, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = GpsState(state.byCode, false, e.userMessage().ifBlank { "車両のGPS情報を取得できませんでした" })
            }
            delay(plan.gpsMs)
        }
    }
    return state
}

class StopCoordsState(
    val coords: Map<String, Gps>,
    val pending: Boolean,
    val error: String?,
    val unresolved: Int,
    val reload: () -> Unit,
)

private fun JsonElement?.toCoordMap(): Map<String, Gps> = (this?.obj ?: JsonObject(emptyMap())).mapNotNull { (k, v) ->
    val lat = v.dbl("lat"); val lon = v.dbl("lon")
    if (lat == null || lon == null) null else k to Gps(lat, lon, null)
}.toMap()

private fun Map<String, Gps>.toJsonCoords(): JsonObject = JsonObject(mapValues { (_, c) -> jsonOf("lat" to c.lat, "lon" to c.lon) })

/**
 * 停留所名 → 座標（Web の useOsmStops）。
 * 端末の保存済み座標 → アプリ共通の座標キャッシュ → OpenStreetMap の順に探す。
 */
@Composable
fun rememberStopCoords(names: List<String>): StopCoordsState {
    val key = names.joinToString("\u0001")
    var retry by remember { mutableIntStateOf(0) }
    var coords by remember { mutableStateOf<Map<String, Gps>>(emptyMap()) }
    var pending by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var unresolved by remember { mutableIntStateOf(0) }
    val plan by Net.plan.collectAsState()

    LaunchedEffect(key, retry) {
        if (names.isEmpty()) { coords = emptyMap(); pending = false; error = null; unresolved = 0; return@LaunchedEffect }
        val local = Logic.call("bus/osm", "readCachedCoords", names)
        var found = local.objAt("coords").toCoordMap()
        var missing = local.list("missing").mapNotNull { (it as? JsonPrimitive)?.content }
        coords = found; pending = true; error = null; unresolved = 0
        try {
            if (missing.isNotEmpty()) {
                runCatching {
                    val shared = Api.rpc("server-fns#listStopCoords").items()
                    val fromDb = shared.mapNotNull { row ->
                        val n = row.str("name") ?: return@mapNotNull null
                        val lat = row.dbl("lat"); val lon = row.dbl("lon")
                        if (n in missing && lat != null && lon != null) n to Gps(lat, lon, null) else null
                    }.toMap()
                    if (fromDb.isNotEmpty()) {
                        Logic.call("bus/osm", "saveResolved", missing, fromDb.toJsonCoords())
                        found = found + fromDb
                        missing = missing.filter { it !in found }
                    }
                    coords = found
                }
            }
            if (missing.isEmpty()) return@LaunchedEffect
            if (plan.low && retry == 0) {
                error = "低速通信のため地図検索を省略しました。「再取得」で座標を取得できます。"
                unresolved = missing.count { it !in found }
                return@LaunchedEffect
            }
            val res = Api.rpc("server-fns#resolveStopCoords", jsonOf("names" to missing, "known" to found.toJsonCoords()))
            val got = res.objAt("found").toCoordMap()
            if (got.isEmpty() && res.str("error") != null) {
                error = "OpenStreetMap から停留所の座標を取得できませんでした（時間をおいて再取得してください）"
                unresolved = missing.count { it !in found }
                return@LaunchedEffect
            }
            Logic.call("bus/osm", "saveResolved", missing, got.toJsonCoords())
            if (got.isNotEmpty()) runCatching {
                Api.rpc("server-fns#saveStopCoords", jsonOf("items" to got.map { (n, c) -> jsonOf("name" to n, "lat" to c.lat, "lon" to c.lon) }))
            }
            found = found + got
            coords = found
            unresolved = missing.count { it !in found }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.userMessage()
            unresolved = missing.count { it !in found }
        } finally {
            pending = false
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return StopCoordsState(coords, pending, error, unresolved) {
        pending = true; error = null; unresolved = 0
        scope.launch { Logic.call("bus/osm", "forgetStops", names); retry++ }
    }
}

/** JS の数値の文字列表現（34.0 → "34"）。キーを Web と一致させるため。 */
fun jsNum(d: Double): String = if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

private fun round5(n: Double) = (n * 1e5).roundToLong() / 1e5

fun legKey(a: Gps, b: Gps) = "${jsNum(round5(a.lat))},${jsNum(round5(a.lon))}>${jsNum(round5(b.lat))},${jsNum(round5(b.lon))}"

data class RouteStop(val name: String, val lat: Double, val lon: Double)
data class RouteSpec(val route: String, val stops: List<RouteStop>)
data class RoadLine(val route: String, val coords: List<Pair<Double, Double>>)
data class RoadLeg(val route: String, val from: String, val to: String, val distance: Double)
private data class Segment(val coords: List<Pair<Double, Double>>, val distance: Double)

class RoadPathsState(
    val lines: List<RoadLine>,
    val legs: List<RoadLeg>,
    val totalDistance: Double,
    val missing: Int,
    val pending: Boolean,
    val error: String,
    val reload: () -> Unit,
)

/** 停留所の並びから道路に沿った経路（Web の useRoadPaths）。 */
@Composable
fun rememberRoadPaths(routes: List<RouteSpec>, enabled: Boolean): RoadPathsState {
    var segments by remember { mutableStateOf<Map<String, Segment>>(emptyMap()) }
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val key = routes.joinToString("|") { r -> r.route + ":" + r.stops.joinToString(";") { "${it.name},${round5(it.lat)},${round5(it.lon)}" } }

    LaunchedEffect(key, enabled, retry) {
        val pairs = LinkedHashMap<String, Pair<RouteStop, RouteStop>>()
        routes.forEach { r -> r.stops.zipWithNext().forEach { (a, b) -> pairs.putIfAbsent(legKey(Gps(a.lat, a.lon, null), Gps(b.lat, b.lon, null)), a to b) } }
        if (!enabled || pairs.isEmpty()) { pending = false; return@LaunchedEffect }
        pending = true; error = ""
        val errors = mutableListOf<String>()
        pairs.values.chunked(12).forEach { chunk ->
            try {
                val res = Api.rpc("server-fns#getRoadPaths", jsonOf("pairs" to chunk.map { (a, b) -> jsonOf("from" to jsonOf("lat" to a.lat, "lon" to a.lon), "to" to jsonOf("lat" to b.lat, "lon" to b.lon)) }))
                val found = (res.objAt("segments") ?: JsonObject(emptyMap())).mapNotNull { (k, v) ->
                    val coords = v.list("coords").mapNotNull { p -> val a = (p as? JsonArray) ?: return@mapNotNull null; (a[0] as JsonPrimitive).content.toDouble() to (a[1] as JsonPrimitive).content.toDouble() }
                    if (coords.size > 1) k to Segment(coords, v.dbl("distance") ?: 0.0) else null
                }.toMap()
                if (found.isNotEmpty()) segments = segments + found
                res.list("errors").forEach { errors.add((it as? JsonPrimitive)?.content ?: it.toString()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors.add(e.userMessage())
            }
        }
        pending = false
        error = errors.firstOrNull().orEmpty()
    }

    // 区間をつないで線と距離にする（Web の assemble と同じ）
    val lines = mutableListOf<RoadLine>()
    val legs = mutableListOf<RoadLeg>()
    var total = 0.0
    var missing = 0
    routes.forEach { (route, stops) ->
        var current = mutableListOf<Pair<Double, Double>>()
        for (i in 1 until stops.size) {
            val a = stops[i - 1]; val b = stops[i]
            val ga = Gps(a.lat, a.lon, null); val gb = Gps(b.lat, b.lon, null)
            val seg = segments[legKey(ga, gb)] ?: segments[legKey(gb, ga)]?.let { Segment(it.coords.reversed(), it.distance) }
            if (seg == null) {
                missing++
                if (current.size > 1) { lines.add(RoadLine(route, current)); current = mutableListOf() }
                continue
            }
            if (current.isEmpty()) current.addAll(seg.coords) else current.addAll(seg.coords.drop(1))
            legs.add(RoadLeg(route, a.name, b.name, seg.distance))
            total += seg.distance
        }
        if (current.size > 1) lines.add(RoadLine(route, current))
    }
    return RoadPathsState(lines, legs, total, missing, pending, error) { retry++ }
}

class RunsState(val runs: List<JsonElement>, val loading: Boolean, val error: String, val reload: () -> Unit)

/** 車両の運用記録（直近 14 日・Web の useVehicleRuns）。 */
@Composable
fun rememberVehicleRuns(vehicle: String?, days: Int = 14): RunsState {
    var runs by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(vehicle, days, retry) {
        if (vehicle.isNullOrBlank()) { runs = emptyList(); loading = false; error = ""; return@LaunchedEffect }
        loading = true; error = ""
        try {
            val now = System.currentTimeMillis()
            val rows = Api.rpc("server-fns#getVehicleRuns", jsonOf("vehicle" to vehicle, "from" to BusFormat.dayKey(now - (days - 1) * 86_400_000L), "to" to BusFormat.dayKey(now))).items()
            runs = rows.map { Logic.call("bus/predict", "normalizeDbRun", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            runs = emptyList(); error = e.userMessage()
        } finally { loading = false }
    }
    return RunsState(runs, loading, error) { retry++ }
}

class PositionLogsState(val points: List<JsonElement>, val loading: Boolean, val error: String, val reload: () -> Unit)

/** 指定日の位置履歴（再生用・Web の usePositionLogs）。 */
@Composable
fun rememberPositionLogs(vehicle: String?, date: String): PositionLogsState {
    var points by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(vehicle, date, retry) {
        if (vehicle.isNullOrBlank() || date.isBlank()) { points = emptyList(); return@LaunchedEffect }
        loading = true; error = ""; points = emptyList()
        try {
            points = Api.rpc("server-fns#getPositionLogs", jsonOf("vehicle" to vehicle, "date" to date)).items()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.userMessage()
        } finally { loading = false }
    }
    return PositionLogsState(points, loading, error) { retry++ }
}

/** 画面を開いている間の観測を運用記録へ保存する（Web の useObservationRecorder）。 */
object ObservationRecorder {
    private var lastSentAt = 0L
    private val lastState = HashMap<String, String>()

    suspend fun record(vehicles: List<Vehicle>, coords: Map<String, Gps>) {
        if (vehicles.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastSentAt < 60_000) return
        val items = vehicles.mapNotNull { v ->
            if (v.name.isEmpty() || v.stop.isEmpty() || v.lastCheck == null) return@mapNotNull null
            val op = parseOperation(v.operation)
            val stateKey = "${v.stop}|${op.route}|${op.destination}"
            if (lastState[v.name] == stateKey) return@mapNotNull null
            val point = coords[v.stop]
            jsonOf(
                "vehicle" to v.name, "at" to v.lastCheck, "route" to op.route, "destination" to op.destination,
                "origin" to op.origin, "operation" to v.operation, "stop" to v.stop,
                "lat" to point?.lat, "lon" to point?.lon, "url" to v.url,
            ) to (v.name to stateKey)
        }
        if (items.isEmpty()) return
        lastSentAt = now
        items.forEach { (_, s) -> lastState[s.first] = s.second }
        runCatching { Api.rpc("server-fns#recordObservations", jsonOf("items" to items.map { it.first })) }
    }
}
