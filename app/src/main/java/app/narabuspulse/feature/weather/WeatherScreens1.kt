package app.narabuspulse.feature.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.narabuspulse.feature.predict.MiniHeader
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val TABS = listOf("/weather" to "天気一覧", "/weather/map" to "天気マップ", "/weather/compare" to "市町村比較", "/weather/snow" to "降雪・積雪", "/weather/elevation" to "標高別・残雪", "/weather/similar" to "類似日", "/weather/ai" to "AI気象解説")

/** 天気のタブと出典（Web: weather.jsx のレイアウト） */
@Composable
fun WeatherTabs(current: String) {
    val nav = LocalNav.current
    ChipRow(TABS, current, { if (it != current) nav.go(it) })
}

@Composable
fun WeatherFootnote() = Muted("予報は Open-Meteo の予報モデル、平年値・過去実績は同アーカイブ（再解析値）を使っています。いずれも実測の観測値ではなく、取得できない項目は「取得不可」と表示します。他の市町村の値で代用することはありません。", 10)

/** lib/weather/format.js と同じ表示 */
fun valueOr(v: Double?, unit: String = "", digits: Int = 1): String {
    if (v == null || v.isNaN() || v.isInfinite()) return "取得不可"
    val r = if (digits == 0) v.roundToInt().toDouble() else Math.round(v * Math.pow(10.0, digits.toDouble())) / Math.pow(10.0, digits.toDouble())
    val s = if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    return "$s$unit"
}
private val WEEK = listOf("日", "月", "火", "水", "木", "金", "土")
fun dayLabel(key: String?): String = runCatching { val d = LocalDate.parse(key); "${d.monthValue}/${d.dayOfMonth}（${WEEK[d.dayOfWeek.value % 7]}）" }.getOrDefault(key ?: "—")
private val DT = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))
fun dateTimeLabel(ts: Long?): String = if (ts == null || ts == 0L) "—" else DT.format(Instant.ofEpochMilli(ts))
private val DIRS = listOf("北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東", "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西")
fun windLabel(deg: Double?): String = if (deg == null) "取得不可" else DIRS[((deg % 360) / 22.5).roundToInt() % 16]

/** 市町村一覧（lib/weather/municipalities.js）。 */
data class Muni(val code: String, val name: String, val kana: String, val region: String, val lat: Double?, val lon: Double?)
data class WeatherMeta(val regions: List<Pair<String, String>>, val munis: List<Muni>) {
    fun regionLabel(key: String?) = regions.firstOrNull { it.first == key }?.second ?: "—"
}

@Composable
fun rememberWeatherMeta(): WeatherMeta? = produceState<WeatherMeta?>(null) {
    val regions = Logic.constant("weather/municipalities", "REGIONS").items().map { it.str("key").orEmpty() to it.str("label").orEmpty() }
    val munis = Logic.constant("weather/municipalities", "MUNICIPALITIES").items().map { Muni(it.str("code").orEmpty(), it.str("name").orEmpty(), it.str("kana").orEmpty(), it.str("region").orEmpty(), it.dbl("lat"), it.dbl("lon")) }
    value = WeatherMeta(regions, munis)
}.value

/** 奈良県 市町村別の天気（Web: weather.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherListScreen() {
    val nav = LocalNav.current
    val meta = rememberWeatherMeta()
    val q = rememberLoad("weather-overview") { Api.rpc("weather/server-fns#getRegionOverview", JsonNull) }
    var region by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val data = q.data
    val items = data.items("items")
    val filtered = items.filter { (region == "all" || it.str("region") == region) && (query.isEmpty() || it.str("name").orEmpty().contains(query) || it.str("kana").orEmpty().contains(query)) }
    ScreenList {
        item { WeatherTabs("/weather") }
        item {
            MiniHeader("奈良県 市町村別の天気", "県内 ${meta?.munis?.size ?: 39} 市町村の地点ごとに気象データを取得しています。市町村を選ぶと30日間の予報と降雪・撮影適性を表示します。") {
                SmallButton("再取得", { q.reload() }, enabled = !q.loading, icon = Icons.Outlined.Refresh)
            }
        }
        item { OutfitCard() }
        if (q.loading && data == null) item { Muted("気象データを取得しています…", 12) }
        q.error?.let { item { ErrorText("気象データを取得できませんでした：$it") } }
        if (data != null) {
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    StatTile("登録市町村", "${meta?.munis?.size ?: items.size}", Modifier.weight(1f))
                    StatTile("データ取得済み", "${items.count { it.bool("available") == true }}", Modifier.weight(1f))
                    StatTile("❄ 降雪・積雪あり", "${items.count { (it.dbl("today.snow") ?: 0.0) > 0 || (it.dbl("current.snowDepth") ?: 0.0) > 0 }}", Modifier.weight(1f), tone = INFO)
                    StatTile("☂ 降水確率60%以上", "${items.count { (it.dbl("today.pop") ?: 0.0) >= 60 }}", Modifier.weight(1f), tone = Brand.Warning)
                }
            }
            item {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (listOf("all" to "すべて") + meta?.regions.orEmpty()).forEach { (k, l) -> FilterChip(region == k, { region = k }, label = { Text(l, fontSize = 11.sp) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("市町村名で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { nav.go("/weather/map") }) { Text("🗺 地図で見る", fontSize = 12.sp) }
                    }
                    val missing = data.list("missing").map { it.text }
                    Muted("予報取得：${data.str("source").orEmpty()}・更新 ${dateTimeLabel(data.long("fetchedAt"))}${if (missing.isNotEmpty()) "／取得できなかった地点：${missing.joinToString("、")}" else ""}", 10)
                    if ((data.int("staleCount") ?: 0) > 0) Text("最新の取得に失敗したため、${data.int("staleCount")}地点は直近に取得できた値（各カードの更新時刻）を表示しています。", fontSize = 10.sp, color = Brand.Warning)
                }
            }
            items(filtered.size) { i -> MunicipalityCard(filtered[i], meta) }
            if (filtered.isEmpty()) item { Muted("該当する市町村がありません。", 12) }
        }
        item { WeatherFootnote() }
    }
}

@Composable
private fun MunicipalityCard(item: JsonElement, meta: WeatherMeta?) {
    val nav = LocalNav.current
    Panel(modifier = Modifier.clickable { nav.go("/weather/${item.str("code")}") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.str("name").orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Muted("${item.str("kana").orEmpty()}・${meta?.regionLabel(item.str("region")) ?: ""}", 10)
            }
            WeatherSymbol(item.int("current.code") ?: item.int("today.code"), 28.dp)
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(valueOr(item.dbl("current.temp"), "℃"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Row { Text(valueOr(item.dbl("today.tmax"), "°", 0), fontSize = 12.sp, color = Brand.Destructive); Text(" / ", fontSize = 12.sp); Text(valueOr(item.dbl("today.tmin"), "°", 0), fontSize = 12.sp, color = INFO) }
            }
        }
        Muted("☂ ${valueOr(item.dbl("today.pop"), "%", 0)}　❄ 降雪 ${valueOr(item.dbl("today.snow"), "cm")}・積雪 ${valueOr(item.dbl("current.snowDepth") ?: item.dbl("today.snowDepth"), "cm")}　⛰ ${valueOr(item.dbl("elevation"), "m", 0)}", 11)
        if (item.bool("available") != true) Text("この地点の気象データを取得できませんでした（他の市町村の値で代用しません）", fontSize = 10.sp, color = Brand.Warning)
        else Muted("${item.str("source").orEmpty()}・更新 ${dateTimeLabel(item.long("fetchedAt"))}${if (item.bool("stale") == true) "・直近の取得値" else ""}", 10)
    }
}

/** 市町村別30日予報（Web: weather.$code.jsx） */
@Composable
fun WeatherDetailScreen(code: String) {
    val nav = LocalNav.current
    val meta = rememberWeatherMeta()
    val q = rememberLoad("weather-detail", code) { Api.rpc("weather/server-fns#getMunicipalityDetail", jsonOf("code" to code)) }
    val data = q.data
    val item = data?.objAt("item")
    ScreenList {
        item { WeatherTabs("/weather") }
        item {
            MiniHeader(item?.let { "${it.str("name")}の天気" } ?: "市町村の天気",
                item?.let { "${it.str("kana")}・${meta?.regionLabel(it.str("region")) ?: ""}・標高 ${valueOr(it.dbl("elevation"), "m", 0)}（${it.str("elevationSource").orEmpty()}）" } ?: "地点ごとの気象データを表示します。") {
                TextButton(onClick = { nav.go("/weather") }) { Text("← 一覧へ", fontSize = 12.sp) }
            }
        }
        if (q.loading && data == null) item { Muted("気象データを取得しています…", 12) }
        q.error?.let { item { ErrorText("気象データを取得できませんでした：$it") } }
        if (data != null && item == null) item { Muted("市町村が見つかりません。", 12) }
        if (item != null) {
            item { CurrentWeatherPanel(item) }
            if (item.list("points").size > 1) item { PointsPanel(item.items("points")) }
            item { DayForecastList(item.items("days")) }
            item { ShootSuitability(item) }
            item { WeatherAccuracyPanel(item.str("code").orEmpty()) }
            val hist = data.items("history")
            if (hist.isNotEmpty()) item {
                Panel(title = "予報の更新履歴") {
                    hist.forEach { r -> Muted("${r.str("forecast_date").orEmpty()}　取得 ${dateTimeLabel(r.long("fetched_at"))}　${r.str("source").orEmpty()}　${r.int("day_count") ?: 0}日分", 11) }
                    Muted("同じ日は上書きし、日ごとに1件保存しています。保存した予報と後日の再解析値を比較したものが上の「予報精度」です。", 10)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { nav.go("/weather/compare") }) { Text("他の市町村と比較する", fontSize = 11.sp) }
                    TextButton(onClick = { nav.go("/weather/snow") }) { Text("降雪・積雪分析", fontSize = 11.sp) }
                    TextButton(onClick = { nav.go("/shoot/light") }) { Text("撮影支援AIへ", fontSize = 11.sp) }
                }
            }
        }
        item { WeatherFootnote() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentWeatherPanel(item: JsonElement) {
    val c = item.objAt("current"); val t = item.items("days").firstOrNull()
    if (c == null) { Panel { Muted("この地点の現在の天気を取得できませんでした。他の市町村の値で代用しません。", 12) }; return }
    val rows = listOf(
        "気温" to valueOr(c.dbl("temp"), "℃"), "体感温度" to valueOr(c.dbl("feels"), "℃"), "最高気温" to valueOr(t.dbl("tmax"), "℃"), "最低気温" to valueOr(t.dbl("tmin"), "℃"),
        "降水確率" to valueOr(t.dbl("pop"), "%", 0), "降水量" to valueOr(c.dbl("precip") ?: t.dbl("precip"), "mm"), "降雪量" to valueOr(c.dbl("snow") ?: t.dbl("snow"), "cm"),
        "積雪深" to valueOr(c.dbl("snowDepth") ?: t.dbl("snowDepth"), "cm"), "風向" to windLabel(c.dbl("windDir")), "風速" to valueOr(c.dbl("wind"), "m/s"), "湿度" to valueOr(c.dbl("humidity"), "%", 0),
        "雲量" to valueOr(c.dbl("cloud"), "%", 0), "視程" to valueOr(c.dbl("visibility"), "m", 0), "日の出" to (t.str("sunrise") ?: "取得不可"), "日の入り" to (t.str("sunset") ?: "取得不可"),
    )
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Muted("現在の天気", 11)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(valueOr(c.dbl("temp"), "℃"), fontSize = 26.sp, fontWeight = FontWeight.Bold); WeatherSymbol(c.int("code"), 24.dp, showLabel = true) }
            }
            Column(horizontalAlignment = Alignment.End) { Muted(item.str("source").orEmpty(), 10); Muted("更新 ${dateTimeLabel(item.long("fetchedAt"))}", 10) }
        }
        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 3) {
            rows.forEach { (l, v) ->
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) { Muted(l, 10); Text(v, fontSize = 12.sp, fontWeight = if (v.startsWith("取得不可")) FontWeight.Normal else FontWeight.Bold, color = if (v.startsWith("取得不可")) Brand.MutedFg else MaterialTheme.colorScheme.onSurface) }
            }
        }
        Muted("標高 ${valueOr(item.dbl("elevation"), "m", 0)}（${item.str("elevationSource").orEmpty()}）。積雪深は予報モデルの推定値で、この地点の実測値ではありません。", 10)
    }
}

@Composable
private fun PointsPanel(points: List<JsonElement>) {
    val temps = points.mapNotNull { it.dbl("temp") }
    val spread = if (temps.size > 1) temps.max() - temps.min() else null
    Panel(title = "地点ごとの違い（代表地点と山間部）") {
        Muted("標高や地形で天候が変わるため、代表地点の値だけで地域全体を断定しないでください。", 10)
        DataTable(listOf(Col("地点", 120.dp), Col("標高", 64.dp, true), Col("現在気温", 72.dp, true), Col("最高/最低", 80.dp, true), Col("積雪深（推定）", 96.dp, true)),
            points.map { p -> listOf(p.str("name").orEmpty() + if (p.bool("main") == true) "（代表）" else "", valueOr(p.dbl("elevation"), "m", 0), if (p.bool("available") == true) valueOr(p.dbl("temp"), "℃") else "取得不可", "${valueOr(p.dbl("tmax"), "°", 0)} / ${valueOr(p.dbl("tmin"), "°", 0)}", valueOr(p.dbl("snowDepth"), "cm")) })
        Muted(if (spread == null) "地点間の気温差は算出できませんでした。" else "地点間の気温差は最大 ${(spread * 10).roundToInt() / 10.0}℃ です（同時刻の現在気温）。", 10)
    }
}

private val SEGMENTS = listOf(Triple("short", "1〜7日", "短期予報：実際の予報値です"), Triple("medium", "8〜14日", "中期予報：参考値で、確度は下がります"), Triple("long", "15〜30日", "長期予測：平年値と降水日の割合による目安です（確定予報ではありません）"))

@Composable
fun DayForecastList(days: List<JsonElement>) {
    Panel(title = "30日間の天気", subtitle = "1〜7日は短期予報、8〜14日は中期予報、15〜30日は平年値による長期予測です") {
        SEGMENTS.forEach { (key, label, note) ->
            val rows = days.filter { it.str("segment") == key }
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)); Muted(note, 10)
            if (rows.isEmpty()) Muted("この期間のデータを取得できませんでした。", 11)
            rows.forEachIndexed { i, d -> if (i > 0) RowDivider(); DayForecastRow(d) }
        }
    }
}

@Composable
private fun DayForecastRow(day: JsonElement) {
    val normal = day.str("kind") != "forecast"
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayLabel(day.str("date")), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp))
            WeatherSymbol(day.int("code"), 18.dp)
            Text(valueOr(day.dbl("tmax"), "°", 0), fontSize = 12.sp, color = Brand.Destructive, modifier = Modifier.padding(start = 6.dp)); Text("/", fontSize = 12.sp); Text(valueOr(day.dbl("tmin"), "°", 0), fontSize = 12.sp, color = INFO)
            Text("  ☂${valueOr(day.dbl("pop"), "%", 0)}", fontSize = 11.sp, modifier = Modifier.weight(1f))
            val w = day.dbl("wind")
            Muted(if (w == null) "—" else "${windLabel(day.dbl("windDir"))} ${w.roundToInt()}m", 10)
        }
        Muted("降水 ${valueOr(day.dbl("precip"), "mm")}　降雪 ${valueOr(day.dbl("snow"), "cm")}　積雪 ${valueOr(day.dbl("snowDepth"), "cm")}", 10)
        if (normal) Muted("平年値（過去6年の同じ日付の平均）・降水日の割合 ${valueOr(day.dbl("pop"), "%", 0)}", 10)
        else { val a = day.dbl("anomaly"); Muted("日の出 ${day.str("sunrise") ?: "—"} / 日の入り ${day.str("sunset") ?: "—"}　平年差 ${if (a == null) "取得不可" else "${if (a > 0) "+" else ""}${valueOr(a)}℃"}", 10) }
    }
}

private fun scoreDay(d: JsonElement): Int? {
    if (d.str("kind") != "forecast") return null
    var s = 100.0
    s -= (d.dbl("pop") ?: 50.0) * 0.7; s -= (d.dbl("precip") ?: 0.0) * 10; s -= maxOf(0.0, (d.dbl("wind") ?: 0.0) - 5) * 2
    if ((d.dbl("snow") ?: 0.0) > 0) s -= 10
    return s.roundToInt().coerceIn(0, 100)
}
private fun grade(s: Int?) = when { s == null -> "—"; s >= 75 -> "好適"; s >= 55 -> "適"; s >= 35 -> "条件付き"; else -> "不向き" }

@Composable
private fun ShootSuitability(item: JsonElement) {
    val nav = LocalNav.current
    val days = item.items("days").filter { it.str("kind") == "forecast" }.take(14)
    Panel(title = "📷 撮影適性（${item.str("name")}・気象データとの組み合わせ）", action = { TextButton(onClick = { nav.go("/shoot/light") }) { Text("光線を確認 →", fontSize = 11.sp) } }) {
        if (days.isEmpty()) { Muted("この地点の予報が取得できないため、撮影適性を判定できません。", 12); return@Panel }
        val scored = days.map { it to scoreDay(it) }.sortedByDescending { it.second ?: 0 }.take(5)
        val snowDays = days.filter { (it.dbl("snow") ?: 0.0) > 0 || (it.dbl("snowDepth") ?: 0.0) > 0 }
        val rainDays = days.filter { (it.dbl("pop") ?: 0.0) >= 60 }
        val maxDepth = days.maxOf { it.dbl("snowDepth") ?: 0.0 }
        val best = scored.firstOrNull()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("総合評価（14日の最良日）", best?.let { "${grade(it.second)}・${it.second}点" } ?: "—", Modifier.weight(1f), tone = Brand.Success)
            StatTile("❄ 雪景色の可能性", if (snowDays.isNotEmpty()) "${snowDays.size}日あり" else "低い", Modifier.weight(1f), tone = INFO)
            StatTile("雨天の可能性", if (rainDays.isNotEmpty()) "${rainDays.size}日" else "低い", Modifier.weight(1f), tone = Brand.Warning)
        }
        Muted(best?.let { "${dayLabel(it.first.str("date"))} 降水確率 ${valueOr(it.first.dbl("pop"), "%", 0)}" } ?: "判定できません", 10)
        Muted(if (snowDays.isNotEmpty()) "最大積雪深 ${valueOr(maxDepth, "cm")}（推定）・${snowDays.take(2).joinToString("・") { dayLabel(it.str("date")) }}" else "14日間で降雪・積雪の予報はありません", 10)
        Muted(if (rainDays.isNotEmpty()) "降水確率60%以上: " + rainDays.take(3).joinToString("・") { dayLabel(it.str("date")) } else "降水確率60%以上の日はありません", 10)
        Text("☀ 晴天撮影に適した日（降水確率・降水量・風速から算出）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        scored.forEach { (d, s) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(dayLabel(d.str("date")), fontSize = 12.sp, modifier = Modifier.width(76.dp)); Tag(grade(s), if ((s ?: 0) >= 55) Brand.Success else Brand.Warning)
                Muted("  降水確率 ${valueOr(d.dbl("pop"), "%", 0)}・降水量 ${valueOr(d.dbl("precip"), "mm")}・風速 ${valueOr(d.dbl("wind"), "m/s")}", 10)
            }
        }
        Muted("気温と積雪の関係：14日間の最低気温 ${valueOr(days.minOf { it.dbl("tmin") ?: 99.0 }, "℃")}・最大積雪深 ${valueOr(maxDepth, "cm")}（推定）。" +
            (if (maxDepth > 0) "積雪があるため、雪景色と低温時の路面状況に注意してください。" else "積雪の予報がなく、路面は乾きやすい見込みです。") +
            "太陽の方向は時刻と撮影地点で変わるため、この画面では断定しません。車両の運行情報と組み合わせる場合は車両位置と撮影支援AIを併用してください。", 10)
    }
}

@Composable
private fun WeatherAccuracyPanel(code: String) {
    val q = rememberLoad("weather-accuracy", code) { Api.rpc("weather/server-fns#getForecastAccuracy", jsonOf("code" to code)) }
    val d = q.data
    Panel(title = "予報精度（過去の予報と実測の比較）") {
        if (q.loading && d == null) Muted("照合しています…", 11)
        if (q.error != null) Text("精度を取得できませんでした", fontSize = 11.sp, color = Brand.Destructive)
        if (d != null) {
            if ((d.int("days") ?: 0) == 0) Muted(d.str("note").orEmpty(), 11)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("照合した日数", "${d.int("days")}日", Modifier.weight(1f))
                StatTile("最高気温の誤差", d.dbl("maeMax")?.let { "${valueOr(it)}℃" } ?: "—", Modifier.weight(1f))
                StatTile("最低気温の誤差", d.dbl("maeMin")?.let { "${valueOr(it)}℃" } ?: "—", Modifier.weight(1f))
                StatTile("降水の有無の一致", d.dbl("precipHit")?.let { "${valueOr(it, "", 0)}%" } ?: "—", Modifier.weight(1f), unit = "${d.int("precipSamples") ?: 0}日分")
            }
            Muted("対象期間 ${d.str("period.from")?.let { "$it 〜 ${d.str("period.to")}" } ?: "—"}・${d.str("source").orEmpty()}${d.str("note")?.let { "／$it" } ?: ""}", 10)
        }
    }
}
