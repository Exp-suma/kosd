package app.narabuspulse.feature.bus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Net
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.at
import app.narabuspulse.core.text
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

private data class RouteOrder(
    val stops: List<JsonElement> = emptyList(),
    val car: JsonElement? = null,
    val date: String = "",
    val cachedAt: Long = 0,
    val loading: Boolean = false,
    val error: String = "",
    val errorKind: String = "",
    val stale: Boolean = false,
)

private val COORD_LABEL = mapOf("ok" to "取得済み", "pending" to "補完中", "missing" to "未登録", "error" to "取得失敗")
private val COORD_COLOR = mapOf("ok" to Brand.Success, "pending" to Brand.MutedFg, "missing" to Brand.Warning, "error" to Brand.Destructive)

/**
 * 停車停留所パネル（Web: components/bus/StopTimeline.jsx）。
 * バスロケの停留所順を即表示し、座標と予定時刻は後から補う。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StopTimelineSheet(vehicle: Vehicle, stops: List<JsonElement>, onClose: () -> Unit, onRefresh: (() -> Unit)?, refreshing: Boolean) {
    var order by remember(vehicle.url) { mutableStateOf(RouteOrder(loading = vehicle.url.isNotBlank())) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var force by remember { mutableStateOf(false) }

    // 1) 停留所順（route_order）：保存済みを即表示 → 期限切れなら取得
    LaunchedEffect(vehicle.url, reloadKey) {
        val car = Logic.call("bus/routeOrderCache", "carCodeFromUrl", vehicle.url).text
        if (vehicle.url.isBlank() || car.isBlank()) { order = RouteOrder(); return@LaunchedEffect }
        val today = Logic.call("bus/routeOrderCache", "jstDateKey").text
        val cached = Logic.call("bus/routeOrderCache", "readRouteOrder", car, today)
        fun fromCache(c: JsonElement, extra: RouteOrder.() -> RouteOrder) =
            RouteOrder(c.list("stops"), c.at("car"), c.str("date").orEmpty(), c.long("cachedAt") ?: 0).extra()
        if (cached.obj != null && cached.str("stale") != "true") order = fromCache(cached) { this }
        val ttl = cached.long("ttlMs") ?: if (Net.plan.value.low) 300_000L else 180_000L
        if (!force && cached.obj != null && cached.str("stale") != "true" && System.currentTimeMillis() - (cached.long("cachedAt") ?: 0) < ttl) return@LaunchedEffect
        order = order.copy(loading = true, error = "", errorKind = "")
        try {
            val res = Api.rpc("server-fns#getVehicleRouteOrder", jsonOf("url" to vehicle.url, "carCode" to car, "date" to today, "force" to force))
            val list = res.list("stops")
            if (list.isEmpty()) {
                val kind = res.str("errorKind") ?: "api"
                val saved = Logic.call("bus/routeOrderCache", "readRouteOrder", car, today)
                val fallback = if (kind == "empty" && saved.str("date") != today) null else saved.takeIf { it.obj != null }
                order = fallback?.let { fromCache(it) { copy(error = res.str("error") ?: "停留所一覧を取得できませんでした。前回取得した一覧を表示しています。", errorKind = kind, stale = true) } }
                    ?: RouteOrder(error = res.str("error") ?: "この車両の停留所一覧を取得できませんでした。", errorKind = kind)
                return@LaunchedEffect
            }
            val payload = jsonOf(
                "car" to res.at("car"), "stops" to list, "date" to (res.str("date") ?: today),
                "routeCode" to (res.str("car.routeCode") ?: res.str("routeCode") ?: ""),
                "fetchedAt" to (res.long("fetchedAt") ?: System.currentTimeMillis()), "cacheTtlMs" to (res.long("cacheTtlMs") ?: 0),
            )
            if (res.str("cached") != "true") Logic.call("bus/routeOrderCache", "writeRouteOrder", car, payload)
            order = RouteOrder(list, res.at("car"), res.str("date") ?: today, res.long("cachedAt") ?: System.currentTimeMillis(), stale = res.str("stale") == "true")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            order = order.copy(loading = false, error = e.userMessage(), stale = order.stops.isNotEmpty())
        } finally {
            order = order.copy(loading = false); force = false
        }
    }

    val routeSeq = order.car.int("routeSeq")
    val rows: List<JsonElement> = if (order.stops.isNotEmpty()) order.stops.map { s ->
        val seq = s.int("seq") ?: 0
        val state = when { routeSeq == null || routeSeq == 0 -> "future"; seq == routeSeq -> "current"; seq < routeSeq -> "passed"; else -> "future" }
        jsonOf("seq" to seq, "name" to s.str("name"), "platform" to s.str("platform"), "boarding" to s.str("boarding"), "code" to (s.str("code") ?: s.str("poleCode") ?: ""), "state" to state)
    } else stops.mapIndexed { i, s -> jsonOf("seq" to i + 1, "name" to s.str("name"), "platform" to "", "boarding" to "", "code" to "", "state" to (s.str("state") ?: "future")) }

    // 2) 座標の補完（端末キャッシュ → 共通キャッシュ → OpenStreetMap）
    val lookup = rows.map { Triple(it.int("seq") ?: 0, it.str("name").orEmpty(), it.str("code").orEmpty()) }
    var coords by remember { mutableStateOf<Map<Int, Gps>>(emptyMap()) }
    var coordPending by remember { mutableStateOf(true) }
    var coordError by remember { mutableStateOf("") }
    LaunchedEffect(lookup.toString(), reloadKey) {
        if (lookup.isEmpty()) { coordPending = false; return@LaunchedEffect }
        coordPending = true; coordError = ""
        val listJson = JsonArray(lookup.map { (seq, name, code) -> app.narabuspulse.core.toJson(listOf(seq, name, code)) })
        val shared = runCatching { Api.rpc("server-fns#listStopCoords") }.getOrDefault(JsonArray(emptyList()))
        val matched = Logic.call("glue/stops", "matchStopCoords", listJson, shared)
        val found = HashMap<Int, Gps>()
        matched.obj?.forEach { (seq, c) -> val lat = c.dbl("lat"); val lon = c.dbl("lon"); if (lat != null && lon != null) seq.toIntOrNull()?.let { found[it] = Gps(lat, lon, null) } }
        coords = found.toMap()
        val remaining = lookup.filter { it.first !in found }
        if (remaining.isNotEmpty()) {
            if (Net.plan.value.low) coordError = "低速通信のため、端末と共通キャッシュの座標のみ表示しています。"
            else try {
                val names = remaining.map { it.second }.filter { it.isNotBlank() }.distinct()
                val res = Api.rpc("server-fns#resolveStopCoords", jsonOf("names" to names, "known" to emptyMap<String, Any>()))
                val got = res.objAt("found")
                lookup.forEach { (seq, name) -> got?.get(name)?.let { c -> val lat = c.dbl("lat"); val lon = c.dbl("lon"); if (lat != null && lon != null) found[seq] = Gps(lat, lon, null) } }
                coords = found.toMap()
                val items = got?.map { (n, c) -> jsonOf("name" to n, "lat" to c.dbl("lat"), "lon" to c.dbl("lon")) }.orEmpty()
                if (items.isNotEmpty()) {
                    Logic.call("bus/osm", "saveResolved", names, got)
                    runCatching { Api.rpc("server-fns#saveStopCoords", jsonOf("items" to items)) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { coordError = e.userMessage() }
        }
        coordPending = false
    }

    // 3) 予定時刻と遅延
    val op = parseOperation(vehicle.operation)
    val route = order.car.str("indicatorNo")?.ifBlank { null } ?: op.route.ifBlank { "–" }
    val destination = order.car.str("destination")?.ifBlank { null } ?: op.destination
    var times by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var timesNote by remember { mutableStateOf("") }
    LaunchedEffect(route, destination, lookup.map { it.third }.toString(), reloadKey) {
        if (lookup.isEmpty() || route == "–") return@LaunchedEffect
        runCatching {
            val res = Api.rpc("server-fns#getStopTimetable", jsonOf(
                "vehicle" to (order.car.str("plateNo") ?: vehicle.name), "route" to route, "destination" to destination,
                "stops" to lookup.take(30).map { jsonOf("name" to it.second, "code" to it.third) },
            ))
            if (res.str("ok") == "true") {
                val m = HashMap<String, JsonElement>()
                res.list("stops").forEach { row -> m[Logic.call("bus/stopKey", "stopKeyOf", row.str("name")).text] = row }
                times = m; timesNote = res.str("note").orEmpty()
            }
        }
    }
    val keys = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(rows.map { it.str("name") }.toString()) {
        keys.value = rows.mapNotNull { r -> r.str("name")?.let { it to Logic.call("bus/stopKey", "stopKeyOf", it).text } }.toMap()
    }

    val currentStop = rows.firstOrNull { it.str("state") == "current" }
    val boarding = currentStop?.str("boarding")?.ifBlank { null } ?: rows.firstOrNull()?.str("boarding").orEmpty()
    fun refresh() { force = true; reloadKey++; onRefresh?.invoke() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.background(Brand.Orange, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(route, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(destination.ifBlank { "行先情報なし" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${vehicle.name}・停留所 ${rows.size} か所", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "閉じる") }
                        }
                        if (boarding.isNotBlank()) Row(Modifier.padding(top = 6.dp)) { Tag("乗車口"); Spacer(Modifier.width(6.dp)); Text(boarding, fontSize = 12.sp) }
                    }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    if (order.loading && rows.isEmpty()) item { Text("停留所一覧を取得しています…", fontSize = 12.sp) }
                    if (order.stale && rows.isNotEmpty()) item {
                        Text("最新の一覧を取得できませんでした。前回取得したデータ（${order.date.ifBlank { "日付不明" }} ${BusFormat.time(order.cachedAt)} 時点）を表示しています。", fontSize = 11.sp, color = Brand.Warning)
                    }
                    if (rows.isNotEmpty()) item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            Text("座標 ${coords.size} / ${rows.size} か所", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (times.isNotEmpty()) Text("予定時刻 1日分", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (coordPending) Text("座標を補完しています…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (coords.size < rows.size) Text("未登録の停留所は一覧に残しています", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(rows) { i, stop ->
                        val seq = stop.int("seq") ?: 0
                        val state = stop.str("state")
                        val status = if (coords[seq] != null) "ok" else if (coordPending) "pending" else "missing"
                        val terminal = i == rows.lastIndex && rows.size > 1 && state != "current"
                        val bubble = when { state == "current" -> Brand.Orange; state == "passed" -> Color(0xFFB8BEC4); terminal -> Color(0xFFF5E7B8); else -> Color(0xFFFFF4D6) }
                        val time = keys.value[stop.str("name")]?.let { times[it] }
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) { if (state == "current") Tag(route, Brand.Orange) }
                            Column(
                                Modifier.weight(1f).background(bubble.copy(alpha = if (state == "current") 0.18f else 0.45f), RoundedCornerShape(8.dp))
                                    .border(1.dp, bubble, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stop.str("name").orEmpty(), fontSize = 13.sp, fontWeight = if (state == "current") FontWeight.Bold else FontWeight.Medium,
                                        color = if (state == "passed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                    stop.str("platform")?.takeIf { it.isNotBlank() }?.let { Tag("乗り場 $it", Color(0xFF2F6FB0)) }
                                    time?.str("planned")?.let { planned ->
                                        Spacer(Modifier.width(6.dp))
                                        Text(planned, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        val d = time.int("delay_min") ?: 0
                                        if (d > 0) Text(" +${d}分", fontSize = 11.sp, color = Brand.Destructive, fontWeight = FontWeight.Bold)
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("#$seq", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    stop.str("code")?.takeIf { it.isNotBlank() }?.let { Text("コード $it", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text(COORD_LABEL[status].orEmpty(), fontSize = 10.sp, color = COORD_COLOR[status] ?: Brand.MutedFg)
                                    coords[seq]?.let { Text("%.5f, %.5f".format(it.lat, it.lon), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                    if (!order.loading && rows.isEmpty()) item {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            Text(order.error.ifBlank { "この車両の停留所一覧を取得できませんでした。" }, fontSize = 12.sp, color = Brand.Warning)
                            SmallButton("再取得", { refresh() }, enabled = !refreshing, icon = Icons.Outlined.Refresh)
                        }
                    }
                    if (rows.isNotEmpty() && order.error.isNotBlank()) item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Text(order.error + if (order.errorKind == "empty") "（本日の運行前・運行終了の可能性があります）" else "", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            SmallButton("再取得", { refresh() }, enabled = !refreshing, icon = Icons.Outlined.Refresh)
                        }
                    }
                    if (coordError.isNotBlank()) item { Text(coordError, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (rows.isNotEmpty() && times.isNotEmpty()) item { Text("${timesNote}遅延は本日その停留所を通過した記録がある場合だけ表示します。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        SmallButton("最新表示", { refresh() }, enabled = !order.loading && !refreshing, icon = Icons.Outlined.Refresh)
                        Text("(c)Nara Kotsu Bus Lines Co.,Ltd.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

