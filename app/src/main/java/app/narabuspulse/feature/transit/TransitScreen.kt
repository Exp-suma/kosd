package app.narabuspulse.feature.transit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.feature.predict.PURPLE
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val JST = ZoneId.of("Asia/Tokyo")
private val TYPE_LABEL = mapOf("departure" to "出発", "arrival" to "到着", "first" to "始発", "last" to "終電")
private val TYPE_CHOICES = listOf("departure" to "出発時刻", "arrival" to "到着時刻", "first" to "始発", "last" to "終電")
private val SORT_CHOICES = listOf("arrival" to "到着が早い順", "departure" to "出発が早い順", "duration" to "所要時間が短い順", "transfers" to "乗換が少ない順", "walk" to "徒歩が短い順", "fare" to "運賃が安い順")
private val WALK_SPEEDS = listOf(80 to "ゆっくり（80m/分）", 60 to "ふつう（60m/分）", 50 to "急ぎ（50m/分）")
private val KIND_LABEL = mapOf("station" to "駅", "stop" to "停留所", "place" to "施設", "address" to "住所")

/** 出発地・目的地（Web の PlaceField が返す値と同じ項目）。 */
data class Place(val endpoint: String, val label: String, val lat: Double? = null, val lon: Double? = null, val feedName: String = "", val kind: String = "place", val code: String = "") {
    fun payload() = jsonOf("kind" to (if (kind == "bus") "bus" else "place"), "code" to code, "endpoint" to endpoint, "label" to label, "lat" to lat, "lon" to lon)
}

private fun jstClock(offsetMin: Long = 0): java.time.LocalDateTime = Instant.now().plusSeconds(offsetMin * 60).atZone(JST).toLocalDateTime()

private fun sortOptions(list: List<JsonElement>, key: String): List<JsonElement> {
    val big = 1e9
    val dur = compareBy<JsonElement> { it.dbl("durationSecs") ?: big }
    return when (key) {
        "departure" -> list.sortedBy { it.dbl("departureSecs") ?: big }
        "duration" -> list.sortedWith(dur)
        "transfers" -> list.sortedWith(compareBy<JsonElement> { it.dbl("transferCount") ?: 9.0 }.then(dur))
        "walk" -> list.sortedWith(compareBy<JsonElement> { it.dbl("walkSecs") ?: big }.then(dur))
        "fare" -> list.sortedWith(compareBy<JsonElement> { it.dbl("fare.ticket") ?: big }.then(dur))
        else -> list.sortedWith(compareBy<JsonElement> { it.dbl("effectiveArrivalSecs") ?: it.dbl("arrivalSecs") ?: big }.then(dur))
    }
}

/** 総合経路検索（Web: transit.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransitScreen(args: Map<String, String>, naraTabs: Boolean = false) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val planId = args["planId"]
    val argDate = args["date"]?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }
    var from by remember { mutableStateOf(args["from"]?.let { Place("bus:$it", args["fromName"] ?: "停留所 $it", kind = "bus", code = it, feedName = "奈良交通") }) }
    var to by remember { mutableStateOf(args["to"]?.let { Place("bus:$it", args["toName"] ?: "停留所 $it", kind = "bus", code = it, feedName = "奈良交通") }) }
    var date by remember { mutableStateOf(argDate ?: jstTodayKey()) }
    var time by remember { mutableStateOf(jstClock(30).toLocalTime().toString().take(5)) }
    var type by remember { mutableStateOf("departure") }
    var walkSpeed by remember { mutableStateOf(80) }
    var maxTransfers by remember { mutableStateOf(3) }
    var sortKey by remember { mutableStateOf("arrival") }
    var transferBuffer by remember { mutableStateOf(4) }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var decorated by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var lastQuery by remember { mutableStateOf<Pair<Place, Place>?>(null) }
    var favReload by remember { mutableStateOf(0) }
    val timeBased = type == "departure" || type == "arrival"

    fun run(f: Place? = from, t: Place? = to) {
        if (f == null || t == null) return
        busy = true; error = ""; progress = ""; lastQuery = f to t
        scope.launch {
            try {
                val now = jstClock(1).toLocalTime().toString().take(5)
                val data = JourneyPlanner.search(jsonOf("from" to f.payload(), "to" to t.payload(), "date" to date, "time" to (if (timeBased) time else null), "type" to type,
                    "walkSpeed" to walkSpeed, "maxTransfers" to maxTransfers, "transferBuffer" to transferBuffer, "nowClock" to now)) { progress = it }
                result = data
                decorated = data.list("options")
                if (decorated.isEmpty()) error = data.str("error")?.ifBlank { null } ?: "この条件で取得できる経路がありませんでした。"
                favReload++
            } catch (e: Throwable) { if (e is CancellationException) throw e; result = null; decorated = emptyList(); error = e.userMessage().ifBlank { "経路を取得できませんでした" } } finally { busy = false }
        }
    }
    val options = remember(decorated, sortKey) { sortOptions(decorated, sortKey) }

    ScreenList {
        if (naraTabs) item { app.narabuspulse.feature.nara.NaraNav("/nara") }
        else item {
            TextButton(onClick = { nav.go(if (planId != null) "/expedition/$planId" else "/expedition") }) { Text(if (planId != null) "← 遠征計画へ戻る" else "← 遠征プランナーへ", fontSize = 12.sp) }
        }
        item {
            Column {
                Heading("乗換案内（奈良交通バス＋鉄道）", "奈良交通の停留所・駅・施設のどれからでも検索できます。バスの直行便、バス同士の乗り継ぎ、バスと鉄道の乗り継ぎを1つの一覧にまとめます。奈良交通は公式API（奈良バスなびweb）、鉄道・他社は Transit API の実データだけを使い、取得できない区間・時刻・運賃は推測せず「情報なし」と表示します。")
                if (!naraTabs) TextButton(onClick = { nav.go("/nara/data") }) { Text("データ・診断（管理者向け）", fontSize = 11.sp) }
            }
        }
        item { FavoritesBar(from, to, sortKey, favReload) { f, t -> from = f; to = t; run(f, t) } }
        item {
            Panel {
                PlaceField("出発地（奈良交通の停留所・駅・施設）", from, { from = it }, "例 近鉄奈良駅")
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) { IconButton(onClick = { val a = from; from = to; to = a }) { Icon(Icons.Outlined.SwapVert, "出発地と目的地を入れ替え") } }
                PlaceField("目的地（奈良交通の停留所・駅・施設）", to, { to = it }, "例 大阪難波")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Muted("日付", 12); DateField(date, { date = it })
                    SelectField(TYPE_CHOICES, type, { type = it }, label = "種別")
                    if (timeBased) { Muted("時刻", 12); TimeField(time, { time = it }) }
                    TextButton(onClick = { val n = jstClock(1); date = n.toLocalDate().toString(); time = n.toLocalTime().toString().take(5); type = "departure" }) { Text("現在時刻で検索", fontSize = 11.sp) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SelectField(WALK_SPEEDS, walkSpeed, { walkSpeed = it }, label = "徒歩速度")
                    SelectField((0..4).map { it to if (it == 0) "なし" else "${it}回" }, maxTransfers, { maxTransfers = it }, label = "乗換上限")
                    SelectField(listOf(2, 4, 6, 10).map { it to "${it}分" }, transferBuffer, { transferBuffer = it }, label = "乗換の余裕")
                    SelectField(SORT_CHOICES, sortKey, { sortKey = it }, label = "並び順")
                }
                if (from == null || to == null) Text("出発地と目的地を候補から選んでください（奈良交通の停留所も選べます）。", fontSize = 11.sp, color = Brand.Warning)
                Muted("奈良交通の区間は公式API（奈良バスなびweb）の実データ、鉄道・他社交通機関は Transit API の実データを使用します。取得できない区間は表示しません。", 10)
                if (busy && progress.isNotBlank()) Muted("$progress…", 11)
                SmallButton(if (busy) "検索中…" else "経路を検索", { run() }, enabled = !busy && from != null && to != null, icon = Icons.Outlined.Search, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (planId != null && argDate != null) item { Muted("遠征計画の $argDate で検索します。結果は「遠征の行程に追加」から日別行程へ反映できます。", 11) }
        if (error.isNotBlank()) item {
            Panel {
                Text("⚠ $error", fontSize = 12.sp, color = Brand.Destructive)
                lastQuery?.let { (f, t) -> SmallButton(if (busy) "再試行中…" else "再試行", { run(f, t) }, enabled = !busy, icon = Icons.Outlined.Refresh) }
                Muted("経路の代替を推測して表示することはありません。条件を変えて検索し直してください。", 10)
            }
        }
        result?.let { r ->
            item {
                Column {
                    Text("${r.str("from.label").orEmpty()} → ${r.str("to.label").orEmpty()}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Muted("${r.str("date").orEmpty()}（${TYPE_LABEL[r.str("type")] ?: r.str("type").orEmpty()}${r.str("time")?.let { " $it" } ?: ""}）／候補 ${options.size} 件", 11)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Tag("🚌 バスのみ ${r.int("counts.bus") ?: 0}", Brand.Success); Tag("🚆 鉄道など ${r.int("counts.transit") ?: 0}", INFO); Tag("🚌🚆 バス＋鉄道 ${r.int("counts.hybrid") ?: 0}", PURPLE); (r.int("counts.busTransfer") ?: 0).takeIf { it > 0 }?.let { Tag("🚌⇄🚌 バス乗継 $it", Brand.Orange) }
                    }
                    Muted("奈良交通API ${if (r.bool("sources.narakotsu.ok") == true) "OK" else "取得不可"}／Transit API ${if (r.bool("sources.transit.ok") == true) "OK" else "取得不可"}　処理 ${r.int("durationMs") ?: 0}ms", 10)
                }
            }
            item { TransitNotices(r.items("notices"), r.objAt("osm"), r.items("coverage.feeds")) }
            items(options.size) { i -> RouteResultCard(options[i], r.str("date") ?: date, planId) }
            if (options.isEmpty()) item { Muted("候補がありません。日時・出発地・目的地を変えてお試しください（推測の経路は表示しません）。", 12) }
        }
        item { CoveragePanel() }
    }
}

/** 駅名・停留所名・施設名の候補検索と緯度経度指定（Web: PlaceField.jsx） */
@Composable
fun PlaceField(label: String, value: Place?, onChange: (Place?) -> Unit, placeholder: String) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(value?.label.orEmpty()) }
    LaunchedEffect(value) { if (value != null) text = value.label }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var coordOpen by remember { mutableStateOf(false) }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var coordBusy by remember { mutableStateOf(false) }
    var coordError by remember { mutableStateOf("") }
    fun query(k: String) {
        job?.cancel()
        if (k.isBlank()) { items = emptyList(); return }
        job = scope.launch {
            delay(300); loading = true; error = ""
            try {
                val r = Api.rpc("transit/hybrid-fns#suggestJourneyPlaces", jsonOf("q" to k.trim(), "limit" to 8))
                if (r.bool("ok") == false) error = r.str("error") ?: "候補を取得できませんでした" else items = r.items("places")
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "候補を取得できませんでした" } } finally { loading = false }
        }
    }
    fun useCoordinate() {
        val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
        if (la == null || lo == null || kotlin.math.abs(la) > 90 || kotlin.math.abs(lo) > 180) { coordError = "緯度（-90〜90）と経度（-180〜180）を入力してください"; return }
        coordBusy = true; coordError = ""
        scope.launch {
            var lbl = "緯度経度 ${"%.4f".format(la)}, ${"%.4f".format(lo)}"
            runCatching { Api.rpc("transit/server-fns#reverseTransitPlace", jsonOf("lat" to la, "lon" to lo)).items("places").firstOrNull()?.str("name") }.getOrNull()?.let { lbl = "$it（${"%.4f".format(la)}, ${"%.4f".format(lo)}）" }
            text = lbl; coordOpen = false; lat = ""; lon = ""; coordBusy = false
            onChange(Place("geo:$la,$lo", lbl, la, lo))
        }
    }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { coordOpen = !coordOpen }) { Icon(Icons.Outlined.MyLocation, null, Modifier.width(14.dp)); Text(" 緯度経度", fontSize = 11.sp) }
        }
        OutlinedTextField(text, { text = it; open = true; if (value != null) onChange(null); query(it) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; onChange(null); items = emptyList() }) { Icon(Icons.Outlined.Close, "消す") } },
            supportingText = if (loading) ({ Text("検索中…", fontSize = 10.sp) }) else null)
        if (open && error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        if (open && items.isNotEmpty()) Column {
            items.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable {
                    text = p.str("name").orEmpty(); open = false; items = emptyList()
                    onChange(Place(p.str("endpoint").orEmpty(), p.str("name").orEmpty(), p.dbl("lat"), p.dbl("lon"), p.str("feedName").orEmpty(), if (p.str("source") == "narakotsu") "bus" else "place", p.str("code").orEmpty()))
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(KIND_LABEL[p.str("kind")] ?: "地点", if (p.str("source") == "narakotsu") Brand.Success else INFO)
                    Column(Modifier.weight(1f)) {
                        Text(p.str("name").orEmpty(), fontSize = 13.sp)
                        Muted(p.str("feedName")?.ifBlank { null } ?: p.str("description")?.ifBlank { null } ?: if (p.str("source") == "osm") "OpenStreetMap" else "", 10)
                    }
                }
            }
        }
        if (open && !loading && error.isBlank() && items.isEmpty() && text.isNotBlank() && value == null) Muted("一致する地点がありません", 10)
        value?.let { Muted(if (it.endpoint.startsWith("geo:")) "座標指定" else it.feedName.ifBlank { "時刻表データあり" }, 10) }
        if (coordOpen) Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(lat, { lat = it }, label = { Text("緯度", fontSize = 11.sp) }, placeholder = { Text("34.6841") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(lon, { lon = it }, label = { Text("経度", fontSize = 11.sp) }, placeholder = { Text("135.8285") }, singleLine = true, modifier = Modifier.weight(1f))
                SmallButton(if (coordBusy) "…" else "指定", { useCoordinate() }, enabled = !coordBusy, primary = true)
            }
            if (coordError.isNotBlank()) Text(coordError, fontSize = 11.sp, color = Brand.Destructive)
        }
    }
}

private fun endpointOf(row: JsonElement, prefix: String): Place {
    val code = row.str("${prefix}_code").orEmpty()
    return Place(row.str("${prefix}_endpoint")?.ifBlank { null } ?: if (code.isNotBlank()) "bus:$code" else "", row.str("${prefix}_label").orEmpty(), row.dbl("${prefix}_lat"), row.dbl("${prefix}_lon"), "",
        row.str("${prefix}_kind")?.ifBlank { null } ?: if (code.isNotBlank()) "bus" else "place", code)
}

/** よく使う経路と検索履歴（Web: FavoritesBar.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoritesBar(from: Place?, to: Place?, sortKey: String, reloadKey: Int, onPick: (Place, Place) -> Unit) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val favs = produceState(emptyList<JsonElement>(), version) { value = runCatching { Api.entityList("TransitFavorite", sort = "-at", limit = 20) }.getOrDefault(emptyList()) }
    val recents = produceState(emptyList<JsonElement>(), version, reloadKey) { value = runCatching { Api.entityList("TransitJourneySearch", sort = "-at", limit = 8) }.getOrDefault(emptyList()) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Panel {
        Text("★ お気に入り", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        val list = favs.value.filter { it.str("id") !in hidden }
        if (list.isEmpty()) Muted("まだありません", 11)
        list.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${row.str("from_label")} → ${row.str("to_label")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).clickable { onPick(endpointOf(row, "from"), endpointOf(row, "to")) }.padding(vertical = 4.dp))
                IconButton(onClick = {
                    onPick(endpointOf(row, "to"), endpointOf(row, "from"))
                    scope.launch { runCatching { Api.entityUpdate("TransitFavorite", row.str("id").orEmpty(), jsonOf("last_used_at" to System.currentTimeMillis())) } }
                }) { Icon(Icons.Outlined.SwapHoriz, "逆方向で検索") }
                IconButton(onClick = {
                    scope.launch {
                        try { Api.entityDelete("TransitFavorite", row.str("id").orEmpty()); hidden = hidden + row.str("id").orEmpty() }
                        catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "削除できませんでした" } }
                    }
                }) { Icon(Icons.Outlined.Delete, "お気に入りから削除") }
            }
        }
        SmallButton("この経路を保存", {
            val f = from; val t = to; if (f == null || t == null) return@SmallButton
            busy = true; message = ""
            scope.launch {
                try {
                    Api.entityCreate("TransitFavorite", jsonOf("name" to "${f.label} → ${t.label}", "from_kind" to f.kind, "from_code" to f.code, "from_endpoint" to f.endpoint, "from_label" to f.label, "from_lat" to f.lat, "from_lon" to f.lon,
                        "to_kind" to t.kind, "to_code" to t.code, "to_endpoint" to t.endpoint, "to_label" to t.label, "to_lat" to t.lat, "to_lon" to t.lon, "sort_key" to sortKey, "at" to System.currentTimeMillis()))
                    message = "この経路をお気に入りに保存しました。"; version++
                } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
            }
        }, enabled = !busy && from != null && to != null, icon = Icons.Outlined.StarOutline)
        val pairs = recents.value.filter { !it.str("from_endpoint").isNullOrBlank() && !it.str("to_endpoint").isNullOrBlank() }
        if (pairs.isNotEmpty()) {
            Text("🕘 最近の検索", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pairs.forEach { row -> Tag("${row.str("from_label")} → ${row.str("to_label")}", Brand.MutedFg, Modifier.clickable { onPick(endpointOf(row, "from"), endpointOf(row, "to")) }) }
            }
        }
        if (message.isNotBlank()) Muted(message, 11)
    }
}

private val COMPOSITION_COLOR = mapOf("bus" to Brand.Success, "transit" to INFO, "hybrid" to PURPLE)

/** 経路候補1件（Web: RouteResultCard.jsx + LegList.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteResultCard(option: JsonElement, date: String, planId: String?) {
    var showMap by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Tag("候補${option.int("rank") ?: ""}", Brand.MutedFg)
            option.str("_comp")?.takeIf { it.isNotBlank() }?.let { Tag(it, COMPOSITION_COLOR[option.str("composition")] ?: INFO) }
            if (option.bool("recommended") == true) Tag("推奨", Brand.Orange)
            option.str("_conf")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
        }
        Text("${option.str("_dep").orEmpty()} → ${option.str("_arr").orEmpty()}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🕒 ${option.str("_dur").orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("⇄ 乗換 ${option.int("transferCount") ?: 0} 回", fontSize = 12.sp)
            option.str("_walk")?.takeIf { it.isNotBlank() }?.let { Text("🚶 徒歩 $it", fontSize = 12.sp) }
            val fare = option.objAt("fare")
            Text("💴 ${option.str("_fare").orEmpty()}${if (fare == null && option.dbl("busFare") != null) "（バス区間 ${java.text.NumberFormat.getInstance(java.util.Locale.JAPAN).format(option.long("busFare"))}円）" else ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (fare != null) MaterialTheme.colorScheme.onSurface else Brand.Warning)
        }
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { option.items("legs").forEach { LegRow(it) } }
        option.str("hybridNote")?.takeIf { it.isNotBlank() }?.let { Muted("乗り換え: $it", 11) }
        (option.int("_implicitWalkMin") ?: 0).takeIf { it >= 2 }?.let { m -> Text("⚠ 駅・停留所までの徒歩 約${m}分は上の時刻に含まれていません${option.str("_effArr")?.takeIf { it.isNotBlank() }?.let { "（実質の到着 $it 頃）" } ?: ""}", fontSize = 10.sp, color = Brand.Warning) }
        val tr = option.items("transfers")
        if (tr.isNotEmpty()) Muted("乗換: " + tr.joinToString("、") { it.str("name").orEmpty() }, 11)
        Muted(option.str("_fareNote").orEmpty(), 10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (showMap) "地図を閉じる" else "経路地図", { showMap = !showMap }, icon = Icons.Outlined.Map)
            SmallButton("遠征の行程に追加", { showAttach = !showAttach }, icon = Icons.Outlined.Add)
            val ops = option.items("operators")
            if (ops.isNotEmpty()) Muted(ops.joinToString("・") { it.str("nameShort") ?: it.str("name").orEmpty() }, 10)
        }
        if (showMap) { val m = option.objAt("map"); if (m != null) HybridMap(m) else Muted("この候補の地図データは取得できませんでした（経路の時刻・区間は表示しています）。", 11) }
        if (showAttach) AttachToPlan(option, date, planId)
    }
}

@Composable
private fun LegRow(leg: JsonElement) {
    val walk = leg.str("kind") == "walk"
    val icon = when { walk -> "🚶"; leg.str("mode") in setOf("bus", "trolleybus") -> "🚌"; leg.str("mode") == "ferry" -> "⛴"; leg.str("mode") == "air" -> "✈"; else -> "🚆" }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(icon, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${leg.str("_dep").orEmpty()} → ${leg.str("_arr").orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Tag(leg.str("_mode").orEmpty(), if (walk) Brand.MutedFg else INFO)
            }
            Text("${leg.str("from.name") ?: "—"}${leg.str("from.platformCode")?.let { " [$it]" } ?: ""} → ${leg.str("to.name") ?: "—"}${leg.str("to.platformCode")?.let { " [$it]" } ?: ""}", fontSize = 12.sp, color = if (walk) Brand.MutedFg else MaterialTheme.colorScheme.onSurface)
            if (!walk) {
                val op = leg.str("operator.nameShort") ?: leg.str("operator.name") ?: leg.str("feedName")
                Muted(listOfNotNull(leg.str("routeName"), leg.str("trainType"), leg.str("headsign")?.let { "$it 行" }).filter { it.isNotBlank() }.joinToString("・").ifBlank { "路線名不明" } + (op?.let { "／$it" } ?: "") + if (leg.bool("headwayBased") == true) "（頭数運行・時刻は目安）" else "", 10)
                if (leg.str("platform") != null && leg.str("from.platformCode") != null) Muted(leg.str("platform").orEmpty(), 10)
            }
            leg.objAt("live")?.let { live ->
                val stale = live.bool("stale") == true
                Text(listOfNotNull("車番 ${live.str("carCode") ?: "—"}${live.str("plateNo")?.let { "（$it）" } ?: ""}", live.str("stopName")?.let { "現在 $it 付近" }, leg.str("_liveAt")?.takeIf { it.isNotBlank() }?.let { "更新 $it" },
                    if (stale) "位置情報が古い" else null, if (live.bool("passedBoardingStop") == true) "乗車停留所は通過済みの可能性" else null).joinToString("／"), fontSize = 10.sp, color = if (stale) Brand.Warning else INFO)
            }
        }
    }
}

private val GREEN = Color(0xFF1F6F43)
private val GRAY = Color(0xFF8B9198)

/** 経路地図（Web: HybridMap.jsx + RouteMap.jsx） */
@Composable
private fun HybridMap(routeMap: JsonElement) {
    val pairs = routeMap.items("busPairs").filter { it.dbl("from.lat") != null && it.dbl("to.lat") != null }
    var state by remember { mutableStateOf(if (pairs.isEmpty()) "none" else "loading") }
    val extra = produceState(emptyList<List<Pair<Double, Double>>>(), routeMap) {
        if (pairs.isEmpty()) return@produceState
        try {
            val res = Api.rpc("server-fns#getRoadPaths", jsonOf("pairs" to JsonArray(pairs)))
            val lines = (res.objAt("segments")?.values ?: emptyList()).mapNotNull { seg -> seg.list("coords").mapNotNull { p -> p.dbl("lat")?.let { la -> p.dbl("lon")?.let { lo -> la to lo } } ?: (p as? JsonArray)?.let { a -> a.getOrNull(0)?.text?.toDoubleOrNull()?.let { la -> a.getOrNull(1)?.text?.toDoubleOrNull()?.let { lo -> la to lo } } } }.takeIf { it.size > 1 } }
            value = lines; state = if (lines.isNotEmpty()) "ok" else "failed"
        } catch (e: Throwable) { if (e is CancellationException) throw e; state = "failed" }
    }
    val points = routeMap.items("points").filter { it.dbl("lat") != null && it.dbl("lon") != null }
    val segs = routeMap.items("segments").filter { it.list("polyline").size > 1 }
    val lines = segs.map { s -> MapLine(s.items("polyline").map { it.dbl("lat")!! to it.dbl("lon")!! }, if (s.str("kind") == "walk") GRAY else GREEN, if (s.str("kind") == "walk") 5f else 7f, dashed = s.str("kind") == "walk") } +
        extra.value.map { MapLine(it, GREEN, 7f) }
    val pins = points.mapIndexed { i, p -> MapPin("p$i", p.dbl("lat")!!, p.dbl("lon")!!, p.str("name").orEmpty(), color = GREEN, size = if (p.str("role") == "origin" || p.str("role") == "destination") 14.dp else 10.dp) }
    Column {
        OsmMap(Modifier.fillMaxWidth().height(280.dp), pins = pins, lines = lines, center = points.firstOrNull()?.let { it.dbl("lat")!! to it.dbl("lon")!! } ?: (34.685 to 135.83), zoom = 12.0, fitKey = lines.size + pins.size)
        Muted(when (state) {
            "none" -> "停留所・駅の実座標のみ表示しています。"
            "ok" -> "奈良交通バス区間の道路形状は OpenStreetMap の道路データから取得したものです（実際の運行経路とは異なる場合があります）。"
            "loading" -> "バス区間の道路形状を取得しています…"
            else -> "バス区間の道路形状を取得できませんでした。停留所・駅の実座標のみ表示しています。"
        }, 10)
    }
}

private fun datesOf(plan: JsonElement?): List<String> {
    val s = plan.str("start_date") ?: return emptyList(); val e = plan.str("end_date") ?: return emptyList()
    return runCatching { generateSequence(LocalDate.parse(s)) { it.plusDays(1) }.takeWhile { !it.isAfter(LocalDate.parse(e)) }.take(62).map { it.toString() }.toList() }.getOrDefault(emptyList())
}

/** 検索した経路を遠征の日別行程へ追加（Web: AttachToPlan.jsx） */
@Composable
private fun AttachToPlan(option: JsonElement, date: String, defaultPlanId: String?) {
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val q = rememberLoad("expedition-plans") { Api.rpc("expedition/server-fns#listExpeditionPlans", jsonOf()) }
    var planId by remember { mutableStateOf(defaultPlanId.orEmpty()) }
    var dayDate by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<JsonObject?>(null) }
    val list = q.data.items()
    val plan = list.firstOrNull { it.str("id") == planId } ?: list.firstOrNull()
    val dates = datesOf(plan)
    val eff = if (dayDate in dates) dayDate else if (date in dates) date else dates.firstOrNull() ?: date
    Column(Modifier.padding(top = 6.dp)) {
        when {
            q.loading && q.data == null -> Muted("遠征計画を読み込んでいます…", 11)
            list.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) { Muted("遠征計画がまだありません。", 11); TextButton(onClick = { nav.go("/expedition") }) { Text("遠征プランナーで作成する", fontSize = 11.sp) } }
            done != null -> Column {
                Text("✓ ${done.str("date")} の行程に ${done.int("added") ?: 0} 件を追加しました", fontSize = 12.sp, color = Brand.Success)
                if ((done.int("skipped") ?: 0) > 0) Muted("同じ内容の ${done.int("skipped")} 件は重複のため追加していません。", 10)
                TextButton(onClick = { nav.go("/expedition/${done.str("planId")}") }) { Text("遠征計画を開く", fontSize = 11.sp) }
            }
            else -> Column {
                Text("追加先の計画と日付", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                SelectField(list.map { it.str("id").orEmpty() to it.str("title").orEmpty() }, plan.str("id").orEmpty(), { planId = it; dayDate = "" }, label = "計画", modifier = Modifier.fillMaxWidth())
                SelectField(if (dates.isEmpty()) listOf("" to "期間外") else dates.map { it to it }, eff, { dayDate = it }, label = "日付", modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                SmallButton(if (busy) "追加中…" else "この経路を追加", {
                    val p = plan ?: return@SmallButton
                    val rows = option.list("_rows")
                    if (rows.isEmpty()) { scope.launch { snackbar.showSnackbar("この経路には行程に追加できる区間がありません") }; return@SmallButton }
                    busy = true
                    scope.launch {
                        try {
                            val r = Api.rpc("transit/server-fns#appendRouteToExpeditionDay", jsonOf("planId" to p.str("id"), "date" to eff, "rows" to JsonArray(rows), "transportCost" to option.dbl("fare.ticket")?.let { Math.round(it) }))
                            if (r.bool("ok") == false) snackbar.showSnackbar(r.str("error") ?: "行程に追加できませんでした")
                            else { done = jsonOf("planId" to p.str("id"), "date" to eff, "added" to r.int("added"), "skipped" to r.int("skipped")); snackbar.showSnackbar("$eff の行程に ${r.int("added") ?: 0} 件追加しました") }
                        } catch (e: Throwable) { if (e is CancellationException) throw e; snackbar.showSnackbar(e.userMessage().ifBlank { "行程に追加できませんでした" }) } finally { busy = false }
                    }
                }, enabled = !busy && plan != null && dates.isNotEmpty(), icon = Icons.Outlined.Add, primary = true, modifier = Modifier.padding(top = 6.dp))
                Muted("行程の時刻・区間名を追加します。運賃が取得できている場合は交通費にも加算します（既存の行程は変更しません）。", 10)
            }
        }
    }
}

/** 注意事項と出典（Web: TransitNotices.jsx） */
@Composable
private fun TransitNotices(notices: List<JsonElement>, osm: JsonElement?, feeds: List<JsonElement>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        notices.forEach { n -> val warn = n.str("severity") == "warning"; Text("${if (warn) "⚠" else "ⓘ"} ${n.str("message").orEmpty()}", fontSize = 11.sp, color = if (warn) Brand.Warning else Brand.MutedFg) }
        val attributions = feeds.distinctBy { it.str("feedId") }.flatMap { listOfNotNull(it.str("attribution"), it.str("feedName")) }.filter { it.isNotBlank() }.distinct()
        Muted("出典: Transit API（https://api.transit.ls8h.com）／ データ提供元: ${if (attributions.isEmpty()) "取得結果に応じて表示" else attributions.joinToString("、")}${osm.str("attribution")?.let { "／ 地図: $it" } ?: "／ 地図: © OpenStreetMap contributors"}", 10)
        Muted("この経路は非公式のオープンデータに基づく候補です。正確性・完全性・最新性は保証されません。運休・臨時列車・運賃改定は反映されない場合があるため、実際の移動では事業者の公式情報を確認してください。", 10)
    }
}

/** 時刻表データの収録状況（Web: CoveragePanel.jsx） */
@Composable
private fun CoveragePanel() {
    var open by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    Panel {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("🗄 時刻表データの収録状況（奈良県周辺）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(if (open) "▴" else "▾")
        }
        if (open) {
            val q = rememberLoad("transit-coverage", version) { Api.rpc("transit/server-fns#getTransitCoverage", jsonOf()) }
            val data = q.data
            if (q.loading && data == null) Muted("収録状況を取得しています…", 11)
            q.error?.let { Text(it, fontSize = 11.sp, color = Brand.Destructive); SmallButton("再試行", { version++ }) }
            if (data?.bool("ok") == false) Text(data.str("error").orEmpty(), fontSize = 11.sp, color = Brand.Destructive)
            if (data?.bool("ok") == true) {
                val feeds = data.items("area.feeds")
                Muted("収録フィード ${data.int("totalFeeds") ?: 0} 件（うちODPT由来 ${data.int("odptFeeds") ?: 0} 件）／奈良駅周辺を範囲に含むフィード ${feeds.size} 件", 11)
                if (data.bool("narakotsu") != true) Text("⚠ 奈良交通の路線バス時刻表は収録されていません。同社バスのみで移動する区間は検索できません（徒歩や他社路線の候補が出る場合があります）。", fontSize = 11.sp, color = Brand.Warning)
                DataTable(listOf(Col("フィード（路線）", 180.dp), Col("事業者", 120.dp), Col("データ期限", 110.dp)), feeds.map { f ->
                    val end = f.str("serviceEnd")
                    listOf(f.str("name").orEmpty(), f.str("operator") ?: "—", (if (end != null && end.length >= 8) "${end.take(4)}-${end.substring(4, 6)}-${end.substring(6, 8)}" else "—") + if (f.bool("stale") == true) " 更新前" else "")
                }, boldFirst = false)
                val lic = feeds.mapNotNull { it.str("license") }.filter { it.isNotBlank() }.distinct().take(5)
                Muted("ライセンス・帰属表示はフィードごとに異なります（${lic.joinToString("、")}${if (lic.isNotEmpty()) " など" else "—"}）。「更新前」は提供元データの期限が過ぎた状態で、ダイヤ改正が反映されていない可能性があります。", 10)
            }
        }
    }
}
