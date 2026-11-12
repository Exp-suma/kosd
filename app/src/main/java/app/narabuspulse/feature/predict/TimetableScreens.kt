package app.narabuspulse.feature.predict

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Config
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.MapCircle
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.Markdown
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.LocalTime
import java.time.ZoneId

private val SPANS = listOf("30" to "30分以内", "60" to "1時間以内", "180" to "3時間", "today" to "本日全体", "custom" to "時間帯を指定")
private val SPAN_MIN = mapOf("30" to 30, "60" to 60, "180" to 180)
private val TT_VIEWS = listOf("all" to "すべての方向", "direction" to "方向別", "route" to "系統別", "vehicle" to "車番別")
private fun clock(min: Int) = "%02d:%02d".format(min / 60, min % 60)
private fun parseClock(t: String): Int? = Regex("^(\\d{1,2}):(\\d{2})$").find(t)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
private data class TtPoint(val lat: Double, val lon: Double, val name: String)
private data class Filters(val span: String = "60", val radiusM: Int = 500, val from: String = "", val to: String = "")

private fun windowOf(f: Filters): Pair<Int?, Int?> {
    val now = LocalTime.now(ZoneId.of("Asia/Tokyo")); val nowMin = now.hour * 60 + now.minute
    return when (f.span) {
        "today" -> 0 to 1439
        "custom" -> parseClock(f.from) to parseClock(f.to)
        else -> nowMin to minOf(1439, nowMin + (SPAN_MIN[f.span] ?: 60))
    }
}

private fun shareTimetable(context: android.content.Context, p: TtPoint, span: String) {
    val url = "${Config.BASE_URL}/timetable?lat=${p.lat}&lon=${p.lon}&name=${android.net.Uri.encode(p.name)}&span=$span"
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, "時刻表を共有")) }
}

@Composable
private fun TimetableNav(current: String) {
    val nav = LocalNav.current
    app.narabuspulse.ui.kit.ChipRow(listOf("/timetable" to "交差点時刻表", "/timetable/favorites" to "保存した交差点"), current, { if (it != current) nav.go(it) })
}

/** 交差点別バス時刻表（Web: timetable.index.jsx） */
@Composable
fun IntersectionTimetableScreen(args: Map<String, String>) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var point by remember { mutableStateOf(args["lat"]?.toDoubleOrNull()?.let { la -> args["lon"]?.toDoubleOrNull()?.let { lo -> TtPoint(la, lo, args["name"]?.take(120) ?: "指定地点") } }) }
    var filters by remember { mutableStateOf(Filters(span = args["span"] ?: "60")) }
    var view by remember { mutableStateOf("all") }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(listOf<JsonElement>()) }
    var saving by remember { mutableStateOf(false) }
    suspend fun fetch(p: TtPoint, f: Filters) {
        loading = true; error = ""
        try {
            val (from, to) = windowOf(f)
            result = Api.rpc("timetable/fns#getIntersectionTimetable", jsonOf("point" to jsonOf("lat" to p.lat, "lon" to p.lon, "name" to p.name), "radiusM" to f.radiusM, "fromMin" to from, "toMin" to to, "filters" to jsonOf("span" to f.span, "from" to f.from, "to" to f.to)))
        } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "時刻表を取得できませんでした" } } finally { loading = false }
    }
    suspend fun loadFavs() { favorites = runCatching { Api.rpc("timetable/fns#listIntersectionFavorites").items() }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { loadFavs() }
    LaunchedEffect(point, filters) {
        val p = point ?: return@LaunchedEffect
        fetch(p, filters)
        // 現在位置ベースの時刻表だけ 45 秒ごとに自動更新（公式バスロケの更新間隔に合わせる）
        while (SPAN_MIN.containsKey(filters.span)) { delay(45_000); fetch(p, filters) }
    }
    val vehicles = result.items("entries").filter { it.dbl("vehicle_lat") != null && it.dbl("vehicle_lon") != null }
    ScreenList {
        item {
            MiniHeader("交差点別バス時刻表", "地図で指定した地点を通過する奈良交通のバスを、公式バスロケーションの現在位置と公式の停留所間キロ程から算出します。予測であり公式の到着時刻ではありません。") {
                SmallButton("URL共有", { point?.let { shareTimetable(context, it, filters.span) } }, enabled = point != null, icon = Icons.Outlined.Share)
            }
        }
        item { PredictNav("/timetable") }
        item { TimetableNav("/timetable") }
        item {
            Panel(title = "地点の指定", subtitle = "交差点名・住所・現在地・地図タップで指定できます。") {
                PlacePicker(loading) { point = it }
                OsmMap(Modifier.fillMaxWidth().height(280.dp),
                    pins = listOfNotNull(point?.let { MapPin("p", it.lat, it.lon, it.name, color = Color(0xFFE8B45B), size = 16.dp) }) + vehicles.mapIndexed { i, v -> MapPin("v$i", v.dbl("vehicle_lat")!!, v.dbl("vehicle_lon")!!, v.str("plate_no") ?: v.str("vehicle").orEmpty(), "${v.str("route")?.let { "系統 $it " } ?: ""}${v.str("destination").orEmpty()}", color = Color(0xFF35C98B), size = 11.dp) },
                    circles = listOfNotNull(point?.let { MapCircle(it.lat, it.lon, filters.radiusM.toDouble(), Color(0xFF6C8CFF)) }),
                    center = point?.let { it.lat to it.lon } ?: (34.685 to 135.805), zoom = 15.0, fitKey = point,
                    onMapClick = { la, lo -> point = TtPoint(la, lo, "地図で指定した地点") })
                point?.let { Muted("${it.name}（${"%.5f".format(it.lat)}, ${"%.5f".format(it.lon)}）／半径 ${filters.radiusM}m", 11) }
            }
        }
        item {
            Panel(title = "時刻表", subtitle = "通過予定時刻の早い順。実測に基づく予測と過去履歴からの推定を色分けしています。", action = { result?.let { ConfidenceChip(it.str("confidence"), "全体") } }) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { SPANS.forEach { (k, l) -> FilterChip(filters.span == k, { filters = filters.copy(span = k) }, label = { Text(l, fontSize = 11.sp) }) } }
                if (filters.span == "custom") Row(verticalAlignment = Alignment.CenterVertically) { TimeField(filters.from, { filters = filters.copy(from = it) }); Text(" 〜 "); TimeField(filters.to, { filters = filters.copy(to = it) }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectField(listOf(300, 500, 800, 1200).map { it to "対象範囲 ${it}m" }, filters.radiusM, { filters = filters.copy(radiusM = it) })
                    SmallButton(if (loading) "更新中…" else "予測を更新", { point?.let { p -> scope.launch { fetch(p, filters) } } }, enabled = !loading && point != null)
                }
                result?.let { r -> Muted("対象 ${clock(r.int("window.from") ?: 0)}〜${clock(r.int("window.to") ?: 0)}／データ取得 ${BusFormat.dateTime(r.long("dataFetchedAt"))}", 10) }
                if (result?.bool("stale") == true) Text("公式バスロケから取得できなかったため、前回取得した位置情報を表示しています（最新ではありません）。", fontSize = 11.sp, color = Brand.Warning)
                if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
                if (point == null) Muted("地点を指定すると時刻表を表示します。", 12)
                if (loading && result == null) Muted("時刻表を生成しています…", 12)
                result?.let { r ->
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Tag(if (r.bool("apiOk") == true) "公式バスロケ接続OK" else "公式バスロケ未接続", if (r.bool("apiOk") == true) Brand.Success else Brand.Destructive)
                        Muted("車両 ${r.int("stats.liveVehicles") ?: 0}台／照合 ${r.int("stats.checked") ?: 0}台／周辺停留所 ${r.int("stats.stopCount") ?: 0}件${r.int("stats.staleExcluded")?.takeIf { it > 0 }?.let { "／古い位置 ${it}台を除外" } ?: ""}", 10)
                    }
                    TimetableBoard(r.items("entries"), view) { view = it }
                    r.list("notes").forEach { Muted("・${it.text}", 10) }
                }
            }
        }
        val observed = result.items("observed")
        if (observed.isNotEmpty()) item {
            Panel(title = "実際の通過記録", subtitle = "位置情報が指定地点の至近にあり、通過したと確認できた記録です（予測とは別に保存）。") {
                observed.forEach { o -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) { Text(o.str("clock").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold); Muted("系統 ${o.str("route") ?: "不明"}　${o.str("destination") ?: "行先不明"}　車両 ${o.str("vehicle")}", 11); Tag("実績", INFO) } }
            }
        }
        item { Panel(title = "AIに質問", subtitle = "時刻・車番は取得した実データだけを使います。") { TimetableChat(point) { r -> if (r.items("entries").isNotEmpty()) result = result?.let { prev -> kotlinx.serialization.json.JsonObject((prev as kotlinx.serialization.json.JsonObject) + ("entries" to (r as kotlinx.serialization.json.JsonObject)["entries"]!!)) } } } }
        item {
            Panel(title = "お気に入り", subtitle = "地点と表示条件を保存して、ワンタップで再表示できます。") {
                var name by remember { mutableStateOf("") }
                point?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(name, { name = it }, label = { Text("この地点を保存", fontSize = 11.sp) }, placeholder = { Text(p.name, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                        SmallButton("保存", {
                            saving = true
                            scope.launch {
                                try { val (f, t) = windowOf(filters); Api.rpc("timetable/fns#saveIntersectionFavorite", jsonOf("name" to name.trim().ifBlank { p.name }, "lat" to p.lat, "lon" to p.lon, "radiusM" to filters.radiusM, "direction" to "all", "windowFrom" to f, "windowTo" to t)); loadFavs(); name = ""; snackbar.showSnackbar("交差点を保存しました") }
                                catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar("保存できませんでした") } finally { saving = false }
                            }
                        }, enabled = !saving, icon = Icons.Outlined.StarOutline, primary = true, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                FavoriteList(favorites, onOpen = { item -> filters = filters.copy(radiusM = item.int("radius_m") ?: 500); point = TtPoint(item.dbl("lat") ?: 0.0, item.dbl("lon") ?: 0.0, item.str("name").orEmpty()) },
                    onDelete = { item -> scope.launch { runCatching { Api.rpc("timetable/fns#deleteIntersectionFavorite", jsonOf("id" to item.str("id"))) }.onSuccess { favorites = favorites - item }.onFailure { snackbar.showSnackbar("削除できませんでした") } } })
            }
        }
        item {
            Panel(title = "データについて") {
                listOf("車両位置・車番・系統・行先・停留所は奈良交通バスロケーションシステムの公開APIから取得しています。", "残り距離は公式の停留所間キロ程（route/order）を使い、直線距離では計算していません。", "公式時刻表そのものは地点指定では取得できないため、公式時刻表に基づく便は表示していません。", "取得は45秒間隔でキャッシュし、複数利用者の同時要求は1回にまとめています。").forEach { Muted("・$it", 10) }
            }
        }
    }
}

@Composable
private fun PlacePicker(loading: Boolean, onPick: (TtPoint) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val locate = rememberLocationRequest { loc -> busy = false; if (loc == null) error = "現在地を取得できませんでした（位置情報の許可を確認してください）" else onPick(TtPoint(loc.lat, loc.lon, "現在地")) }
    fun run() {
        val t = query.trim(); if (t.isEmpty()) return
        busy = true; error = ""
        scope.launch {
            try { val r = Api.rpc("timetable/fns#searchTimetablePlace", jsonOf("query" to t)); if (r.bool("ok") != true) error = r.str("error") ?: "地点を特定できませんでした" else r.objAt("point")?.let { p -> onPick(TtPoint(p.dbl("lat") ?: 0.0, p.dbl("lon") ?: 0.0, p.str("name") ?: t)) } }
            catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "地点を特定できませんでした" } } finally { busy = false }
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("交差点名・住所（例: 三条大路、奈良市登大路町）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
            IconButton(onClick = { run() }, enabled = !busy && !loading) { Icon(Icons.Outlined.Search, "検索") }
            IconButton(onClick = { busy = true; error = ""; locate() }, enabled = !busy && !loading) { Icon(Icons.Outlined.MyLocation, "現在地") }
        }
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        Muted("地図をタップしても地点を指定できます。", 10)
    }
}

private val TT_METHOD = mapOf("live_route" to ("現在位置から予測" to Brand.Success), "history" to ("過去履歴から推定" to INFO), "observed" to ("実績" to INFO))
private val SOURCE = mapOf("bus_location" to ("公式バスロケ" to Brand.Success), "position_log" to ("GPS履歴" to INFO), "passage_history" to ("通過記録" to INFO))
private fun vehicleLabel(e: JsonElement) = e.str("plate_no")?.ifBlank { null } ?: e.str("vehicle")?.let { "コード$it" } ?: "不明"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimetableBoard(entries: List<JsonElement>, view: String, onView: (String) -> Unit) {
    if (entries.isEmpty()) { EmptyState("この条件に合う便がありません", description = "地点を道路沿いに指定するか、対象範囲・時間帯を広げてください。"); return }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { TT_VIEWS.forEach { (k, l) -> FilterChip(view == k, { onView(k) }, label = { Text(l, fontSize = 11.sp) }) } }
    val groups = if (view == "all") listOf("すべての便" to entries) else entries.groupBy { e ->
        when (view) { "direction" -> e.str("direction")?.ifBlank { null } ?: "方向不明"; "route" -> e.str("route")?.ifBlank { null }?.let { "系統 $it" } ?: "系統不明"; else -> vehicleLabel(e) }
    }.toList()
    groups.forEach { (label, items) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Muted("${items.size}便", 11) }
        items.forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.str("clock").orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Tag(e.str("route")?.ifBlank { null }?.let { "系統 $it" } ?: "系統不明", INFO)
                    Text(e.str("destination") ?: "不明", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    ConfidenceChip(e.str("confidence"))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val (ml, mc) = TT_METHOD[e.str("method")] ?: ("算出方法不明" to Brand.MutedFg); Tag(ml, mc)
                    SOURCE[e.str("source")]?.let { (sl, sc) -> Tag(sl, sc) }
                    if (e.bool("stale") == true) Tag("位置が古い", Brand.Warning)
                    Muted("車番 ${vehicleLabel(e)}　${e.str("direction").orEmpty()}　取得 ${if (e.str("method") == "history") "—" else BusFormat.time(e.long("observed_at"))}　残り ${e.dbl("remaining_km")?.let { "${fmtNum(it)} km" } ?: "—"}", 10)
                }
                e.str("basis")?.takeIf { it.isNotBlank() }?.let { Muted(it, 9) }
            }
        }
    }
}

private val TT_EXAMPLES = listOf("この交差点を通るバスの時刻表を見せて", "今日の16時以降にここを通るバスは？", "車番が分かるものだけ表示して", "明日の朝、ここを通るバスは何時頃？")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimetableChat(point: TtPoint?, onResult: (JsonElement) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf("") }
    fun run(t: String) {
        val q = t.trim(); if (q.isEmpty()) return
        busy = true; error = ""; answer = ""; notes = emptyList()
        scope.launch {
            try {
                val r = Api.rpc("timetable/fns#askTimetable", jsonOf("query" to q, "point" to point?.let { jsonOf("lat" to it.lat, "lon" to it.lon, "name" to it.name) }))
                if (r.bool("ok") != true) error = r.str("error") ?: "時刻表を検索できませんでした"
                else { answer = r.str("answer").orEmpty(); notes = (r.list("cautions") + r.list("notes")).map { it.text }.filter { it.isNotBlank() }; onResult(r) }
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "時刻表を検索できませんでした" } } finally { busy = false }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, { query = it }, placeholder = { Text("例: 今日の16時以降にここを通るバスは？", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { run(query) }))
        SmallButton(if (busy) "検索中…" else "質問", { run(query) }, enabled = !busy, primary = true, modifier = Modifier.padding(start = 6.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TT_EXAMPLES.forEach { ex -> AssistChip({ query = ex; run(ex) }, label = { Text(ex, fontSize = 11.sp) }) } }
    if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
    if (answer.isNotBlank()) Markdown(answer)
    notes.forEach { Muted("・$it", 10) }
}

@Composable
private fun FavoriteList(items: List<JsonElement>, onOpen: (JsonElement) -> Unit, onDelete: (JsonElement) -> Unit) {
    if (items.isEmpty()) { Muted("保存した交差点はまだありません。", 12); return }
    items.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onOpen(item) }.padding(vertical = 6.dp)) {
                Text("${item.str("name")} →", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                val wf = item.int("window_from"); val wt = item.int("window_to")
                Muted("${item.str("direction")?.takeIf { it != "all" }?.let { "$it／" } ?: "全方向／"}${item.str("routes")?.takeIf { it.isNotBlank() }?.let { "系統 $it" } ?: "全系統"}／半径${item.int("radius_m") ?: 500}m${if (wf != null && wt != null) "／${clock(wf)}〜${clock(wt)}" else ""}", 10)
            }
            IconButton(onClick = { onDelete(item) }) { Icon(Icons.Outlined.Delete, "削除") }
        }
    }
}

/** 保存した交差点（Web: timetable.favorites.jsx） */
@Composable
fun TimetableFavoritesScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JsonElement>?>(null) }
    LaunchedEffect(Unit) { rows = runCatching { Api.rpc("timetable/fns#listIntersectionFavorites").items() }.getOrDefault(emptyList()) }
    fun open(item: JsonElement) = nav.go("/timetable?lat=${item.dbl("lat")}&lon=${item.dbl("lon")}&name=${android.net.Uri.encode(item.str("name").orEmpty())}&span=60")
    ScreenList {
        item { MiniHeader("保存した交差点", "よく見る交差点を保存しておくと、ワンタップで時刻表を開けます。") }
        item { TimetableNav("/timetable/favorites") }
        item {
            Panel(title = "一覧") {
                val list = rows
                when {
                    list == null -> Muted("読み込み中…", 12)
                    list.isEmpty() -> EmptyState("保存した交差点はありません", description = "交差点時刻表の画面から保存できます。")
                    else -> list.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { open(item) }.padding(vertical = 6.dp)) {
                                Text(item.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                val wf = item.int("window_from"); val wt = item.int("window_to")
                                Muted("${item.str("direction")?.takeIf { it != "all" }?.let { "$it／" } ?: "全方向／"}${item.str("routes")?.takeIf { it.isNotBlank() }?.let { "系統 $it／" } ?: "全系統／"}半径${item.int("radius_m") ?: 500}m${if (wf != null && wt != null) "／${clock(wf)}〜${clock(wt)}" else ""}", 10)
                            }
                            SmallButton("時刻表を開く", { open(item) })
                            IconButton(onClick = { shareTimetable(context, TtPoint(item.dbl("lat") ?: 0.0, item.dbl("lon") ?: 0.0, item.str("name").orEmpty()), "60") }) { Icon(Icons.Outlined.Share, "共有") }
                            IconButton(onClick = { scope.launch { runCatching { Api.rpc("timetable/fns#deleteIntersectionFavorite", jsonOf("id" to item.str("id"))) }.onSuccess { rows = rows.orEmpty() - item; snackbar.showSnackbar("削除しました") }.onFailure { snackbar.showSnackbar("削除できませんでした") } } }) { Icon(Icons.Outlined.Delete, "削除") }
                        }
                    }
                }
            }
        }
    }
}

