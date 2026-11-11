package app.narabuspulse.feature.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.DateRange
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.RangePicker
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.ui.kit.HBar
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val GROUPS = listOf("day" to "日別", "week" to "週別", "month" to "月別", "year" to "年別")
private val EXPORTS = listOf("runs" to "運行CSV", "headway" to "間隔CSV", "vehicles" to "車両CSV")

/** 長期統計・曜日別・季節別・ランキング（Web: analytics.longterm.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LongTermScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -179), today)) }
    var groupBy by remember { mutableStateOf("month") }
    var busy by remember { mutableStateOf("") }
    val q = rememberLoadRpc("analytics/server-fns#getLongTerm", range, "groupBy" to groupBy)
    val data = q.data

    fun exportCsv(kind: String) {
        busy = kind
        scope.launch {
            try {
                val r = Api.rpc("analytics/server-fns#exportAnalyticsCsv", jsonOf("kind" to kind, "from" to range.from, "to" to range.to))
                val csv = r.str("csv").orEmpty()
                val name = r.str("filename") ?: "$kind.csv"
                val where = Export.text(context, name, "text/csv", if (csv.startsWith("﻿")) csv else "﻿$csv")
                snackbar.showSnackbar("$where に保存しました")
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                snackbar.showSnackbar(e.userMessage().ifBlank { "CSVを出力できませんでした" })
            } finally { busy = "" }
        }
    }

    ScreenList {
        item { AnalyticsTabs("/analytics/longterm") }
        item { Heading("長期統計・曜日別・季節別・ランキング", "日・週・月・年単位の推移、曜日と季節の傾向、車両の長期履歴、系統の変化点を実データから集計します。") }
        item { RangePicker(range, { range = it }) { GROUPS.forEach { (k, l) -> FilterChip(groupBy == k, { groupBy = k }, label = { Text(l, fontSize = 11.sp) }) } } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EXPORTS.forEach { (k, l) -> SmallButton(if (busy == k) "出力中…" else l, { exportCsv(k) }, enabled = busy != k, icon = Icons.Outlined.Download) }
            }
        }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null) {
            item { Muted("${data.str("note").orEmpty()}（集計 ${BusFormat.dateTime(data.long("fetchedAt"))}）") }
            item {
                val series = data.items("series")
                val max = series.maxOfOrNull { it.dbl("runs") ?: 0.0 }?.coerceAtLeast(1.0) ?: 1.0
                Panel(title = "推移（${GROUPS.firstOrNull { it.first == data.str("groupBy") }?.second ?: ""}）") {
                    if (series.isEmpty()) Muted("記録がありません。", 12)
                    series.forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(row.str("key").orEmpty(), fontSize = 11.sp, modifier = Modifier.width(84.dp))
                            HBar(((row.dbl("runs") ?: 0.0) / max).toFloat(), Modifier.weight(1f))
                            Text("${row.int("runs") ?: 0}件", fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                            Text("${row.int("vehicles") ?: 0}台", fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                        }
                    }
                }
            }
            item {
                val wd = data.objAt("weekday")
                Panel(title = "曜日別（祝日を考慮）") {
                    DataTable(listOf(Col("曜日", 72.dp), Col("運行", 72.dp, true), Col("車両", 64.dp, true), Col("", 96.dp)),
                        wd.items("rows").map { r -> val h = r.list("holidays").size; listOf(r.str("label").orEmpty(), "${r.int("runs") ?: 0}件", "${r.int("vehicles") ?: 0}台", if (h > 0) "祝日 ${h}日" else "") })
                    Muted("平日 ${wd.int("split.weekday") ?: 0}件 / 土曜 ${wd.int("split.saturday") ?: 0}件 / 日祝 ${wd.int("split.sunday_holiday") ?: 0}件", 10)
                }
            }
            item {
                Panel(title = "季節別") {
                    DataTable(listOf(Col("季節", 72.dp), Col("運行", 72.dp, true), Col("日数", 64.dp, true), Col("1日あたり", 80.dp, true)),
                        data.items("season").map { r -> listOf(r.str("label").orEmpty(), "${r.int("runs") ?: 0}件", "${r.int("days") ?: 0}日", r.dbl("runsPerDay")?.let { fmtNum(it) } ?: "—") })
                }
            }
            item {
                Panel(title = "ランキング") {
                    data.items("rankings").forEachIndexed { ri, rank ->
                        if (ri > 0) RowDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(rank.str("title").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            val its = rank.items("items")
                            if (its.isEmpty()) Muted("データ不足のため算出できません。", 11)
                            its.forEachIndexed { i, it ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (i < 3) Brand.Orange else Brand.MutedFg, modifier = Modifier.width(22.dp))
                                    Text(it.str("name").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    Text("${it.str("value") ?: it.dbl("value")?.let { v -> fmtNum(v) } ?: ""}${it.str("unit").orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Muted(its.firstOrNull()?.str("condition") ?: rank.str("unit").orEmpty(), 10)
                        }
                    }
                }
            }
            item {
                Panel(title = "車両の長期稼働履歴") {
                    DataTable(listOf(Col("車両", 96.dp), Col("初回", 90.dp), Col("最終", 90.dp), Col("観測日数", 64.dp, true), Col("運行", 52.dp, true), Col("系統", 160.dp)),
                        data.items("vehicleHistories").map { r -> listOf(r.str("vehicle").orEmpty(), r.str("firstDate").orEmpty(), r.str("lastDate").orEmpty(), "${r.int("observedDays") ?: 0}", "${r.int("runs") ?: 0}", r.list("routes").joinToString(" / ") { it.text }.ifBlank { "—" }) })
                    Muted("観測できなかった日は「運休・廃車」を意味しません。記録が無いだけです。", 10)
                }
            }
            item {
                Panel(title = "系統別の長期変化") {
                    val trends = data.items("routeTrends")
                    if (trends.isEmpty()) Muted("系統情報のある記録がありません。", 12)
                    trends.forEachIndexed { i, row ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Tag(row.str("route").orEmpty(), INFO)
                                Text("  ${row.int("runs") ?: 0}件 / ${row.int("days") ?: 0}日", fontSize = 11.sp)
                            }
                            Muted("車両 ${row.items("vehicles").joinToString("・") { it.str("vehicle").orEmpty() }.ifBlank { "—" }}", 10)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.items("months").forEach { m -> Tag("${m.str("month")} ${m.int("runs") ?: 0}", Brand.MutedFg) }
                            }
                            val ch = row.items("changes")
                            if (ch.isNotEmpty()) Text("変化点: " + ch.joinToString("、") { c -> val v = c.dbl("change") ?: 0.0; "${c.str("month")}（${c.int("from")}→${c.int("to")}件 / ${if (v > 0) "+" else ""}${fmtNum(v)}%）" },
                                fontSize = 10.sp, color = Brand.Warning)
                        }
                    }
                }
            }
        }
    }
}
