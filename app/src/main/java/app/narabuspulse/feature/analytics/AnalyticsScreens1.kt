package app.narabuspulse.feature.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.DateRange
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.RangePicker
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.HBar
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import java.text.NumberFormat
import java.util.Locale

val INFO = Color(0xFF2F6FB0)

private val TABS = listOf(
    "/analytics" to "概要", "/analytics/continuity" to "運用の連続性", "/analytics/headway" to "運行間隔",
    "/analytics/fleet" to "配置・稼働・効率", "/analytics/vehicles" to "車両別グラフ", "/analytics/longterm" to "長期統計・ランキング",
)

const val ANALYTICS_NOTE = "車両の運行記録と位置履歴（実測の観測値）を集計しています。到着時刻・乗降人数・営業所は取得できないため、推定で補完しません。"

/** 運行分析のタブ（Web: analytics.jsx） */
@Composable
fun AnalyticsTabs(current: String) {
    val nav = LocalNav.current
    ChipRow(TABS, current, { if (it != current) nav.go(it) })
}

@Composable
fun Busy() = Text("集計しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun ErrorText(message: String, warning: Boolean = false) = Note(message, tone = if (warning) Brand.Warning else Brand.Destructive)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KpiRow(vararg items: Triple<String, String, Color?>, units: List<String?> = emptyList()) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        items.forEachIndexed { i, (label, value, tone) -> StatTile(label, value, Modifier.weight(1f), units.getOrNull(i), tone) }
    }
}

fun num(v: Number?): String = NumberFormat.getInstance(Locale.JAPAN).format(v ?: 0)

/** 運行分析の概要（Web: analytics.index.jsx） */
@Composable
fun AnalyticsOverviewScreen() {
    val nav = LocalNav.current
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -29), today)) }
    val q = rememberLoad("an-overview", range) { Api.rpc("analytics/server-fns#getAnalyticsOverview", jsonOf("from" to range.from, "to" to range.to)) }
    val data = q.data
    ScreenList {
        item { AnalyticsTabs("/analytics") }
        item { Heading("運行分析", "蓄積された運行記録・位置履歴から、運用のつながり・運行間隔・車両配置・長期傾向を分析します。") }
        item { RangePicker(range, { range = it }) }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null) {
            item {
                KpiRow(
                    Triple("運行記録", num(data.long("summary.runs")), Brand.Success), Triple("記録のある車両", num(data.long("summary.vehicles")), INFO),
                    Triple("系統", num(data.long("summary.routes")), null), Triple("観測日数", num(data.long("summary.days")), null),
                    units = listOf("件", "台", "系統", "日"),
                )
            }
            item {
                Panel(title = "集計条件とデータ量") {
                    KeyValue("対象期間", "${data.str("range.from")} 〜 ${data.str("range.to")}")
                    KeyValue("運行時間（合計）", "${num(data.long("summary.durationMin"))} 分")
                    KeyValue("終了時刻あり / 推定", "${data.int("summary.measuredRuns") ?: 0} 件 / ${data.int("summary.runsWithoutEnd") ?: 0} 件")
                    KeyValue("位置履歴の取得件数", "${data.int("positionSamples") ?: 0} 件${if (data.bool("positionTruncated") == true) "（上限到達）" else ""}")
                    KeyValue("集計時刻", BusFormat.dateTime(data.long("fetchedAt")))
                    data.str("note")?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item {
                val top = data.items("topRoutes")
                Panel(title = "記録の多い系統") {
                    if (top.isEmpty()) Text("この期間に運行記録がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val max = top.firstOrNull().dbl("count") ?: 1.0
                    top.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(r.str("route").orEmpty(), fontSize = 12.sp, modifier = Modifier.width(64.dp))
                            HBar(((r.dbl("count") ?: 0.0) / max).toFloat(), Modifier.weight(1f))
                            Text(" ${r.int("count")}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if ((data.int("summary.runs") ?: 0) < 10) item { Note("この期間の運行記録が少ないため、各分析は参考値として表示されます。データ収集を継続すると精度が上がります。", tone = Brand.Warning) }
            item {
                Panel(title = "記録のある日（直近）") {
                    val days = data.list("recordedDays").map { it.text }
                    if (days.isEmpty()) Text("記録がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { days.forEach { Tag(it, Brand.MutedFg) } }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("/analytics/continuity", "運用の連続性", "1台の運行のつながり・待機時間"),
                        Triple("/analytics/headway", "運行間隔", "系統別・時間帯別の間隔と偏り"),
                        Triple("/analytics/fleet", "配置・稼働・効率", "時間帯別必要車両数と稼働率"),
                        Triple("/analytics/transfers", "運行接続・乗り継ぎ", "同一停留所で接続する系統と時間差"),
                        Triple("/analytics/longterm", "長期統計・ランキング", "曜日/季節別・車両履歴・変化点"),
                    ).forEach { (to, label, desc) ->
                        Panel(modifier = Modifier.clickable { nav.go(to) }) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { Text(ANALYTICS_NOTE, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** 車両運用の連続性（Web: analytics.continuity.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContinuityScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -13), today)) }
    var vehicle by remember { mutableStateOf("") }
    val q = rememberLoad("an-cont", range, vehicle) { Api.rpc("analytics/server-fns#getContinuity", jsonOf("from" to range.from, "to" to range.to, "vehicle" to vehicle.ifBlank { null })) }
    val data = q.data
    val rows = data.items("rows")
    ScreenList {
        item { AnalyticsTabs("/analytics/continuity") }
        item { Heading("車両運用の連続性", "1台の車両の運行を時系列に並べ、運行間の待機時間・系統変更・接続関係を確認します。") }
        item {
            RangePicker(range, { range = it }) {
                SelectField(listOf("" to "車両: すべて") + data.list("vehicles").map { it.text to it.text }, vehicle, { vehicle = it })
            }
        }
        if (q.loading) item { Busy() }
        q.error?.let { e -> item { ErrorText(e) } }
        if (data != null) {
            item {
                KpiRow(
                    Triple("運行", num(data.long("total")), Brand.Success), Triple("車両", data.list("vehicles").size.toString(), INFO),
                    Triple("系統変更", rows.count { it.bool("routeChanged") == true }.toString(), Brand.Warning), Triple("推定を含む", rows.count { it.bool("durationEstimated") == true }.toString(), null),
                    units = listOf("件", "台", "回", "件"),
                )
                data.str("note")?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
            item {
                Panel(title = "運用のつながり", action = { Text("表示 ${rows.size} / ${data.int("total") ?: 0} 件  ", fontSize = 11.sp) }) {
                    if (rows.isEmpty()) Text("この期間の運行記録がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rows.forEachIndexed { i, row ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Text(row.str("vehicle").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(row.str("date").orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${BusFormat.time(row.long("start"))} → ${BusFormat.time(row.long("end"))}", fontSize = 12.sp)
                                Text("${row.int("durationMin") ?: 0}分", fontSize = 11.sp)
                                if (row.bool("durationEstimated") == true) Tag("推定", Brand.Warning) else Tag("実測", Brand.Success)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                row.str("route")?.takeIf { it.isNotBlank() }?.let { Tag(it, INFO) }
                                row.str("destination")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp) }
                                Text("${row.str("fromStop")?.ifBlank { null } ?: "始発不明"} → ${row.str("toStop")?.ifBlank { null } ?: "終着不明"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val gap = row.int("gapMin")
                            if (gap != null) Text("次の運行まで ${gap}分" + if (row.bool("routeChanged") == true && !row.str("nextRoute").isNullOrBlank()) "（${row.str("route")} → ${row.str("nextRoute")}）" else "",
                                fontSize = 11.sp, fontWeight = if (gap <= 10) FontWeight.Bold else FontWeight.Normal, color = if (gap <= 10) INFO else MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text("同日の後続運行は記録なし", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (data.bool("truncated") == true) item { ErrorText("記録が多いため上限まで集計しています。期間を狭めると全件を確認できます。", warning = true) }
        }
    }
}
