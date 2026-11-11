package app.narabuspulse.feature.nara

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
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
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.MapLine
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val TABS = listOf("/nara" to "経路検索", "/nara/stops" to "停留所検索", "/nara/timetable" to "区間時刻表", "/nara/data" to "データ・診断")

@Composable
fun NaraNav(current: String) {
    val nav = LocalNav.current
    ChipRow(TABS, current, { if (it != current) nav.go(it) })
}

data class StopRef(val code: String, val name: String, val lat: Double? = null, val lon: Double? = null)

fun yen(v: Double?): String = v?.let { "¥" + java.text.NumberFormat.getInstance(java.util.Locale.JAPAN).format(it.toLong()) } ?: "情報なし"
private fun enc(s: String) = android.net.Uri.encode(s)

@Composable
fun ExternalLink(label: String, url: String?) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { runCatching { uri.openUri(url ?: "https://navi.narakotsu.co.jp/timetable/") } }) {
        Text(label, fontSize = 12.sp); Icon(Icons.Outlined.OpenInNew, null, Modifier.padding(start = 2.dp).width(14.dp))
    }
}

/** 停留所の候補入力（Web: components/nara/StopPicker.jsx） */
@Composable
fun StopPicker(label: String, value: StopRef?, onChange: (StopRef?) -> Unit, placeholder: String) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(value?.name.orEmpty()) }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var synced by remember { mutableStateOf(value?.code.orEmpty()) }
    LaunchedEffect(value?.code) { val v = value; if (v != null && v.code != synced) { synced = v.code; text = v.name } }
    fun query(raw: String) {
        val q = raw.trim(); error = ""; job?.cancel()
        if (q.isEmpty()) { items = emptyList(); return }
        job = scope.launch {
            delay(350); busy = true
            try {
                val res = Api.rpc("nara/server-fns#searchNaraStops", jsonOf("query" to q, "limit" to 12))
                items = res.items("items")
                if (items.isEmpty()) error = res.str("apiError") ?: "候補が見つかりませんでした"
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "候補を取得できませんでした" } } finally { busy = false }
        }
    }
    Column {
        OutlinedTextField(text, { text = it; if (value != null) onChange(null); query(it) }, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { synced = ""; text = ""; onChange(null); items = emptyList(); error = "" }) { Icon(Icons.Outlined.Close, "入力を消す") } },
            supportingText = if (busy) ({ Text("検索中…", fontSize = 10.sp) }) else null)
        value?.let { Muted("📍 コード ${it.code}${if (it.lat != null) " ・座標あり" else ""}", 10) }
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Warning)
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().clickable {
                val code = item.str("code").orEmpty(); val name = item.str("name").orEmpty()
                synced = code; onChange(StopRef(code, name, item.dbl("lat"), item.dbl("lon"))); text = name; items = emptyList(); error = ""
            }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(item.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold); item.str("kana")?.let { Muted(it, 10) } }
                Muted(item.str("code").orEmpty(), 10)
            }
        }
    }
}

@Composable
private fun Seg(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { options.forEach { (v, l) -> FilterChip(value == v, { onChange(v) }, label = { Text(l, fontSize = 11.sp) }) } }
}

/** 停留所（のりば）の実座標のみを描く地図（Web: StopMap.jsx） */
@Composable
fun StopMap(points: List<Triple<JsonElement, String?, Color>>, showLine: Boolean, height: Int = 224) {
    val valid = points.filter { it.first.dbl("lat") != null && it.first.dbl("lon") != null }
    if (valid.isEmpty()) return
    val pins = valid.mapIndexed { i, (p, label, color) -> MapPin("p$i", p.dbl("lat")!!, p.dbl("lon")!!, p.str("name").orEmpty(), color = color, size = if (label == null) 10.dp else 18.dp, label = label) }
    OsmMap(Modifier.fillMaxWidth().height(height.dp), pins = pins,
        lines = if (showLine && valid.size > 1) listOf(MapLine(valid.map { it.first.dbl("lat")!! to it.first.dbl("lon")!! }, INFO, 5f, dashed = true)) else emptyList(),
        center = valid.map { it.first.dbl("lat")!! }.average() to valid.map { it.first.dbl("lon")!! }.average(), zoom = if (valid.size > 1) 12.0 else 16.0, fitKey = valid.size)
}

/** 停留所検索（Web: nara.stops.index.jsx） */
@Composable
fun NaraStopsScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(listOf<JsonElement>()) }
    var error by remember { mutableStateOf("") }
    var apiError by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    fun submit() {
        val v = query.trim(); if (v.isEmpty()) return
        busy = true; items = emptyList(); error = ""; apiError = ""; done = false
        scope.launch {
            try { val res = Api.rpc("nara/server-fns#searchNaraStops", jsonOf("query" to v, "limit" to 20)); items = res.items("items"); apiError = res.str("apiError").orEmpty() }
            catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "停留所を取得できませんでした" } } finally { busy = false; done = true }
        }
    }
    ScreenList {
        item { NaraNav("/nara/stops") }
        item { Heading("停留所検索", "奈良バスなびwebの公式APIから停留所を検索します。保存済みの停留所は、公式APIに接続できないときも表示されます。") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("停留所名（一部でも可）", fontSize = 12.sp) }, placeholder = { Text("例 奈良駅 / 学園前 / 柳生", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
                SmallButton(if (busy) "検索中…" else "検索", { submit() }, enabled = !busy, icon = Icons.Outlined.Search, primary = true, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (error.isNotBlank()) item { ErrorText(error) }
        if (apiError.isNotBlank()) item { Note("公式APIの応答に失敗しました（$apiError）。保存済みの停留所のみ表示しています。", tone = Brand.Warning) }
        if (done && items.isEmpty() && error.isBlank()) item { Muted("該当する停留所が見つかりませんでした。", 12) }
        if (items.isNotEmpty()) item {
            Panel {
                items.forEachIndexed { i, it ->
                    if (i > 0) RowDivider()
                    Row(Modifier.fillMaxWidth().clickable { nav.go("/nara/stops/" + enc(it.str("code").orEmpty())) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.str("name").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Muted("${it.str("kana") ?: "かな情報なし"}${if (it.dbl("lat") != null) " ・座標あり" else ""}", 11)
                        }
                        Muted(it.str("code").orEmpty(), 11)
                    }
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Muted("出典: 奈良バスなびweb（奈良交通）", 10); ExternalLink("公式の停留所ページ", null) } }
    }
}

/** 停留所の詳細（Web: nara.stops.$code.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaraStopDetailScreen(code: String) {
    val nav = LocalNav.current
    val q = app.narabuspulse.ui.kit.rememberLoad("nara-stop", code) { Api.rpc("nara/server-fns#getNaraStop", jsonOf("code" to code)) }
    val data = q.data
    val stop = data?.objAt("stop")
    val poles = data.items("poles")
    fun hasCoord(p: JsonElement): Boolean { val la = p.dbl("lat"); val lo = p.dbl("lon"); return la != null && lo != null && !(la == 0.0 && lo == 0.0) }
    val located = poles.filter { hasCoord(it) }
    val name = stop.str("name") ?: "停留所 $code"
    val err = q.error ?: data?.takeIf { it.bool("ok") != true }?.let { it.str("apiError") ?: "停留所を取得できませんでした" }
    ScreenList {
        item { NaraNav("/nara/stops") }
        item { TextButton(onClick = { nav.go("/nara/stops") }) { Text("← 停留所検索に戻る", fontSize = 12.sp) } }
        item {
            MiniHeader(name, stop.str("kana")?.let { "$it（停留所コード $code）" } ?: "停留所コード $code") { ExternalLink("公式の時刻表", data.str("officialUrl")) }
        }
        if (q.loading && data == null) item { Muted("のりば情報を取得しています…", 12) }
        err?.let { item { ErrorText(it) } }
        if (data?.bool("ok") == true) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("のりば数", "${stop.int("poleCount") ?: poles.size}", Modifier.weight(1f))
                    StatTile("座標のあるのりば", "${located.size}", Modifier.weight(1f))
                    StatTile("取得元", if (data.bool("cached") == true) "保存済み（24時間）" else "公式API", Modifier.weight(1f))
                }
            }
            item {
                if (located.isNotEmpty()) Column { StopMap(located.map { Triple(it, null, INFO) }, showLine = false, height = 256); Muted(data.str("coordsNote").orEmpty(), 10) }
                else Muted("📍 座標を取得できませんでした。位置は推測せず、地図は表示していません。", 12)
            }
            item {
                Panel(title = "のりば一覧") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("ここを出発地に", { nav.go("/nara?from=${enc(code)}&fromName=${enc(name)}") })
                        SmallButton("ここを到着地に", { nav.go("/nara?to=${enc(code)}&toName=${enc(name)}") })
                        SmallButton("区間時刻表", { nav.go("/nara/timetable?from=${enc(code)}&fromName=${enc(name)}") })
                    }
                    poles.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.str("name")?.ifBlank { null } ?: "のりば", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Muted(if (hasCoord(p)) "%.5f, %.5f".format(p.dbl("lat"), p.dbl("lon")) else "座標なし", 10)
                            }
                            Muted(p.str("poleCode").orEmpty(), 10)
                        }
                    }
                    if (poles.isEmpty()) Muted("のりば情報を取得できませんでした。", 12)
                }
            }
        }
    }
}

/** 区間時刻表（Web: nara.timetable.jsx + TimetableRouteCard.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NaraTimetableScreen(args: Map<String, String>) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf(args["from"]?.let { StopRef(it, args["fromName"] ?: "コード $it") }) }
    var to by remember { mutableStateOf(args["to"]?.let { StopRef(it, args["toName"] ?: "コード $it") }) }
    var date by remember { mutableStateOf(args["date"]?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) } ?: jstTodayKey()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    fun submit() {
        error = ""
        val f = from; val t = to
        if (f == null || t == null) { error = "出発停留所と到着停留所を選んでください。"; return }
        busy = true
        scope.launch {
            try { val res = Api.rpc("nara/server-fns#getNaraSectionTimetable", jsonOf("fromCode" to f.code, "toCode" to t.code, "date" to date)); result = res; if (res.bool("ok") != true) error = res.str("error") ?: "区間時刻表を取得できませんでした。" }
            catch (e: Throwable) { if (e is CancellationException) throw e; result = null; error = e.userMessage().ifBlank { "区間時刻表を取得できませんでした。" } } finally { busy = false }
        }
    }
    val table = result?.objAt("table")
    ScreenList {
        item { NaraNav("/nara/timetable") }
        item { Heading("区間時刻表", "2つの停留所の間を走る系統と、停留所ごとの毎時発車時刻を表示します。平日・土曜・日祝は日付から自動で切り替わります。") }
        item {
            Panel {
                StopPicker("出発停留所", from, { from = it }, "例 近鉄奈良駅")
                StopPicker("到着停留所", to, { to = it }, "例 天理駅")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("日付", 12); DateField(date, { date = it })
                    SmallButton(if (busy) "取得中…" else "時刻表を表示", { submit() }, enabled = !busy, primary = true)
                }
                Muted("取得した時刻表は24時間保存します。", 10)
                if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
            }
        }
        if (table != null) {
            item {
                Column {
                    Text("${table.str("fromName") ?: from?.name.orEmpty()} → ${table.str("toName") ?: to?.name.orEmpty()}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Tag(table.str("dayTypeLabel") ?: "区分不明", INFO)
                        Muted("${table.items("routes").size}系統 / ${table.int("totalDepartures") ?: 0}便", 12)
                        if (result.bool("cached") == true) Tag("保存済み", Brand.MutedFg)
                    }
                    ExternalLink("公式の時刻表", result.str("officialUrl"))
                    table.str("message")?.let { Note(it) }
                    table.items("notes").forEach { n -> Muted("${n.str("symbol")?.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""}${n.str("note").orEmpty()}", 10) }
                }
            }
            val routes = table.items("routes")
            items(routes.size) { i -> TimetableRouteCard(routes[i]) }
            item {
                Column {
                    Text("⚠ 途中停留所の発着時刻は公式APIで提供されていないため、区間の途中経過時刻は表示できません。", fontSize = 10.sp, color = Brand.Warning)
                    Text("⚠ 系統ごとの定期券・運賃の適用条件は公式サイトでご確認ください。", fontSize = 10.sp, color = Brand.Warning)
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { nav.go("/nara/data") }) { Text("データ・診断", fontSize = 11.sp) }; Muted("で、取得元（公式API）の状態と収録件数を確認できます。", 10) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimetableRouteCard(route: JsonElement) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(route.str("indicator")?.ifBlank { null } ?: "—", INFO)
            Text(route.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        route.str("via")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
        Muted("のりば ${route.str("platform")?.ifBlank { null } ?: "—"}　所要 ${route.int("requiredMinutes")?.let { "${it}分" } ?: "—"}　運賃 ${yen(route.dbl("fare"))}　便数 ${route.int("departures") ?: 0}便（${route.str("firstDeparture").orEmpty()}〜${route.str("lastDeparture").orEmpty()}）", 11)
        route.items("hours").forEach { h ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Text("${h.str("hour") ?: h.int("hour")}時", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    h.items("times").forEach { t ->
                        Row {
                            Text(t.str("m") ?: t.int("m")?.toString().orEmpty(), fontSize = 13.sp)
                            listOfNotNull(t.str("s"), t.str("c")).filter { it.isNotBlank() }.forEach { s -> Text(s, fontSize = 8.sp, color = Brand.Warning, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        if (route.bool("sectionPassUnavailable") == true || route.bool("pricePassUnavailable") == true) Text("全区間の定期券では利用できない便が含まれます。", fontSize = 10.sp, color = Brand.Warning)
    }
}

