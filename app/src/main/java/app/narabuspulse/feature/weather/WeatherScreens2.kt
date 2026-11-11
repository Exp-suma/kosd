package app.narabuspulse.feature.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.research.rememberRunner
import app.narabuspulse.ui.kit.CHART_COLORS
import app.narabuspulse.ui.kit.HBar
import app.narabuspulse.ui.kit.LineChart
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.Series
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonElement
import kotlin.math.roundToInt

/** 市町村の複数選択（Web: MunicipalityPicker.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MunicipalityPicker(value: List<String>, onChange: (List<String>) -> Unit, max: Int, hint: String? = null) {
    val meta = rememberWeatherMeta()
    var region by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    val list = meta?.munis.orEmpty().filter { (region == "all" || it.region == region) && (query.isEmpty() || it.name.contains(query) || it.kana.contains(query)) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("市町村名・よみがなで絞り込み", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
            Muted("  ${value.size}/$max 選択", 11)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf("all" to "すべて") + meta?.regions.orEmpty()).forEach { (k, l) -> FilterChip(region == k, { region = k }, label = { Text(l, fontSize = 11.sp) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            list.forEach { m ->
                val active = m.code in value
                FilterChip(active, { if (active) onChange(value - m.code) else if (value.size < max) onChange(value + m.code) }, enabled = active || value.size < max, label = { Text(m.name, fontSize = 11.sp) })
            }
        }
        if (meta != null && list.isEmpty()) Muted("該当する市町村がありません。", 11)
        hint?.let { Muted(it, 10) }
    }
}

private fun weekStats(item: JsonElement): Map<String, Double?> {
    val w = item.items("days").take(7)
    val tmax = w.mapNotNull { it.dbl("tmax") }; val tmin = w.mapNotNull { it.dbl("tmin") }; val pop = w.mapNotNull { it.dbl("pop") }; val an = w.mapNotNull { it.dbl("anomaly") }
    return mapOf("tmax" to tmax.maxOrNull(), "tmin" to tmin.minOrNull(), "popMax" to pop.maxOrNull(), "anomaly" to (if (an.isEmpty()) null else an.average()), "snow" to w.sumOf { it.dbl("snow") ?: 0.0 })
}

/** 市町村間の天気比較（Web: weather.compare.jsx + CompareTable + CompareTempChart） */
@Composable
fun WeatherCompareScreen() {
    val meta = rememberWeatherMeta()
    var codes by remember { mutableStateOf(listOf("nara", "uda", "gojo", "tenkawa", "totsukawa")) }
    val q = rememberLoad("weather-region", codes.sorted().joinToString(",")) { if (codes.isEmpty()) null else Api.rpc("weather/server-fns#getRegionWeather", jsonOf("codes" to codes)) }
    val data = q.data
    val items = data.items("items")
    ScreenList {
        item { WeatherTabs("/weather/compare") }
        item { Heading("市町村間の天気比較", "市町村を選んで、気温・降水量・降水確率・降雪・積雪・風・平年との差を並べて比較します。") }
        item { MunicipalityPicker(codes, { codes = it }, 6, "最大6市町村まで選べます（例：奈良市・宇陀市・五條市・天川村・十津川村）。") }
        if (q.loading && codes.isNotEmpty()) item { Muted("比較データを取得しています…", 12) }
        q.error?.let { item { ErrorText("比較データを取得できませんでした：$it") } }
        if (items.isNotEmpty()) {
            item {
                val stats = items.map { weekStats(it) }
                val rows: List<Pair<String, (Int) -> String>> = listOf(
                    "地域" to { i -> meta?.regionLabel(items[i].str("region")) ?: "—" },
                    "標高" to { i -> valueOr(items[i].dbl("elevation"), "m", 0) },
                    "現在の気温" to { i -> valueOr(items[i].dbl("current.temp"), "℃") },
                    "体感温度" to { i -> valueOr(items[i].dbl("current.feels"), "℃") },
                    "最高気温（今日）" to { i -> valueOr(items[i].items("days").firstOrNull().dbl("tmax"), "℃") },
                    "最低気温（今日）" to { i -> valueOr(items[i].items("days").firstOrNull().dbl("tmin"), "℃") },
                    "7日間の最高" to { i -> valueOr(stats[i]["tmax"], "℃") },
                    "7日間の最低" to { i -> valueOr(stats[i]["tmin"], "℃") },
                    "降水確率（今日）" to { i -> valueOr(items[i].items("days").firstOrNull().dbl("pop"), "%", 0) },
                    "7日間の最大降水確率" to { i -> valueOr(stats[i]["popMax"], "%", 0) },
                    "降水量（今日）" to { i -> valueOr(items[i].items("days").firstOrNull().dbl("precip"), "mm") },
                    "降雪量（今日）" to { i -> valueOr(items[i].items("days").firstOrNull().dbl("snow"), "cm") },
                    "7日間の降雪計" to { i -> valueOr(stats[i]["snow"], "cm") },
                    "積雪深（モデル推定）" to { i -> valueOr(items[i].dbl("current.snowDepth") ?: items[i].items("days").firstOrNull().dbl("snowDepth"), "cm") },
                    "風速" to { i -> valueOr(items[i].dbl("current.wind"), "m/s") },
                    "風向" to { i -> windLabel(items[i].dbl("current.windDir")) },
                    "平年との気温差（7日平均）" to { i -> stats[i]["anomaly"]?.let { a -> "${if (a > 0) "+" else ""}${(a * 10).roundToInt() / 10.0}℃" } ?: "取得不可" },
                    "データ取得元" to { i -> items[i].str("source").orEmpty() },
                )
                Panel {
                    DataTable(listOf(Col("項目", 150.dp)) + items.map { Col(it.str("name").orEmpty(), 92.dp, true) }, rows.map { (l, f) -> listOf(l) + items.indices.map(f) })
                }
            }
            item {
                val dates = items.first().items("days").take(14).mapNotNull { it.str("date") }
                Panel(title = "地域別 気温比較グラフ", subtitle = "最高気温（℃）・14日先まで。8日目以降は中期予報で、確度は下がります。") {
                    LineChart(dates.map { dayLabel(it).substringBefore("（") }, items.mapIndexed { idx, it ->
                        Series(it.str("name").orEmpty(), CHART_COLORS[idx % CHART_COLORS.size], dates.map { d -> it.items("days").firstOrNull { x -> x.str("date") == d }.dbl("tmax") ?: Double.NaN })
                    }, height = 240.dp, labelEvery = 2, valueSuffix = "℃")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items.forEachIndexed { idx, it -> Text("● ${it.str("name")}", fontSize = 10.sp, color = CHART_COLORS[idx % CHART_COLORS.size]) } }
                }
            }
            item { Muted("予報取得：${data.str("source").orEmpty()}／平年値：${data.str("normalSource").orEmpty()}／更新 ${dateTimeLabel(data.long("fetchedAt"))}。データを取得できなかった市町村は「取得不可」と表示し、他の市町村の値で代用しません。", 10) }
        }
        item { WeatherFootnote() }
    }
}

private fun remainLabel(item: JsonElement): String {
    val depth = item.dbl("next14.maxDepth"); val minT = item.dbl("next14.minTemp"); val days = item.int("next14.snowDays") ?: 0
    return when {
        depth == null -> if (days > 0) "降雪の可能性あり（積雪深の推定値なし）" else "積雪の可能性は低い"
        depth > 0 && minT != null && minT <= 0 -> "雪が残る可能性が高い（推定）"
        depth > 0 -> "一時的に積もる可能性（推定）"
        days > 0 -> "降っても積もりにくい（推定）"
        else -> "積雪の可能性は低い"
    }
}

/** 市町村別の降雪・積雪分析（Web: weather.snow.jsx + SnowPanel.jsx） */
@Composable
fun WeatherSnowScreen() {
    val meta = rememberWeatherMeta()
    var codes by remember { mutableStateOf(listOf("gojo", "totsukawa", "tenkawa", "nosegawa", "kamikitayama", "shimokitayama", "kawakami", "soni")) }
    val q = rememberLoad("weather-snow", codes.sorted().joinToString(",")) { if (codes.isEmpty()) null else Api.rpc("weather/server-fns#getSnowAnalysis", jsonOf("codes" to codes)) }
    val data = q.data
    val items = data.items("items")
    ScreenList {
        item { WeatherTabs("/weather/snow") }
        item { Heading("市町村別の降雪・積雪分析", "南部・東部の山間部を中心に、降雪の可能性・降雪量・積雪深・標高別の気温差・過去90日の降雪実績をまとめています。") }
        item { MunicipalityPicker(codes, { codes = it }, 8, "山間部の市町村は、詳細画面で代表地点と追加地点（例：洞川・折立）の差も確認できます。") }
        if (q.loading && codes.isNotEmpty()) item { Muted("降雪データを集計しています…", 12) }
        q.error?.let { item { ErrorText("降雪データを取得できませんでした：$it") } }
        if (data != null) {
            item {
                Panel {
                    DataTable(
                        listOf(Col("市町村", 120.dp), Col("標高", 60.dp, true), Col("14日降雪計", 80.dp, true), Col("降雪日数", 64.dp, true), Col("最低気温", 64.dp, true), Col("積雪深（推定）", 92.dp, true), Col("過去90日降雪", 100.dp, true), Col("雪が残る可能性", 200.dp)),
                        items.map { it -> listOf("${it.str("name")}（${meta?.regionLabel(it.str("region")) ?: ""}${if (it.bool("mountain") == true) "・山間" else ""}）", valueOr(it.dbl("elevation"), "m", 0), valueOr(it.dbl("next14.snowSum"), "cm"),
                            "${it.int("next14.snowDays")?.toString() ?: "—"}日", valueOr(it.dbl("next14.minTemp"), "℃"), valueOr(it.dbl("next14.maxDepth"), "cm"), "${valueOr(it.dbl("past.snowSum"), "cm")} (${it.int("past.snowDays") ?: 0}日)", remainLabel(it)) },
                    )
                }
            }
            item {
                Panel(title = "❄ 標高と気温の関係（14日間の最低気温）") {
                    items.sortedByDescending { it.dbl("elevation") ?: 0.0 }.forEach { it ->
                        val t = it.dbl("next14.minTemp")
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(it.str("name").orEmpty(), fontSize = 11.sp, modifier = Modifier.width(76.dp)); Muted(valueOr(it.dbl("elevation"), "m", 0), 10)
                            HBar(if (t == null) 0f else (((t + 10) * 4).coerceIn(4.0, 100.0) / 100).toFloat(), Modifier.weight(1f).padding(horizontal = 6.dp), color = INFO)
                            Text(valueOr(t, "℃"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Panel(title = "データの見方") {
                    listOf("降雪量と積雪深は別々に扱っています。降雪量は「新しく降った雪の量」、積雪深は「積もっている深さ」です。", "積雪深は予報モデルの推定値です。この地点の実測の積雪深は取得していないため、実測として表示しません。",
                        "過去90日の降雪は再解析値（${data.str("pastSource").orEmpty()}）で、アメダス等の実測観測値ではありません。", "山間部は標高差で天候が変わるため、詳細画面で追加地点の値も確認してください。").forEach { Muted("・$it", 11) }
                    Muted("予報取得：${data.str("source").orEmpty()}・${dateTimeLabel(items.firstOrNull().long("fetchedAt"))}", 10)
                }
            }
        }
        item { WeatherFootnote() }
    }
}

private val BLOCKS = listOf("coldest" to "最も気温が低くなる可能性", "snowRisk" to "雪が降る可能性がある地域", "wettest" to "雨が降りやすい地域", "mountainGap" to "山間部と平野部の気温差", "anomaly" to "平年との比較")

/** AI地域別気象解説（Web: weather.ai.jsx + AiRegionPanel.jsx） */
@Composable
fun WeatherAiScreen() {
    var codes by remember { mutableStateOf(listOf("nara", "tenri", "uda", "gojo", "tenkawa", "totsukawa")) }
    val (st, launch) = rememberRunner("分析できませんでした")
    val data = st.result
    ScreenList {
        item { WeatherTabs("/weather/ai") }
        item { Heading("AI地域別気象解説", "選んだ市町村の気象データをもとに、気温差・雪・雨の傾向をAIが比較します。根拠・取得日時・不確実性もあわせて表示します。") }
        item { MunicipalityPicker(codes, { codes = it }, 8, "最大8市町村までまとめて分析できます。") }
        item {
            Panel(title = "✨ AI地域別気象解説") {
                Muted("選択中の${codes.size}市町村について、取得した実データだけを根拠にAIが地域差を分析します。", 11)
                SmallButton(if (st.loading) "分析中…" else if (data != null) "再分析する" else "AIで分析する", { launch { Api.rpc("weather/server-fns#analyzeRegionWeather", jsonOf("codes" to codes)) } }, enabled = !st.loading && codes.isNotEmpty(), icon = Icons.Outlined.AutoAwesome, primary = true)
                st.error?.let { Text("分析できませんでした：$it", fontSize = 11.sp, color = Brand.Destructive) }
                if (data == null && !st.loading) Muted("AIの呼び出しは実行したときだけ行います（自動では動きません）。", 10)
            }
        }
        if (data != null) {
            val r = data.objAt("result")
            item {
                Panel {
                    Text(r.str("summary").orEmpty(), fontSize = 13.sp)
                    Muted("予報取得：${data.str("source").orEmpty()}／平年値：${data.str("normalSource").orEmpty()}／AI分析 ${dateTimeLabel(data.long("fetchedAt"))}／対象日 ${data.str("targetDate").orEmpty()}", 10)
                    BLOCKS.filter { !r.str(it.first).isNullOrBlank() }.forEach { (k, l) ->
                        Column(Modifier.padding(top = 6.dp)) { Text(l, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(r.str(k).orEmpty(), fontSize = 12.sp) }
                    }
                }
            }
            val areas = r.items("areas")
            if (areas.isNotEmpty()) item {
                Panel {
                    areas.forEachIndexed { i, a ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row { Text(a.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Muted("確度 ${a.str("confidence") ?: "—"}", 10) }
                            Text(a.str("outlook").orEmpty(), fontSize = 12.sp)
                            Muted("雪・積雪: ${a.str("snow")?.ifBlank { null } ?: "—"}", 11); Muted("根拠: ${a.str("reason").orEmpty()}", 10)
                        }
                    }
                }
            }
            item {
                Panel(title = "この分析の不確実性") {
                    Text(r.str("uncertainty").orEmpty(), fontSize = 12.sp)
                    Muted("根拠に使ったデータ：${data.items("inputs").count { it.bool("available") == true }}地点の数値（気温・降水確率・降雪・積雪深・標高・平年値）。予報モデルの値であり、実測の観測値ではありません。", 10)
                }
            }
        }
        item { WeatherFootnote() }
    }
}
