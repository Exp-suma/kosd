package app.narabuspulse.feature.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Net
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.parseOperation
import app.narabuspulse.feature.bus.rememberBusData
import app.narabuspulse.feature.bus.rememberVehicleGps
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.ui.kit.BarChart
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.MapTiles
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.Series
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val JST = ZoneId.of("Asia/Tokyo")
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val STAMP = DateTimeFormatter.ofPattern("M/d HH:mm")
private fun clock(ts: Long?) = ts?.let { CLOCK.format(Instant.ofEpochMilli(it).atZone(JST)) } ?: "—"
private fun stamp(ts: Long?) = ts?.takeIf { it > 0 }?.let { STAMP.format(Instant.ofEpochMilli(it).atZone(JST)) } ?: "—"
private fun kind(k: String?) = if (k == "forecast") "予測" else "実況"
private val WIND = listOf("北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東", "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西")
private fun wind(deg: Double?) = deg?.let { WIND[((it % 360) / 22.5).roundToInt() % 16] } ?: "—"
private fun cm(v: Double?) = v?.let { "${if (it % 1.0 == 0.0) it.toLong() else it} cm" } ?: "—"
private fun hx(s: String) = Color(android.graphics.Color.parseColor(s))

private const val RAIN_BASE = "https://www.jma.go.jp/bosai/jmatile/data/nowc"
private const val CLOUD_BASE = "https://www.jma.go.jp/bosai/himawari/data/satimg"
private const val SNOW_BASE = "https://www.jma.go.jp/bosai/jmatile/data/snow"
private fun rainUrl(f: JsonElement) = "$RAIN_BASE/${f.str("basetime")}/none/${f.str("validtime")}/surf/hrpns/{z}/{x}/{y}.png"
private fun cloudUrl(f: JsonElement) = "$CLOUD_BASE/${f.str("basetime")}/fd/${f.str("validtime")}/B13/TBB/{z}/{x}/{y}.jpg"
private fun snowUrl(f: JsonElement, element: String) = "$SNOW_BASE/${f.str("basetime")}/none/${f.str("validtime")}/surf/$element/{z}/{x}/{y}.png"

private val RAIN_SCALE = listOf("1未満" to "#f2fcff", "1〜5" to "#b4d7ff", "5〜10" to "#218cff", "10〜20" to "#0041ff", "20〜30" to "#fa00e5", "30〜50" to "#ff2800", "50以上" to "#b40068")
private val SNOWF_SCALE = listOf("3未満" to "#f0f0f8", "3〜5" to "#a0d2ff", "5〜10" to "#218cff", "10〜15" to "#0041ff", "15〜20" to "#fff500", "20〜25" to "#ff9900", "25〜30" to "#ff2800", "30以上" to "#b40068")
private val SNOWD_SCALE = listOf(1 to ("1〜5" to "#a0d2ff"), 5 to ("5〜20" to "#218cff"), 20 to ("20〜50" to "#0041ff"), 50 to ("50〜100" to "#fff500"), 100 to ("100〜150" to "#ff9900"), 150 to ("150〜200" to "#ff2800"), 200 to ("200以上" to "#b40068"))
private fun depthColor(v: Double?): Color = if (v == null || v < 1) hx("#9ca3af") else hx(SNOWD_SCALE.last { v >= it.first }.second.second)

private fun nearest(frames: List<JsonElement>, at: Long?, tolerance: Long): JsonElement? = if (at == null) null else frames.minByOrNull { abs((it.long("at") ?: 0) - at) }?.takeIf { abs((it.long("at") ?: 0) - at) <= tolerance }
private fun pickCloud(frames: List<JsonElement>, at: Long): JsonElement? = frames.lastOrNull { (it.long("at") ?: 0) <= at + 6 * 60_000 } ?: frames.firstOrNull()

private val LAYERS = listOf("rain" to "雨雲", "cloud" to "雲", "snow" to "降雪", "snowdepth" to "積雪", "bus" to "バス位置", "stops" to "路線・停留所", "spots" to "撮影地点")

/** 気象レーダー（Web: radar.jsx）。気象庁の雨雲・雲・降雪・積雪のタイルを地図に重ねる。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadarScreen() {
    val plan by Net.plan.collectAsState()
    var layers by remember { mutableStateOf(mapOf("rain" to true, "cloud" to true, "snow" to false, "snowdepth" to false, "bus" to true, "stops" to true, "spots" to true)) }
    var opacity by remember { mutableStateOf(mapOf("rain" to 0.8f, "cloud" to 0.7f, "snow" to 0.85f, "snowdepth" to 0.8f)) }
    var order by remember { mutableStateOf(listOf("cloud", "snowdepth", "snow", "rain")) }
    var index by remember { mutableStateOf(0) }
    var follow by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(700L) }
    var point by remember { mutableStateOf<Triple<Double, Double, String>?>(null) }
    var station by remember { mutableStateOf<JsonElement?>(null) }
    var tall by remember { mutableStateOf(false) }

    val frames = rememberLoad { Api.rpc("radar/server-fns#getRadarFrames", jsonOf()) }
    val snow = rememberLoad { Api.rpc("radar/snow-fns#getSnowRadar", jsonOf()) }
    LaunchedEffect(plan.radarMs) { while (true) { delay(plan.radarMs); frames.reload(); snow.reload() } }
    val stopsLoad = rememberLoad(layers["stops"]) { if (layers["stops"] == true) Api.rpc("server-fns#listStopCoords", jsonOf()).items() else emptyList() }
    val spotsLoad = rememberLoad(layers["spots"]) { if (layers["spots"] == true) Api.rpc("radar/server-fns#listRadarSpots", jsonOf()).items() else emptyList() }
    val bus = rememberBusData()
    val gps = rememberVehicleGps(bus.data?.fetchedAt)

    val vehicles = remember(bus.data, gps.byCode) {
        val now = System.currentTimeMillis()
        bus.data?.vehicles.orEmpty().mapNotNull { v -> val g = gps.byCode[v.carCode] ?: return@mapNotNull null; if (g.at == null || now - g.at > 30 * 60_000) null else Triple(v.name, parseOperation(v.operation).route, g.lat to g.lon) }
    }
    val rain = frames.data.objAt("rain"); val cloud = frames.data.objAt("cloud"); val snowData = snow.data.objAt("snow"); val obs = snow.data.objAt("obs")
    val rainFrames = rain.list("frames"); val cloudFrames = cloud.list("frames"); val snowFrames = snowData.list("frames")
    val depthFrames = remember(snowFrames) { snowFrames.filter { it.str("kind") == "observed" } }
    val stations = obs.list("stations")
    val timeline = remember(rainFrames, cloudFrames, snowFrames) {
        val buckets = LinkedHashMap<Long, Long>()
        fun push(ts: Long?) { if (ts != null) buckets.putIfAbsent(Math.round(ts / 300_000.0), ts) }
        rainFrames.forEach { push(it.long("at")) }; snowFrames.forEach { push(it.long("at")) }
        if (rainFrames.isEmpty()) cloudFrames.forEach { push(it.long("at")) }
        val times = buckets.values.sorted()
        val latestObserved = rainFrames.lastOrNull { it.str("kind") == "observed" }?.long("at") ?: snowData.long("latestAnalysisAt") ?: times.lastOrNull() ?: 0
        times.map { it to if (it > latestObserved) "forecast" else "observed" }
    }
    val latestObserved = timeline.indexOfLast { it.second == "observed" }.let { if (it < 0) maxOf(timeline.size - 1, 0) else it }
    LaunchedEffect(follow, latestObserved, timeline.size) { if (follow && timeline.isNotEmpty()) index = latestObserved; if (timeline.isEmpty()) index = 0 }
    LaunchedEffect(playing, speed, timeline.size) { while (playing && timeline.size > 1) { delay(speed); index = (index + 1) % timeline.size } }
    val entry = timeline.getOrNull(index); val at = entry?.first
    val rainFrame = nearest(rainFrames, at, 6 * 60_000); val snowFrame = nearest(snowFrames, at, 35 * 60_000); val depthFrame = nearest(depthFrames, at, 70 * 60_000)
    val cloudFrame = pickCloud(cloudFrames, at ?: System.currentTimeMillis())

    val tiles = order.mapNotNull { key ->
        if (layers[key] != true) return@mapNotNull null
        when (key) {
            "cloud" -> cloudFrame?.let { MapTiles("cloud", cloudUrl(it), opacity["cloud"] ?: 0.7f, maxNativeZoom = 5, minZoom = 3) }
            "snowdepth" -> depthFrame?.let { MapTiles("snowd", snowUrl(it, "snowd"), opacity["snowdepth"] ?: 0.8f) }
            "snow" -> snowFrame?.let { MapTiles("snowf", snowUrl(it, "snowf03h"), opacity["snow"] ?: 0.85f) }
            else -> rainFrame?.let { MapTiles("rain", rainUrl(it), opacity["rain"] ?: 0.8f) }
        }
    }
    val stops = stopsLoad.data.orEmpty().filter { it.dbl("lat") != null && it.dbl("lon") != null }.take(800)
    val spots = spotsLoad.data.orEmpty().filter { it.dbl("lat") != null }
    val pins = buildList {
        if (layers["stops"] == true) stops.forEach { add(MapPin("stop:${it.str("name")}", it.dbl("lat")!!, it.dbl("lon")!!, it.str("name").orEmpty(), "停留所", color = Color(0xFF475569), size = 6.dp)) }
        if (layers["snowdepth"] == true) stations.forEach { s -> s.dbl("lat")?.let { la -> add(MapPin("st:${s.str("id")}", la, s.dbl("lon") ?: 0.0, "${s.str("name")}（アメダス）", "積雪 ${cm(s.dbl("snow"))}", color = depthColor(s.dbl("snow")), size = 22.dp, label = s.dbl("snow")?.roundToInt()?.toString() ?: "—")) } }
        if (layers["spots"] == true) spots.forEach { add(MapPin("spot:${it.str("id") ?: it.str("name")}", it.dbl("lat")!!, it.dbl("lon")!!, it.str("name").orEmpty(), "撮影地点", color = Brand.Orange, size = 16.dp, label = "📷")) }
        if (layers["bus"] == true) vehicles.forEach { (name, route, ll) -> add(MapPin("bus:$name", ll.first, ll.second, name, "系統 $route", color = Color(0xFFF97316), size = 22.dp, label = "🚌")) }
        point?.let { add(MapPin("point", it.first, it.second, it.third.ifBlank { "選択した地点" }, color = Color(0xFFD94F2B), size = 14.dp)) }
    }

    ScreenList {
        item { Heading("気象レーダー", "気象庁の雨雲（高解像度降水ナウキャスト）・雲（ひまわり）・降雪（降雪短時間予報）・積雪（解析積雪深とアメダス観測）を、バス位置・停留所・撮影地点と重ねて表示します。") }
        if (frames.loading && frames.data == null && snow.loading) item { Muted("レーダーの時刻を取得しています…", 12) }
        if (frames.error != null && timeline.isEmpty()) item {
            Panel { Text("⚠ レーダーの時刻を取得できませんでした", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); Muted("気象庁の配信状況を確認してください。地図・バス位置・撮影地点はそのまま利用できます。", 11); SmallButton("再取得", { frames.reload(); snow.reload() }, icon = Icons.Outlined.Refresh) }
        }
        item {
            val counts = mapOf("bus" to vehicles.size, "stops" to stops.size, "spots" to spots.size, "snowdepth" to (obs.int("counts.snowCovered") ?: 0))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { LAYERS.forEach { (k, l) -> FilterChip(layers[k] == true, { layers = layers + (k to (layers[k] != true)) }, label = { Text("$l${counts[k]?.let { " $it" } ?: ""}", fontSize = 11.sp) }) } }
            if (plan.radarDetail == "low") Muted("${plan.label}：レーダーは${plan.radarFrames}コマに間引き、更新は${plan.radarMs / 60000}分ごとです。高精細な表示は高速通信モードに切り替えてください。", 10)
        }
        item {
            Box {
                OsmMap(Modifier.fillMaxWidth().height(if (tall) 620.dp else 400.dp), pins = pins, tiles = tiles, center = 34.55 to 135.85, zoom = 9.0,
                    onPinClick = { p -> when { p.id.startsWith("st:") -> { station = stations.firstOrNull { "st:${it.str("id")}" == p.id }; point = null }; p.id.startsWith("spot:") -> { station = null; point = Triple(p.lat, p.lon, p.title) } } },
                    onMapClick = { la, lo -> station = null; point = Triple(la, lo, "") })
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { SmallButton(if (tall) "縮小" else "拡大", { tall = !tall }) }
            }
        }
        item {
            // 時刻の操作（Web: RadarTimebar.jsx）
            val observedCount = timeline.count { it.second == "observed" }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(at), fontSize = 20.sp, fontWeight = FontWeight.Bold); Box(Modifier.padding(horizontal = 6.dp)) { Tag(kind(entry?.second), if (entry?.second == "forecast") Brand.Warning else Brand.Success) }
                    Muted("実況 ${observedCount}コマ／予測 ${timeline.size - observedCount}コマ", 10)
                }
                if (timeline.size > 1) Slider(index.toFloat(), { follow = false; index = it.roundToInt() }, valueRange = 0f..(timeline.size - 1).toFloat(), steps = maxOf(0, timeline.size - 2))
                Row { Muted(stamp(timeline.firstOrNull()?.first), 10); Box(Modifier.weight(1f)); Muted(stamp(timeline.lastOrNull()?.first), 10) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { follow = false; index = maxOf(index - 1, 0) }, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "1コマ戻る") }
                    IconButton(onClick = { if (playing) playing = false else if (timeline.size > 1) { if (index >= timeline.size - 1) index = 0; follow = false; playing = true } }, enabled = timeline.size > 1) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "一時停止" else "再生") }
                    IconButton(onClick = { follow = false; index = minOf(index + 1, timeline.size - 1) }, enabled = index < timeline.size - 1) { Icon(Icons.Outlined.ChevronRight, "1コマ進む") }
                    SelectField(listOf(1200L to "0.5×", 700L to "1×", 350L to "2×"), speed, { speed = it })
                    SmallButton("最新", { playing = false; follow = true; index = latestObserved }, primary = follow && index == latestObserved, modifier = Modifier.padding(start = 6.dp))
                }
                Muted(listOfNotNull(cloudFrame?.let { "雲 ${clock(it.long("at"))}" }, snowFrame?.let { "降雪 ${clock(it.long("at"))}" }, depthFrame?.let { "積雪 ${clock(it.long("at"))}" }, if (obs.bool("stale") == true) "古いデータ" else null).joinToString("　"), 10)
                Muted("雨雲は5分ごと（60分先まで）、降雪は1時間ごと（6時間先まで）、積雪は毎時の解析値を表示します。コマの無い時刻はそのレイヤーを表示しません。", 10)
                if (at != null && layers["rain"] == true && rainFrame == null && rainFrames.isNotEmpty()) Text("この時刻の雨雲データはありません（降水ナウキャストは60分先まで）。降雪の予報コマは6時間先まで選べます。", fontSize = 11.sp, color = Brand.Warning)
            }
        }
        item {
            // レイヤーの重ね順・透明度（Web: RadarLayerSettings.jsx）
            Panel(title = "レイヤーの重ね順・透明度", subtitle = "上にあるほど手前に表示します") {
                order.reversed().forEach { key ->
                    val label = LAYERS.toMap()[key].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp), color = if (layers[key] == true) Color.Unspecified else Brand.MutedFg)
                        Slider(opacity[key] ?: 0.8f, { opacity = opacity + (key to it) }, valueRange = 0.2f..1f, modifier = Modifier.weight(1f))
                        Muted("${((opacity[key] ?: 0.8f) * 100).roundToInt()}%", 10)
                        fun move(dir: Int) { val from = order.indexOf(key); val to = from + dir; if (to in order.indices) order = order.toMutableList().also { it[from] = it[to]; it[to] = key } }
                        IconButton(onClick = { move(1) }) { Icon(Icons.Outlined.KeyboardArrowUp, "手前へ") }; IconButton(onClick = { move(-1) }) { Icon(Icons.Outlined.KeyboardArrowDown, "奥へ") }
                    }
                }
            }
        }
        item {
            // データと表示の状態（Web: RadarStatusPanel.jsx）
            @Composable fun Source(title: String, data: JsonElement?, frame: JsonElement?, showKind: Boolean) {
                if (data == null) { Muted("$title：まだ時刻情報を取得していません。", 11); return }
                val ok = data.bool("ok") == true
                Row { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(if (ok) "✓ 取得中" else "⚠ 取得できません", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (ok) Brand.Success else Brand.Destructive) }
                Muted("${data.str("source.name").orEmpty()}　${if (showKind) "表示時刻" else "観測時刻"} ${frame?.let { clock(it.long("at")) + if (showKind) "（${kind(it.str("kind"))}）" else "" } ?: "—"}　更新間隔 ${data.str("source.intervalMin") ?: "—"}分　${data.int("source.forecastMin")?.takeIf { it > 0 }?.let { "予測 ${it}分先まで" } ?: "予測なし"}", 10)
                if (!ok) data.str("error")?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
            }
            Panel(title = "データと表示の状態") {
                Source("雨雲レーダー", rain, rainFrame, true); Source("雲レーダー", cloud, cloudFrame, false); Source("降雪レーダー（3時間降雪量）", snowData, snowFrame, true); Source("積雪量（気象庁 解析積雪深）", snowData, depthFrame, false)
                obs.objAt("counts")?.let { c -> Muted("アメダス積雪観測 ${c.int("total")}地点（積雪あり ${c.int("snowCovered")}／降雪中 ${c.int("snowing")}）・観測 ${stamp(obs.long("observedAt"))}${if (obs.bool("stale") == true) "（最新の取得に失敗したため前回値を表示）" else if (obs.bool("cached") == true) "（キャッシュ）" else ""}", 10) }
                if (obs.bool("stale") != true) obs.str("error")?.takeIf { it.isNotBlank() }?.let { Muted("積雪観測：$it", 10) }
                Muted("最終正常取得 ${if (frames.data.bool("ok") == true) stamp(frames.data.long("fetchedAt")) else "—"}　表示中レイヤー ${LAYERS.filter { layers[it.first] == true }.joinToString("・") { it.second }.ifBlank { "なし" }}", 10)
                listOf("予測コマは気象庁の降水ナウキャスト（60分先まで）と降雪短時間予報（6時間先まで）です。雲は観測のみで予測はありません。", "積雪深は約5kmメッシュの解析値、数値の札はアメダス観測点の実測です。観測点の無い山間部は補間していません。",
                    "雲が見えていても雨が降っているとは判定しません（降水は雨雲レーダーで確認）。", "古い時刻のコマは「最新」と表示しません。取得できない間は最後に取得できた時刻を保持します。").forEach { Muted("・$it", 10) }
            }
        }
        item {
            Panel(title = "凡例") {
                @Composable fun Scale(title: String, items: List<Pair<String, String>>) { Muted(title, 10); FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) { items.forEach { (l, c) -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(12.dp).background(hx(c), RoundedCornerShape(2.dp))); Muted(" $l ", 9) } } } }
                Scale("雨雲：降水強度（mm/h）", RAIN_SCALE); Scale("降雪：3時間降雪量（cm）", SNOWF_SCALE); Scale("積雪：積雪深（cm）", SNOWD_SCALE.map { it.second })
                Muted("雲は赤外画像（白いほど雲頂が高い）。", 10)
            }
        }
        val st = station; val pt = point
        if (st != null) item {
            // アメダス観測点（Web: SnowStationPanel.jsx）
            Panel(title = "❄ ${st.str("name")}（アメダス）", subtitle = "標高 ${st.dbl("alt")?.let { "${it.roundToInt()} m" } ?: "—"}／緯度 ${"%.3f".format(st.dbl("lat"))}・経度 ${"%.3f".format(st.dbl("lon"))}", action = { IconButton(onClick = { station = null }) { Icon(Icons.Outlined.Close, "閉じる") } }) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(depthColor(st.dbl("snow")), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(st.dbl("snow")?.roundToInt()?.toString() ?: "—", color = Color.White, fontWeight = FontWeight.Bold) }
                    Column(Modifier.padding(start = 8.dp)) { Text("積雪深 ${cm(st.dbl("snow"))}", fontSize = 14.sp, fontWeight = FontWeight.Bold); Muted("24時間前 ${cm(st.dbl("prev24"))}／${st.str("flagText").orEmpty()}", 10) } }
                fun signed(v: Double?) = v?.let { "${if (it > 0) "+" else ""}${if (it % 1.0 == 0.0) it.toLong() else it} cm" } ?: "—"
                Muted("24時間前との差 ${signed(st.dbl("d24"))}　48時間前 ${signed(st.dbl("d48"))}　72時間前 ${signed(st.dbl("d72"))}", 11)
                Muted("観測 ${stamp(obs.long("snowObservedAt") ?: obs.long("observedAt"))}　気温 ${st.dbl("temp")?.let { "$it ℃" } ?: "—"}　天気 ${st.str("weatherLabel") ?: "—"}", 11)
                Muted("降水量(1h) ${st.dbl("precip1h")?.let { "$it mm" } ?: "—"}　1時間降雪 ${cm(st.dbl("snow1h"))}　24時間降雪 ${cm(st.dbl("snow24h"))}", 11)
                Muted("出典：気象庁アメダス／公共データ利用規約。この地点の実測値であり、周辺の山間部へ補間した値ではありません。", 10)
            }
        } else if (pt != null) item(key = "point-${pt.first}-${pt.second}") { PointWeatherPanel(pt, stations) { point = null } }
        else item { Panel(title = "📍 地点の気象を確認") { Muted("地図上の任意の場所、または「撮影地点」をタップすると、その地点の降水・雲量・雨雪の判定・積雪深（最寄りのアメダス観測点）を表示します。", 11) } }
        item {
            Panel(title = "未実装・提供データなし") {
                listOf("雲量の数値・雲頂高度・雲の種類：ひまわりの画像タイルからは算出できません（数値プロダクトは未接続）。", "雨雲・降雪の移動方向（ベクトル）の自動算出：画像の移動解析は未実装です（風向は地点パネルの気象モデル値）。",
                    "降水種別の面的な判別：気象庁の降水ナウキャストに雨雪の区分がないため、面では降雪量（cm/3h）を表示し、雨雪の判定はアメダス観測点の値で行います。", "気象庁 高分解能雲情報・Weathernews WxTech：公開APIの提供条件がないため接続していません。",
                    "観測点の無い山間部の積雪補間：実測との区別が曖昧になるため行っていません（解析積雪深タイルの値のみ表示）。").forEach { Muted("・$it", 10) }
            }
        }
    }
}

/** 選んだ地点の気象（Web: PointWeatherPanel.jsx + PointRainChart.jsx） */
@Composable
private fun PointWeatherPanel(point: Triple<Double, Double, String>, stations: List<JsonElement>, onClose: () -> Unit) {
    val q = rememberLoad("%.3f".format(point.first), "%.3f".format(point.second)) { Api.rpc("radar/server-fns#getRadarPointWeather", jsonOf("lat" to point.first, "lon" to point.second)) }
    val nearest = produceState<JsonElement?>(null, point, stations.size) { value = Logic.call("radar/snow", "nearestSnowStation", kotlinx.serialization.json.JsonArray(stations), point.first, point.second, 45).takeIf { it is kotlinx.serialization.json.JsonObject } }.value
    val data = q.data; val cur = data.objAt("current")
    val now = System.currentTimeMillis()
    val all = data.list("series")
    val series = all.filter { (it.long("at") ?: 0) >= now - 15 * 60_000 }.take(9)
    fun mm(v: Double?) = v?.let { "${Math.round(it * 10) / 10.0} mm" } ?: "—"
    fun pc(v: Double?) = v?.let { "${it.roundToInt()}%" } ?: "—"
    Panel(title = "📍 ${point.third.ifBlank { "選択した地点" }}", subtitle = "緯度 ${"%.4f".format(point.first)}／経度 ${"%.4f".format(point.second)}", action = { Row { IconButton(onClick = { q.reload() }, enabled = !q.loading) { Icon(Icons.Outlined.Refresh, "再取得") }; IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "閉じる") } } }) {
        if (q.loading && data == null) Muted("この地点の気象を取得しています…", 11)
        q.error?.let { Text(it, fontSize = 11.sp, color = Brand.Destructive) }
        if (data == null) return@Panel
        Muted("降水量（現在） ${mm(cur.dbl("precip"))}　雲量 ${pc(cur.dbl("cloud"))}　気温 ${cur.dbl("temp")?.let { "$it ℃" } ?: "—"}　風 ${wind(cur.dbl("windDir"))}${cur.dbl("wind")?.let { " $it m/s" } ?: ""}", 12)
        val near = all.firstOrNull { (it.long("at") ?: 0) >= now - 15 * 60_000 } ?: all.firstOrNull()
        val nextHour = all.filter { (it.long("at") ?: 0) in now..(now + 3_600_000) }
        val maxNext = nextHour.maxOfOrNull { it.dbl("precip") ?: 0.0 }
        val nowPrecip = near.dbl("precip") ?: cur.dbl("precip"); val cloudV = cur.dbl("cloud") ?: near.dbl("cloud")
        val approaching = maxNext != null && maxNext >= 1 && maxNext > (nowPrecip ?: 0.0) + 0.5
        listOf(Triple("現在の雨", if (nowPrecip == null) "取得できません" else if (nowPrecip >= 1) "降っている（1mm以上）" else if (nowPrecip >= 0.1) "降っている（弱い）" else "降っていない", if ((nowPrecip ?: 0.0) >= 0.1) Brand.Warning else Brand.Success),
            Triple("雨雲の接近", if (maxNext == null) "取得できません" else if (approaching) "接近のおそれ（60分以内に降水増）" else "目立った接近なし（60分先まで）", if (approaching) Brand.Warning else Brand.Success),
            Triple("雲の多さ", if (cloudV == null) "取得できません" else if (cloudV >= 80) "ほぼ曇り（雲量80%以上）" else if (cloudV >= 40) "雲あり（40〜80%）" else "晴れ間あり（40%未満）", Brand.MutedFg)).forEach { (l, v, c) -> Row(Modifier.padding(vertical = 2.dp)) { Text(l, fontSize = 11.sp, modifier = Modifier.weight(1f)); Tag(v, c) } }
        Text("積雪・降雪（最寄りのアメダス実測）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        val s = nearest.objAt("station")
        if (s == null) Muted("45km以内に積雪を観測するアメダス地点がありません。値を推測して表示することはしません。", 11)
        else {
            val depth = s.dbl("snow"); val altDiff = s.dbl("alt")?.let { a -> data.dbl("elevation")?.let { (a - it).roundToInt() } }
            val chance = when { depth != null && depth >= 20 -> "雪景色の撮影が期待できます（実測の積雪20cm以上）"; depth != null && depth >= 1 -> "積雪あり（薄い雪化粧の可能性）"; s.str("weatherKind") == "snow" -> "降雪中（積雪が増える可能性）"; s.str("weatherKind") == "mixed" -> "雨雪の判別不確実（降雪の可能性あり）"; else -> "雪景色は期待しにくい（積雪0cm・降水なし）" }
            Muted("${s.str("name")}・${nearest.str("distanceKm")}km　${cm(depth)}　${s.str("weatherLabel").orEmpty()}${s.dbl("snow1h")?.takeIf { it > 0 }?.let { "／1時間降雪 ${cm(it)}" } ?: ""}", 11)
            Muted("観測点の標高 ${s.dbl("alt")?.let { "${it.roundToInt()} m" } ?: "—"}${altDiff?.let { "（この地点との差 ${if (it > 0) "+" else ""}$it m）" } ?: ""}／$chance", 10)
        }
        if (series.isNotEmpty()) {
            Text("降水の推移（15分ごと・2時間先まで）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            BarChart(series.map { it.str("time").orEmpty() }, listOf(Series("降水量", INFO, series.map { it.dbl("precip") ?: 0.0 })), height = 130.dp, labelEvery = 2, valueSuffix = "mm")
            Muted("棒＝降水量（mm）。未来は予報値、現在より前は実績値です。", 10)
        }
        Text("15分ごとの予報（2時間先まで）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        if (series.isEmpty()) Muted("予報データがありません。", 11)
        else DataTable(listOf(Col("時刻", 60.dp), Col("降水", 70.dp, true), Col("降水確率", 80.dp, true), Col("雲量", 70.dp, true)), series.take(8).map { r -> listOf(r.str("time").orEmpty(), r.dbl("precip")?.let { "${it}mm" } ?: "—", pc(r.dbl("pop")), pc(r.dbl("cloud"))) })
        Muted("出典：${data.str("source").orEmpty()}／取得 ${stamp(data.long("fetchedAt"))}${if (data.bool("cached") == true) "（キャッシュ）" else ""}${if (data.bool("stale") == true) "（最新の取得に失敗したため、前回の値を表示）" else ""}", 10)
        data.str("note")?.let { Muted(it, 10) }
        Muted("雨雲の移動方向はレーダー画像から算出していません。風向は気象モデルの値で、雨雲の通過方向ではありません。", 10)
    }
}
