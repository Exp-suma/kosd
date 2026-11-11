package app.narabuspulse.feature.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.PdfTable
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
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.bus.BusSnapshot
import app.narabuspulse.feature.bus.OpLog
import app.narabuspulse.feature.bus.logPrimaryKind
import app.narabuspulse.ui.kit.BarChart
import app.narabuspulse.ui.kit.CHART_COLORS
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.HBar
import app.narabuspulse.ui.kit.LineChart
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.Series
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

fun List<OpLog>.toJson() = JsonArray(map { jsonOf("row" to it.row, "at" to it.at, "vehicle" to it.vehicle, "kind" to it.kind, "from" to it.from, "to" to it.to) })

private val RANGES = listOf(30 to "直近30日", 90 to "直近90日", 0 to "取得済み全期間")

/** 時間帯別分析（Web: analysis.jsx + AnalysisCharts.jsx） */
@Composable
fun AnalysisScreen() { BusGate { data -> AnalysisBody(data) } }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisBody(data: BusSnapshot) {
    var range by remember { mutableIntStateOf(30) }
    val logs = remember(data.logs, range) {
        if (range == 0) data.logs else { val from = BusFormat.dayKey(System.currentTimeMillis() - range * 86_400_000L); data.logs.filter { it.at != null && BusFormat.dayKey(it.at) >= from } }
    }
    val logsJson = remember(logs) { logs.toJson() }
    val calc by produceState<Map<String, JsonElement>>(emptyMap(), logsJson) {
        value = mapOf(
            "hourly" to Logic.call("bus/analysis", "logsByHour", logsJson),
            "weekday" to Logic.call("bus/analysis", "logsByWeekday", logsJson),
            "monthly" to Logic.call("bus/analysis", "logsByMonth", logsJson),
            "vehicle" to Logic.call("bus/analysis", "logsByVehicle", logsJson),
            "split" to Logic.call("bus/analysis", "weekdaySplit", logsJson),
        )
    }
    val starts = logs.count { logPrimaryKind(it.kind) == "運行開始" }
    val changes = logs.count { val k = logPrimaryKind(it.kind); k != "運行開始" && k != "運行終了" }
    val perVehicle = calc["vehicle"].items()

    ScreenList {
        item { Heading("時間帯別分析", "ログに記録された運行変更イベントを集計します。イベント件数は運行便数や稼働車両数とは異なります。") }
        item {
            ChipRow(RANGES, range, { range = it })
            Text("対象レコード ${logs.size} 件", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                StatTile("運行開始", starts.toString(), Modifier.weight(1f), "件", Brand.Success)
                StatTile("運用変更", changes.toString(), Modifier.weight(1f), "件", CHART_COLORS[1])
                StatTile("平日 / 土日", "${calc["split"].int("weekday") ?: 0} / ${calc["split"].int("weekend") ?: 0}", Modifier.weight(1f), "件")
                StatTile("記録のある車両", perVehicle.size.toString(), Modifier.weight(1f), "台")
            }
        }
        item {
            val h = calc["hourly"].items()
            Panel(title = "時間帯別の運行変更") {
                BarChart(
                    h.map { "${it.int("hour")}時" },
                    listOf(
                        Series("運行開始", CHART_COLORS[0], h.map { it.dbl("start") ?: 0.0 }),
                        Series("運用変更", CHART_COLORS[1], h.map { it.dbl("change") ?: 0.0 }),
                        Series("運行終了", CHART_COLORS[2], h.map { it.dbl("end") ?: 0.0 }),
                    ),
                    stacked = true, labelEvery = 3, tooltipLabel = { "${h.getOrNull(it).int("hour")}時台" },
                )
            }
        }
        item {
            val w = calc["weekday"].items()
            Panel(title = "曜日別の変更件数") { BarChart(w.map { it.str("label").orEmpty() }, listOf(Series("変更件数", CHART_COLORS[0], w.map { it.dbl("total") ?: 0.0 })), height = 180.dp) }
        }
        item {
            val m = calc["monthly"].items()
            Panel(title = "月別の推移") {
                if (m.isEmpty()) Text("データがありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LineChart(m.map { it.str("label").orEmpty() }, listOf(Series("変更件数", CHART_COLORS[0], m.map { it.dbl("total") ?: 0.0 }), Series("運行開始", CHART_COLORS[1], m.map { it.dbl("start") ?: 0.0 })), height = 180.dp)
            }
        }
        item {
            Panel(title = "車両別の変更件数（上位10台）") {
                if (perVehicle.isEmpty()) Text("データがありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val max = perVehicle.firstOrNull().dbl("total") ?: 1.0
                perVehicle.take(10).forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(row.str("vehicle").orEmpty(), fontSize = 11.sp, modifier = Modifier.width(96.dp))
                        Column(Modifier.weight(1f)) {
                            HBar(((row.dbl("total") ?: 0.0) / max).toFloat())
                            Text("開始${row.int("start") ?: 0} / 変更${row.int("change") ?: 0}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(" ${row.int("total") ?: 0}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 運用表（Web: operations.jsx + GeneratedPlanPanel.jsx + PlanSheetExport.jsx） */
@Composable
fun OperationsScreen() {
    ScreenList {
        item { Heading("運用表", "日次運用表・月間運用表をそのまま表示します。選択したシートだけを読み込みます。") }
        item { GeneratedPlanPanel() }
        item { Column { BusGate { data -> OperationsBody(data) } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OperationsBody(data: BusSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val daily = data.dailyTabs()
    val tabs = daily.reversed() + data.raw.objAt("tabs").list("other").map { it.text }
    var sheetName by remember { mutableStateOf(tabs.firstOrNull().orEmpty()) }
    var note by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    val today = BusFormat.dayKey(System.currentTimeMillis())
    val dailyByDate by produceState<Map<String, String>>(emptyMap(), daily) {
        val m = LinkedHashMap<String, String>()
        daily.forEach { title ->
            Regex("^(\\d{1,2})月(\\d{1,2})日運用表$").find(title)?.let { r ->
                m[Logic.call("collect/serviceDay", "resolveSheetDate", r.groupValues[1].toInt(), r.groupValues[2].toInt(), today).text] = title
            }
        }
        value = m
    }
    val currentDate = dailyByDate.entries.firstOrNull { it.value == sheetName }?.key.orEmpty()
    val rows by produceState<List<List<String>>?>(null, sheetName) {
        value = null
        value = if (sheetName.isBlank()) emptyList() else runCatching {
            Api.rpc("server-fns#getSheetRows", jsonOf("sheetName" to sheetName, "range" to "A1:G", "maxRows" to 500)).items("rows").map { r -> r.list("cells").map { it.text } }
        }.getOrDefault(emptyList())
    }
    if (tabs.isEmpty()) { Text("運用表のシートが見つかりませんでした。", fontSize = 12.sp); return }
    val filled = rows.orEmpty().filter { r -> r.any { it.isNotBlank() } }
    val header = filled.firstOrNull { r -> r.any { it.contains("車両") || it.contains("時刻") } }
    val body = filled.filter { it !== header }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SelectField(tabs.map { it to it }, sheetName, { sheetName = it; note = "" }, label = "シート")
                DateField(currentDate, { v ->
                    val title = dailyByDate[v]
                    if (title != null) { sheetName = title; note = "" } else note = "${v.replace("-", "/")} の運用表はありません。シートを直接選んでください。"
                }, label = "日付")
                SmallButton(if (exporting) "PDFを作成中…" else "PDFで書き出す", {
                    exporting = true
                    scope.launch {
                        try {
                            val where = PdfTable.save(context, "${sheetName.ifBlank { "運用表" }}.pdf", sheetName.ifBlank { "運用表" }, "印刷日時 ${BusFormat.dateTime(System.currentTimeMillis())}", header.orEmpty(), body)
                            snackbar.showSnackbar("$where に保存しました")
                        } catch (e: Throwable) { snackbar.showSnackbar(e.userMessage().ifBlank { "PDFを作成できませんでした" }) } finally { exporting = false }
                    }
                }, enabled = !exporting && body.isNotEmpty(), icon = Icons.Outlined.PictureAsPdf)
                Text("${body.size} 行", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = Brand.Warning)
        }
        Panel {
            when {
                rows == null -> Text("読み込み中…", fontSize = 12.sp)
                body.isEmpty() -> Text("このシートにデータがありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> SheetTable(header, body)
            }
        }
    }
}

private const val MAX_VEHICLES = 120

/** 自動生成の運用表（Web: components/bus/GeneratedPlanPanel.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneratedPlanPanel() {
    val dates by produceState<List<JsonElement>?>(null) { value = runCatching { Api.rpc("collect/generated-fns#getGeneratedPlanDates").items("dates") }.getOrDefault(emptyList()) }
    var date by remember { mutableStateOf("") }
    LaunchedEffect(dates) { if (date.isBlank()) date = dates?.firstOrNull().str("date").orEmpty() }
    val vehicles by produceState<List<JsonElement>?>(null, date) {
        value = null
        value = if (date.isBlank()) emptyList() else runCatching { Api.rpc("collect/generated-fns#getGeneratedPlan", jsonOf("date" to date)).items("vehicles") }.getOrDefault(emptyList())
    }
    val list = dates ?: return
    if (list.isEmpty()) {
        Panel { Text("自動生成の運用表はまだありません。深夜1時以降の収集で、前日ぶんの運用を全車両分まとめて生成します。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val vs = vehicles.orEmpty()
    Panel(title = "自動生成の運用表") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SelectField(list.map { it.str("date").orEmpty() to "${it.str("date")}（${it.int("vehicles")}台）" }, date, { date = it })
            Text("  ${vs.size}台 / ${vs.sumOf { it.int("runCount") ?: 0 }}運用${if (vehicles == null) "　読み込み中…" else ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vs.take(MAX_VEHICLES).forEachIndexed { i, v ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DirectionsBus, null, tint = Brand.Orange, modifier = Modifier.width(18.dp))
                    Text(" ${v.str("vehicle")}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("  ${BusFormat.time(v.long("firstAt"))}〜${BusFormat.time(v.long("lastAt"))}・${v.int("runCount")}運用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    v.list("rows").forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.str("time").orEmpty(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            row.str("route")?.takeIf { it.isNotBlank() }?.let { Tag(it, CHART_COLORS[1]) }
                            Text(" " + (listOfNotNull(row.str("from"), row.str("to")).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { row.str("destination") ?: row.str("stop") ?: "—" }), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (vs.size > MAX_VEHICLES) Text("残り ${vs.size - MAX_VEHICLES}台は省略しています。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
