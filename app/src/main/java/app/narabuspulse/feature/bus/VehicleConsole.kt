package app.narabuspulse.feature.bus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Net
import app.narabuspulse.core.dbl
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.str
import app.narabuspulse.core.toJson
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.OSM_ATTRIBUTION
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** 車両の位置（ok = 地図に出せる / stale = 更新が止まっている / none = 位置なし）。 */
data class VehiclePosition(val kind: String, val lat: Double? = null, val lon: Double? = null, val at: Long? = null)

private val TABS = listOf(
    "info" to "基本情報", "current" to "現在の運行", "route" to "走行経路", "sun" to "太陽光線",
    "runs" to "運用履歴", "next" to "次の運行予測", "playback" to "履歴再生",
)

const val DEFAULT_POSITION_NOTE =
    "走行経路は、選択した系統の停留所をOpenStreetMapの道路データ（OSRM）で結んだものです。バスの現在位置は収集処理が保存した実測GPSを表示しています。GPSが提供されていない、または30分以上更新が止まっている車両は、停留所位置や他車両での代用をせず位置を表示しません。"

private val ROUTE_COLORS = listOf(Color(0xFF2F6FB0), Color(0xFF8E5BC1), Color(0xFF27AE60), Color(0xFFD99A0E))
val MARKER = Brand.Orange

/** 計算処理（logic.js）に渡す車両データ（シートの値 + 運用の分解結果）。 */
fun Vehicle.toLogicJson(staleMinutes: Int, now: Long): JsonObject {
    val op = parseOperation(operation)
    val st = state(staleMinutes, now)
    return JsonObject(
        (raw.obj ?: JsonObject(emptyMap())) + mapOf(
            "op" to jsonOf("raw" to op.raw, "route" to op.route, "origin" to op.origin, "destination" to op.destination, "noOperation" to op.noOperation),
            "state" to jsonOf("key" to st.name.lowercase(), "label" to st.label),
        ),
    )
}

/** 現在時刻（15 秒ごとに更新。Web の useNow(15000)）。 */
@Composable
fun rememberNow(intervalMs: Long = 15_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) { while (true) { delay(intervalMs); now = System.currentTimeMillis() } }
    return now
}

/**
 * 車両位置コンソール（Web: components/bus/VehicleConsole.jsx）。
 * 基本情報・現在の運行・走行経路・太陽光線・運用履歴・次の運行予測・履歴再生。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleConsole(
    vehicles: List<Vehicle>,
    routeStops: Map<String, List<String>>,
    logs: List<OpLog>,
    positions: Map<String, VehiclePosition>,
    positionsLoading: Boolean = false,
    positionsError: String? = null,
    updatedAt: Long? = null,
    staleMinutes: Int = 15,
    autoUpdateLabel: String = "",
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
    extraCoords: Map<String, Gps> = emptyMap(),
    providedTimeline: List<JsonElement>? = null,
    infoRows: List<Pair<String, String>>? = null,
    infoNote: String? = null,
    positionNote: String = DEFAULT_POSITION_NOTE,
    loadingText: String = "車両のGPS位置を読み込んでいます…",
    staleText: String = "GPSの更新が止まっています",
    missingText: String = "この車両のGPS位置が提供されていません（停留所位置での代用はしません）",
    initialSelected: String = "",
    initialTab: String = "info",
    header: (@Composable () -> Unit)? = null,
) {
    val now = rememberNow()
    var selected by remember { mutableStateOf(initialSelected) }
    var tab by remember { mutableStateOf(initialTab) }
    var follow by remember { mutableStateOf(false) }
    var stopsOpen by remember { mutableStateOf(false) }
    var routeKeys by remember { mutableStateOf(listOf<String>()) }
    var routeTouched by remember { mutableStateOf(false) }
    var sunMode by remember { mutableStateOf("now") }
    var sunDate by remember { mutableStateOf(BusFormat.dayKey(now)) }
    var sunTime by remember { mutableStateOf(BusFormat.time(now)) }
    var pickedStop by remember { mutableStateOf("") }
    var histDate by remember { mutableStateOf(BusFormat.dayKey(now)) }
    var playDate by remember { mutableStateOf(BusFormat.dayKey(now)) }

    val current = vehicles.firstOrNull { it.name == selected } ?: vehicles.firstOrNull()
    val currentOp = remember(current?.operation) { parseOperation(current?.operation) }
    val vehiclesJson = remember(vehicles, now) { JsonArray(vehicles.map { it.toLogicJson(staleMinutes, now) }) }
    val routeStopsJson = remember(routeStops) { toJson(routeStops) }

    // 画面を開いている間の観測を積み上げる（車両の並びが変わったときだけ記録する）
    val trackKey = vehicles.joinToString("|") { "${it.name}:${it.stop}" }
    val tracks by produceState<JsonElement>(JsonNull, trackKey) {
        if (trackKey.isNotEmpty()) value = Logic.call("bus/track", "recordTracks", vehiclesJson, System.currentTimeMillis())
    }

    val routeOptions = remember(routeStops) {
        routeStops.filter { it.value.size > 1 }.map { it.key to it.value.size }.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.JAPANESE)) { it.first })
    }
    LaunchedEffect(currentOp.route, routeOptions, routeTouched) {
        if (!routeTouched && currentOp.route.isNotEmpty() && routeOptions.any { it.first == currentOp.route }) routeKeys = listOf(currentOp.route)
    }

    val stopNames = remember(vehicles, routeKeys, routeStops, currentOp.route) {
        val set = sortedSetOf<String>(java.text.Collator.getInstance(java.util.Locale.JAPANESE))
        vehicles.mapNotNullTo(set) { it.stop.ifBlank { null } }
        routeKeys.forEach { r -> routeStops[r]?.let(set::addAll) }
        routeStops[currentOp.route]?.let(set::addAll)
        set.toList()
    }
    val geo = rememberStopCoords(stopNames.filter { it !in extraCoords })
    val coords = remember(geo.coords, extraCoords) { geo.coords + extraCoords }
    val coordsJson = remember(coords) { JsonObject(coords.mapValues { (_, c) -> jsonOf("lat" to c.lat, "lon" to c.lon) }) }

    val timeline by produceState<List<JsonElement>>(emptyList(), providedTimeline, tracks, current?.name, current?.stop, routeStops) {
        value = providedTimeline?.takeIf { it.isNotEmpty() }
            ?: if (current != null) (Logic.call("bus/track", "buildStopTimeline", tracks, current.toLogicJson(staleMinutes, now), routeStopsJson) as? JsonArray)?.toList().orEmpty()
            else emptyList()
    }
    val currentTrack by produceState<JsonElement>(JsonNull, tracks, current?.name, coordsJson) {
        val pts = current?.let { tracks.obj?.get(it.name) } ?: JsonArray(emptyList())
        value = Logic.call("bus/track", "withCoordinates", pts, coordsJson)
    }

    val mapped = vehicles.filter { positions[it.name]?.kind == "ok" }
    val currentPosition = current?.let { positions[it.name] } ?: VehiclePosition("none")

    val selectedRoutes = remember(routeKeys, routeStops, coords) {
        routeKeys.map { r -> RouteSpec(r, routeStops[r].orEmpty().mapNotNull { n -> coords[n]?.let { RouteStop(n, it.lat, it.lon) } }) }.filter { it.stops.size > 1 }
    }
    val road = rememberRoadPaths(selectedRoutes, enabled = (tab == "route" || tab == "sun") && selectedRoutes.isNotEmpty())

    // 太陽光線
    val sunStops by produceState<List<JsonElement>>(emptyList(), currentOp.route, current?.stop, coords) {
        val names = routeStops[currentOp.route].orEmpty()
        val out = mutableListOf<JsonElement>()
        var prev: Gps? = null
        for (n in names) {
            val c = coords[n] ?: continue
            val heading = prev?.let { p -> Logic.call("bus/track", "bearingBetween", jsonOf("lat" to p.lat, "lon" to p.lon), jsonOf("lat" to c.lat, "lon" to c.lon)) }
            out.add(jsonOf("name" to n, "lat" to c.lat, "lon" to c.lon, "heading" to heading, "state" to if (n == current?.stop) "current" else null))
            prev = c
        }
        value = out
    }
    val sunPoint = coords[pickedStop] ?: current?.stop?.let { coords[it] }
    val sunAtMs = if (sunMode == "now") now else runCatching {
        java.time.OffsetDateTime.parse("${sunDate}T${sunTime.ifBlank { "12:00" }}:00+09:00").toInstant().toEpochMilli()
    }.getOrDefault(now)
    val sun by produceState<JsonElement>(JsonNull, sunAtMs, sunPoint) {
        value = sunPoint?.let { Logic.call("bus/solar", "solarPosition", sunAtMs, it.lat, it.lon) } ?: jsonOf("azimuth" to 0, "altitude" to 0)
    }
    val sunT by produceState<JsonElement>(JsonNull, sunAtMs, sunPoint) {
        value = sunPoint?.let { Logic.call("bus/solar", "sunTimes", sunAtMs, it.lat, it.lon) } ?: JsonObject(emptyMap())
    }

    // 運用記録（DB）とログシートの実績を統合
    val dbRuns = rememberVehicleRuns(current?.name)
    val allRuns by produceState<List<JsonElement>>(emptyList(), dbRuns.runs, logs, current?.name) {
        val name = current?.name
        if (name == null) { value = emptyList(); return@produceState }
        val logsJson = JsonArray(logs.filter { it.vehicle == name }.map { jsonOf("row" to it.row, "at" to it.at, "vehicle" to it.vehicle, "kind" to it.kind, "from" to it.from, "to" to it.to) })
        val logRuns = (Logic.call("bus/predict", "runsFromLogs", logsJson, name) as? JsonArray)?.map { run ->
            val op = parseOperation(run.str("operation"))
            jsonOf(
                "vehicle" to name, "operation" to run.str("operation"), "route" to op.route, "destination" to op.destination,
                "fromStop" to op.origin, "startAt" to run.long("startAt"), "endAt" to run.long("endAt"),
                "status" to if (run.long("endAt") != null) "ended" else "running", "source" to "log",
            )
        } ?: emptyList()
        value = (Logic.call("bus/predict", "mergeRuns", dbRuns.runs, logRuns) as? JsonArray)?.toList() ?: emptyList()
    }
    val histRuns = allRuns.filter { BusFormat.dayKey(it.long("startAt")) == histDate }
    val todayKey = BusFormat.dayKey(now)
    val todayRun = allRuns.firstOrNull { it.str("status") == "running" && BusFormat.dayKey(it.long("startAt")) == todayKey }

    LaunchedEffect(vehicles, coords) { ObservationRecorder.record(vehicles, coords) }

    fun showRouteOnMap(route: String?) {
        val target = route?.ifBlank { null } ?: currentOp.route.ifBlank { null }
        if (target != null) { routeTouched = true; routeKeys = listOf(target) }
        tab = "route"
    }

    val plan by Net.plan.collectAsState()

    ScreenList {
        if (header != null) item { header() }
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("車両を選択", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectField(vehicles.map { it.name to "${it.name}（${it.state(staleMinutes, now).label}）" }, current?.name ?: "", { selected = it })
                    if (onRefresh != null) SmallButton("更新", onRefresh, enabled = !refreshing, icon = Icons.Outlined.Refresh)
                }
                Text("$autoUpdateLabel・最終更新 ${BusFormat.dateTime(updatedAt)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        item { ChipRow(TABS, tab, { tab = it }) }

        if (tab == "playback") {
            item {
                HistoryPlayback(current, playDate, { playDate = it }, onJumpToRoute = { showRouteOnMap(histRuns.firstOrNull()?.str("route")) })
            }
        } else {
            item {
                when (tab) {
                    "info" -> VehicleInfoTab(current, current?.state(staleMinutes, now), now, infoRows, infoNote)
                    "current" -> CurrentRunTab(current, current?.state(staleMinutes, now), timeline, onOpenStops = { stopsOpen = true }, onShowRoute = { showRouteOnMap(null) })
                    "route" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RouteTab(routeOptions, routeKeys, onToggle = { r ->
                            routeTouched = true
                            routeKeys = if (r in routeKeys) routeKeys - r else if (routeKeys.size >= 4) routeKeys else routeKeys + r
                        }, road)
                        if (current != null) VehicleTrackPanel(current, currentTrack)
                    }
                    "sun" -> SunTab(
                        sunMode, { sunMode = it }, sunDate, sunTime, { sunMode = "pick"; sunDate = it }, { sunMode = "pick"; sunTime = it },
                        sunAtMs, sun, sunT, sunStops, pickedStop, { pickedStop = it },
                    )
                    "runs" -> RunHistoryTab(
                        current?.name.orEmpty(), histDate, BusFormat.dayKey(now - 13 * 86_400_000L), todayKey, { histDate = it },
                        histRuns, dbRuns.runs.size, dbRuns.loading, dbRuns.error, dbRuns.reload,
                        onPlayback = { playDate = histDate; tab = "playback" },
                    )
                    "next" -> PredictionTab(
                        current,
                        jsonOf("name" to current?.name, "route" to currentOp.route, "destination" to currentOp.destination, "startAt" to todayRun?.long("startAt"), "stop" to current?.stop),
                        allRuns, onShowRoute = { showRouteOnMap(it) }, onPlayback = { playDate = histDate; tab = "playback" },
                    )
                }
            }
            item {
                val title = when (tab) { "sun" -> "太陽光線と地図"; "route" -> "走行経路（OpenStreetMap）"; else -> "現在位置（OpenStreetMap）" }
                val note = when (tab) {
                    "route" -> "${selectedRoutes.size} 系統・道路形状 ${road.legs.size} 区間"
                    "sun" -> "太陽の方位を線で表示"
                    else -> "選択中の車両のみ表示"
                }
                Panel(title = title, subtitle = note, action = {
                    SmallButton(if (follow) "追跡中" else "追跡", { follow = !follow }, enabled = mapped.any { it.name == current?.name }, icon = Icons.Outlined.GpsFixed, primary = follow)
                }) {
                    VehicleMapView(
                        tab, mapped, positions, current, coords, road, selectedRoutes, currentTrack, follow,
                        if (tab == "sun") sunPoint else null, sun, now, plan.tiles,
                    ) { selected = it }
                    if (current != null && mapped.none { it.name == current.name }) {
                        Note(
                            when {
                                positionsLoading -> loadingText
                                currentPosition.kind == "stale" -> "$staleText（最終 ${BusFormat.time(currentPosition.at)}）。位置は推測しません。"
                                !positionsError.isNullOrBlank() -> positionsError
                                else -> missingText
                            },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (geo.pending) Text("座標取得中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (road.pending) Text("経路取得中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (geo.error != null || geo.unresolved > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text(geo.error ?: "${geo.unresolved} か所の停留所の位置を特定できませんでした。時間をおいて再取得してください。", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        SmallButton("再取得", geo.reload, icon = Icons.Outlined.Refresh)
                    }
                    if ((tab == "route" || tab == "sun") && road.error.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("${road.error}（直線での代用表示は行いません）", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        SmallButton("再取得", road.reload, icon = Icons.Outlined.Refresh)
                    }
                    Text(positionNote, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Text(OSM_ATTRIBUTION, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { StopListPanel(vehicles, coords, current?.name) { selected = it } }
        }
    }

    if (stopsOpen && current != null) {
        StopTimelineSheet(current, timeline, onClose = { stopsOpen = false }, onRefresh = onRefresh, refreshing = refreshing)
    }
}

@Composable
private fun VehicleMapView(
    tab: String,
    mapped: List<Vehicle>,
    positions: Map<String, VehiclePosition>,
    current: Vehicle?,
    coords: Map<String, Gps>,
    road: RoadPathsState,
    routes: List<RouteSpec>,
    currentTrack: JsonElement,
    follow: Boolean,
    sunPoint: Gps?,
    sun: JsonElement,
    now: Long,
    tiles: Boolean,
    onSelect: (String) -> Unit,
) {
    var showAnyway by remember { mutableStateOf(false) }
    if (!tiles && !showAnyway) {
        Column(Modifier.fillMaxWidth().height(160.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("超低速通信のため、地図は読み込んでいません。\n現在の停留所と停留所一覧は上のパネルに表示しています。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SmallButton("地図を表示", { showAnyway = true })
        }
        return
    }
    val pins = mutableListOf<MapPin>()
    val lines = mutableListOf<MapLine>()
    val routeMode = tab == "route" || tab == "sun"
    if (routeMode) {
        road.lines.forEach { l -> lines.add(MapLine(l.coords, ROUTE_COLORS[routes.indexOfFirst { it.route == l.route }.coerceAtLeast(0) % ROUTE_COLORS.size], 7f)) }
        if (tab == "route") routes.forEachIndexed { i, r ->
            r.stops.forEachIndexed { j, s ->
                val end = j == 0 || j == r.stops.lastIndex
                pins.add(MapPin("rs-${r.route}-$j", s.lat, s.lon, "${s.name}${if (j == 0) "（始）" else if (j == r.stops.lastIndex) "（終）" else ""}", "系統 ${r.route}", if (end) ROUTE_COLORS[i % ROUTE_COLORS.size] else Color.White, if (end) 14.dp else 9.dp))
            }
        }
    } else {
        // 選択中の車両の観測経路
        val pts = currentTrack.list("points").mapNotNull { p -> p.dbl("lat")?.let { lat -> p.dbl("lon")?.let { lat to it } } }.takeLast(14)
        if (pts.size > 1) lines.add(MapLine(pts, Color(0xFF2F6FB0).copy(alpha = 0.7f), 5f, dashed = true))
    }
    mapped.forEach { v ->
        val p = positions[v.name] ?: return@forEach
        val sel = v.name == current?.name
        if (!routeMode || sel) pins.add(MapPin("v-${v.name}", p.lat!!, p.lon!!, v.name, "${parseOperation(v.operation).raw.ifBlank { "運行なし" }}\nGPS ${BusFormat.time(p.at)}（${BusFormat.elapsed(p.at, now)}）", if (sel) MARKER else Color(0xFF2F6FB0), if (sel) 20.dp else 14.dp))
    }
    if (sunPoint != null) {
        pins.add(MapPin("sun-point", sunPoint.lat, sunPoint.lon, "撮影地点", "", Brand.Warning, 16.dp))
        val az = sun.dbl("azimuth") ?: 0.0
        val rad = Math.toRadians(az)
        val d = 0.004
        lines.add(MapLine(listOf(sunPoint.lat to sunPoint.lon, (sunPoint.lat + d * Math.cos(rad)) to (sunPoint.lon + d * Math.sin(rad) / Math.cos(Math.toRadians(sunPoint.lat)))), Brand.Warning, 8f))
    }
    val currentPos = current?.let { positions[it.name] }?.takeIf { it.kind == "ok" }
    val center = when {
        sunPoint != null -> sunPoint.lat to sunPoint.lon
        follow && currentPos != null -> currentPos.lat!! to currentPos.lon!!
        else -> null
    }
    val fitKey = when {
        routeMode -> "route:" + routes.joinToString { it.route } + ":" + road.lines.size
        currentPos != null -> "veh:" + current.name
        else -> "all:" + mapped.size
    }
    OsmMap(
        modifier = Modifier.fillMaxWidth().height(360.dp),
        pins = pins, lines = lines, center = center, zoom = 15.0,
        fitKey = if (center == null) fitKey else null,
        onPinClick = { p -> if (p.id.startsWith("v-")) onSelect(p.id.removePrefix("v-")) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopListPanel(vehicles: List<Vehicle>, coords: Map<String, Gps>, current: String?, onSelect: (String) -> Unit) {
    val stops = remember(vehicles) {
        vehicles.groupBy { it.stop.ifBlank { "（停留所情報なし）" } }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<Vehicle>>> { it.value.size }.thenComparator { a, b -> java.text.Collator.getInstance(java.util.Locale.JAPANESE).compare(a.key, b.key) })
    }
    Panel(title = "停留所一覧", action = { Text("${stops.size} か所 / ${vehicles.size} 台", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }) {
        stops.forEachIndexed { i, (stop, list) ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = Brand.Orange, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stop, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (coords[stop] != null) { Spacer(Modifier.width(6.dp)); Tag("地図あり") }
                    Spacer(Modifier.weight(1f))
                    Text("${list.size} 台", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(Modifier.padding(start = 18.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.forEach { v ->
                        val sel = v.name == current
                        val route = parseOperation(v.operation).route
                        Text(
                            v.name + if (route.isNotEmpty()) " 系統$route" else "",
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                                .border(1.dp, if (sel) Brand.Orange.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                .clickable { onSelect(v.name) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
