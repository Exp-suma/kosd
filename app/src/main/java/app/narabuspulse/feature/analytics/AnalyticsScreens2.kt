package app.narabuspulse.feature.analytics

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.DateRange
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.RangePicker
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.ui.kit.BarChart
import app.narabuspulse.ui.kit.CHART_COLORS
import app.narabuspulse.ui.kit.HBar
import app.narabuspulse.ui.kit.LineChart
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.Series
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

private val PERIOD_ORDER = listOf("朝", "昼", "夕", "夜", "深夜")

@Composable
fun Muted(text: String, size: Int = 11) = Text(text, fontSize = size.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** 横スクロールする表（列幅指定・右寄せ対応）。 */
class Col(val title: String, val width: Dp = 72.dp, val right: Boolean = false)

@Composable
fun DataTable(cols: List<Col>, rows: List<List<String>>, boldFirst: Boolean = true) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row { cols.forEach { c -> Text(c.title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (c.right) TextAlign.End else TextAlign.Start, modifier = Modifier.width(c.width).padding(4.dp)) } }
        rows.forEach { r ->
            RowDivider()
            Row { r.forEachIndexed { i, v -> val c = cols[i]; Text(v, fontSize = 12.sp, fontWeight = if (boldFirst && i == 0) FontWeight.Bold else FontWeight.Normal, textAlign = if (c.right) TextAlign.End else TextAlign.Start, modifier = Modifier.width(c.width).padding(4.dp)) } }
        }
    }
}

@Composable
private fun Metric(label: String, value: Number?, unit: String, tone: Color? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Muted(label, 10)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.let { fmtNum(it) } ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tone ?: MaterialTheme.colorScheme.onSurface)
            Muted(unit, 10)
        }
    }
}

fun fmtNum(v: Number): String = if (v is Double && v != v.toLong().toDouble()) "%.1f".format(v) else num(v.toLong())

/** 運行間隔・運行頻度（Web: analytics.headway.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeadwayScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -13), today)) }
    var route by remember { mutableStateOf("") }
    val q = rememberLoadRpc("analytics/server-fns#getHeadway", range, "route" to route.ifBlank { null })
    val data = q.data
    val all = data.items("rows")
    val rows = all.filter { route.isBlank() || it.str("route").orEmpty().contains(route) }
    ScreenList {
        item { AnalyticsTabs("/analytics/headway") }
        item { Heading("運行間隔・運行頻度", "系統別・時間帯別に、運行記録の観測開始時刻から便の間隔と本数を分析します。") }
        item { RangePicker(range, { range = it }) { SelectField(listOf("" to "系統: すべて") + all.map { it.str("route").orEmpty() }.map { it to it }, route, { route = it }) } }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    data.str("note")?.let { Muted(it) }
                    if (data.bool("hasRouteInfo") == false && rows.isNotEmpty()) ErrorText("運行記録に系統番号が含まれていないため、系統別の内訳は表示できません。", warning = true)
                    Muted("対象期間 ${data.str("range.from")} 〜 ${data.str("range.to")}　系統 ${rows.size} 件　間隔サンプル ${rows.sumOf { it.int("samples") ?: 0 }} 件")
                }
            }
            if (rows.isEmpty()) item { Muted("この期間の運行記録がありません。", 12) }
            items(rows.size) { i ->
                val row = rows[i]
                Panel(title = row.str("route"), action = { Muted("${row.int("days") ?: 0}日 / ${row.int("departures") ?: 0}便 / サンプル ${row.int("samples") ?: 0}  ") }) {
                    Row(Modifier.fillMaxWidth()) {
                        Metric("平均間隔", row.dbl("avgInterval"), "分", modifier = Modifier.weight(1f))
                        Metric("中央値", row.dbl("medianInterval"), "分", modifier = Modifier.weight(1f))
                        Metric("最短", row.dbl("minInterval"), "分", INFO, Modifier.weight(1f))
                        Metric("最長", row.dbl("maxInterval"), "分", Brand.Warning, Modifier.weight(1f))
                    }
                    Muted("時間帯別の便数", 10)
                    val hourly = row.items("hourly")
                    BarChart(hourly.map { "${it.int("hour") ?: 0}" }, listOf(Series("便数", CHART_COLORS[0], hourly.map { it.dbl("count") ?: 0.0 })), height = 90.dp, labelEvery = 3, valueSuffix = "便", tooltipLabel = { "${hourly.getOrNull(it)?.int("hour")}時" })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.items("periods").sortedBy { p -> PERIOD_ORDER.indexOf(p.str("key").orEmpty().take(1)).let { if (it < 0) 99 else it } }
                            .forEach { p -> Tag("${p.str("key")} ${p.int("count") ?: 0}便", Brand.MutedFg) }
                    }
                    if ((row.int("samples") ?: 0) < 5) Text("サンプルが少ないため、間隔の傾向は参考値です。", fontSize = 10.sp, color = Brand.Warning)
                }
            }
        }
    }
}

@Composable
fun rememberLoadRpc(fn: String, range: DateRange, vararg extra: Pair<String, Any?>) =
    app.narabuspulse.ui.kit.rememberLoad(fn, range, *extra.map { it.second }.toTypedArray()) {
        Api.rpc(fn, jsonOf("from" to range.from, "to" to range.to, *extra))
    }

private fun avgUtil(vehicles: List<JsonElement>): Double? {
    val v = vehicles.mapNotNull { it.dbl("utilization") }
    return if (v.isEmpty()) null else (v.sum() / v.size * 10).roundToInt() / 10.0
}

/** 車両配置・稼働・運行効率（Web: analytics.fleet.jsx） */
@Composable
fun FleetAnalyticsScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -29), today)) }
    val q = rememberLoadRpc("analytics/server-fns#getFleet", range)
    val data = q.data
    ScreenList {
        item { AnalyticsTabs("/analytics/fleet") }
        item { Heading("車両配置・稼働・運行効率", "時間帯ごとに実際に運行していた車両数と、車両ごとの稼働状況・記録の完全性を分析します。") }
        item { RangePicker(range, { range = it }) }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null) {
            val vehicles = data.items("vehicles")
            item {
                KpiRow(
                    Triple("最大同時稼働", "${data.int("peakHour.active") ?: 0}", Brand.Success),
                    Triple("ピーク時間帯", data.int("peakHour.hour")?.let { "${it}時" } ?: "—", INFO),
                    Triple("記録のある車両", "${data.int("summary.vehicles") ?: 0}", null),
                    Triple("平均稼働率", avgUtil(vehicles)?.toString() ?: "—", null),
                    units = listOf("台", null, "台", "%"),
                )
                data.str("note")?.let { Muted(it) }
            }
            item {
                Panel(title = "時間帯別の稼働車両数（実績）") {
                    val hourly = data.items("hourly")
                    BarChart(hourly.map { "${it.int("hour") ?: 0}" }, listOf(Series("稼働", CHART_COLORS[0], hourly.map { it.dbl("active") ?: 0.0 })), height = 160.dp, labelEvery = 2, valueSuffix = "台",
                        tooltipLabel = { val h = hourly.getOrNull(it); "${h?.int("hour")}時（延べ${h?.int("runs") ?: 0}件）" })
                    Muted("同日時に運行記録があった車両数の最大値（延べ件数ではありません）。配車指示の自動送信は行いません。", 10)
                }
            }
            item {
                Panel(title = "日別の稼働状況") {
                    val days = data.items("days")
                    if (days.isEmpty()) Muted("記録がありません。", 12)
                    days.take(14).forEachIndexed { i, d ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(d.str("date").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1.4f))
                            Text("${d.int("vehicles") ?: 0}台", fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                            Text("${d.int("runs") ?: 0}件", fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                            Text("${num(d.long("durationMin"))}分", fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
                        }
                    }
                }
            }
            item {
                Panel(title = "車両別の稼働・運行効率") {
                    val byName = vehicles.associateBy { it.str("vehicle") }
                    DataTable(
                        listOf(Col("車両", 96.dp), Col("運行", 52.dp, true), Col("日数", 52.dp, true), Col("稼働時間", 80.dp, true), Col("稼働率", 64.dp, true), Col("完全性", 64.dp, true), Col("位置記録", 72.dp, true)),
                        data.items("efficiency").map { r ->
                            listOf(r.str("vehicle").orEmpty(), "${r.int("runs") ?: 0}", "${r.int("days") ?: 0}", "${num(r.long("durationMin"))}分",
                                "${byName[r.str("vehicle")]?.dbl("utilization")?.let { fmtNum(it) } ?: "—"}%", "${r.dbl("completeness")?.let { fmtNum(it) } ?: "—"}%", "${r.int("positions") ?: 0}")
                        },
                    )
                    Muted("稼働率＝稼働時間÷（初回観測〜最終観測の時間）。完全性＝終了時刻が記録された運行の割合。", 10)
                }
            }
        }
    }
}

private val GAPS = listOf(10, 15, 30)

/** 運行接続・乗り継ぎ分析（Web: analytics.transfers.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransfersScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -30), today)) }
    var maxGap by remember { mutableIntStateOf(15) }
    val q = rememberLoadRpc("analytics/transfers-fns#getTransferAnalysis", range, "maxGap" to maxGap)
    val data = q.data
    ScreenList {
        item { AnalyticsTabs("/analytics/transfers") }
        item { Heading("運行接続・乗り継ぎ分析", "位置履歴に残った観測時刻から、同じ停留所に別の系統が近い時刻に来ている組を集計します。到着時刻・乗降人数は取得できないため、乗り継げたかどうかや利用者数は示しません。") }
        item { RangePicker(range, { range = it }) { GAPS.forEach { g -> FilterChip(maxGap == g, { maxGap = g }, label = { Text("${g}分以内", fontSize = 11.sp) }) } } }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null) {
            item {
                KpiRow(
                    Triple("位置の観測", num(data.long("eventCount")), INFO), Triple("観測のある停留所", "${data.int("stopCount") ?: 0}", null),
                    Triple("複数系統が来る停留所", "${data.int("sharedStops") ?: 0}", Brand.Success), Triple("接続の窓", "${data.int("maxGap") ?: maxGap}", Brand.Warning),
                    units = listOf("件", "か所", "か所", "分以内"),
                )
                Muted((data.str("note") ?: "") + if (data.bool("truncated") == true) " 観測件数が上限に達しているため、一部の期間・停留所のみ集計しています。" else "")
                Muted("集計時刻 ${BusFormat.dateTime(data.long("fetchedAt"))}", 10)
            }
            item {
                Panel(title = "接続の多い停留所") {
                    val stops = data.items("stops")
                    if (stops.isEmpty()) Muted("この期間に接続の記録がありません。運行と位置履歴が蓄積されると集計できます。", 12)
                    stops.forEachIndexed { i, stop ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(stop.str("stop").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Muted("系統 ${stop.list("routes").joinToString("・") { it.text }} / 観測 ${stop.int("events") ?: 0}件 / 車両 ${stop.int("vehicles") ?: 0}台")
                            Muted("観測 ${stop.str("firstObservation").orEmpty()}〜${stop.str("lastObservation").orEmpty()}", 10)
                            val conns = stop.items("connections")
                            if (conns.isEmpty()) Muted("${data.int("maxGap") ?: maxGap}分以内の接続は記録されていません", 10)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                conns.take(6).forEach { p ->
                                    Tag("${p.str("from")} → ${p.str("to")}　${p.int("count") ?: 0}回${p.dbl("medianGap")?.let { "・中央値${fmtNum(it)}分" } ?: ""}", INFO)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Panel(title = "接続が確認できていない停留所") {
                    val weak = data.items("weak")
                    if (weak.isEmpty()) Muted("複数系統が来る停留所はすべて接続の記録があります。", 12)
                    weak.forEach { row ->
                        Text("${row.str("stop")}（系統 ${row.list("routes").joinToString("・") { it.text }}）— この期間は ${data.int("maxGap") ?: maxGap}分以内の接続が記録されていません。接続が取りにくい可能性がありますが、観測頻度が低いだけの場合もあります。",
                            fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        }
    }
}

private val TOP_OPTIONS = listOf(10, 15, 25)

fun periodDays(from: String, to: String): Int = runCatching { (ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)) + 1).toInt().coerceAtLeast(1) }.getOrDefault(1)

/** 車両ごとの値を横棒で並べる（Web: VehicleRankChart.jsx） */
@Composable
fun RankBars(rows: List<Pair<String, Double>>, unit: String, color: Color) {
    val max = rows.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    rows.forEach { (name, v) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(name, fontSize = 11.sp, modifier = Modifier.width(88.dp), maxLines = 1)
            HBar((v / max).toFloat(), Modifier.weight(1f), color = color, height = 12.dp)
            Text(" ${fmtNum(v)}$unit", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        }
    }
}

private val SORTS = listOf("runs" to "運行回数", "utilization" to "稼働率", "durationMin" to "稼働時間", "days" to "稼働日数")

/** 車両別の稼働率・運行回数（Web: analytics.vehicles.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleUtilizationScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -29), today)) }
    var top by remember { mutableIntStateOf(15) }
    var sort by remember { mutableStateOf("runs") }
    val q = rememberLoadRpc("analytics/server-fns#getFleet", range)
    val data = q.data
    val days = periodDays(range.from, range.to)
    val avgRun = data.items("efficiency").associate { it.str("vehicle") to it.dbl("avgRunMin") }
    val rows = data.items("vehicles")
    ScreenList {
        item { AnalyticsTabs("/analytics/vehicles") }
        item { Heading("車両別の稼働率・運行回数", "どの車両がどれだけ動いているかを、運行回数・稼働率・日別の推移から一目で確認できます。") }
        item { RangePicker(range, { range = it }) { SelectField(TOP_OPTIONS.map { it to "上位${it}台" }, top, { top = it }) } }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null && rows.isEmpty()) item { Muted("この期間に運行記録がありません。期間を広げてください。", 12) }
        if (data != null && rows.isNotEmpty()) {
            val runs = data.long("summary.runs") ?: 0
            item {
                KpiRow(
                    Triple("記録のある車両", "${rows.size}", null), Triple("延べ運行回数", num(runs), INFO),
                    Triple("1台あたり運行回数", fmtNum((runs * 10.0 / rows.size).roundToInt() / 10.0), null), Triple("平均稼働率", avgUtil(rows)?.toString() ?: "—", Brand.Success),
                    units = listOf("台", "回", "回", "%"),
                )
            }
            item {
                Panel(title = "車両別 運行回数（上位${top}台）") {
                    Muted("期間中に記録した運行の回数。長いほどよく動いている車両です。", 10)
                    RankBars(rows.sortedByDescending { it.dbl("runs") ?: 0.0 }.take(top).map { it.str("vehicle").orEmpty() to (it.dbl("runs") ?: 0.0) }, "回", CHART_COLORS[0])
                }
            }
            item {
                Panel(title = "車両別 稼働率（上位${top}台）") {
                    Muted("稼働率＝稼働時間÷（初回観測〜最終観測の時間）。観測できた範囲内でどれだけ走っていたかを示します。", 10)
                    val util = rows.filter { it.dbl("utilization") != null }.sortedByDescending { it.dbl("utilization") }.take(top)
                    if (util.isEmpty()) Muted("算出できる記録がありません。", 12)
                    else RankBars(util.map { it.str("vehicle").orEmpty() to (it.dbl("utilization") ?: 0.0) }, "%", CHART_COLORS[1])
                }
            }
            item {
                Panel(title = "日別の稼働台数と運行回数") {
                    Muted("棒＝その日に運行記録があった車両数、線＝その日の延べ運行回数（${days}日間）。", 10)
                    val daily = data.items("days").reversed()
                    val labels = daily.map { it.str("date").orEmpty().drop(5) }
                    val every = (labels.size / 7).coerceAtLeast(1)
                    BarChart(labels, listOf(Series("稼働車両数(台)", CHART_COLORS[0], daily.map { it.dbl("vehicles") ?: 0.0 })), height = 160.dp, labelEvery = every, valueSuffix = "台")
                    LineChart(labels, listOf(Series("運行回数", CHART_COLORS[1], daily.map { it.dbl("runs") ?: 0.0 })), height = 140.dp, labelEvery = every, valueSuffix = "回")
                }
            }
            item {
                Panel(title = "車両別の稼働一覧") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Muted("並び替え"); SORTS.forEach { (k, l) -> FilterChip(sort == k, { sort = k }, label = { Text(l, fontSize = 11.sp) }) }
                    }
                    val maxRuns = rows.maxOfOrNull { it.dbl("runs") ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
                    val sorted = rows.sortedByDescending { it.dbl(sort) ?: -1.0 }
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row { listOf("車両" to 96, "運行回数" to 120, "稼働日数" to 64, "稼働時間" to 64, "1運行あたり" to 80, "稼働率" to 56).forEach { (t, w) -> Text(t, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(w.dp).padding(4.dp)) } }
                        sorted.forEach { r ->
                            RowDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.str("vehicle").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp).padding(4.dp))
                                Row(Modifier.width(120.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    HBar(((r.dbl("runs") ?: 0.0) / maxRuns).toFloat(), Modifier.weight(1f)); Text(" ${r.int("runs") ?: 0}", fontSize = 12.sp)
                                }
                                Text("${r.int("days") ?: 0} / $days", fontSize = 12.sp, modifier = Modifier.width(64.dp).padding(4.dp))
                                Text("%.1fh".format(((r.dbl("durationMin") ?: 0.0) / 6).roundToInt() / 10.0), fontSize = 12.sp, modifier = Modifier.width(64.dp).padding(4.dp))
                                Text("${avgRun[r.str("vehicle")]?.let { fmtNum(it) } ?: "—"}分", fontSize = 12.sp, modifier = Modifier.width(80.dp).padding(4.dp))
                                Text("${r.dbl("utilization")?.let { fmtNum(it) } ?: "—"}%", fontSize = 12.sp, modifier = Modifier.width(56.dp).padding(4.dp))
                            }
                        }
                    }
                }
            }
            if (data.bool("truncated") == true) item { ErrorText("期間内の運行記録が多いため、直近の記録に絞って集計しています。期間を短くすると全件を反映できます。", warning = true) }
            data.str("note")?.let { item { Muted(it, 10) } }
        }
    }
}
