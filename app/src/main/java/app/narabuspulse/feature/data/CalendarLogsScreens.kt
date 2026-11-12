package app.narabuspulse.feature.data

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Logic
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.objAt
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.bus.BusSnapshot
import app.narabuspulse.feature.bus.LOG_KINDS
import app.narabuspulse.feature.bus.OpLog
import app.narabuspulse.feature.bus.logKindColor
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray


@Composable
fun Heading(title: String, description: String) {
    Column {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun BusSnapshot.logWindowDays(): Int = raw.int("logWindow.days") ?: 7
fun BusSnapshot.dailyTabs(): List<String> = raw.objAt("tabs").list("daily").map { it.text }

/** 車両別稼働カレンダー（Web: calendar.jsx + CalendarGrid / VehicleDayDetail） */
@Composable
fun CalendarScreen() {
    BusGate { data -> CalendarBody(data) }
}

private data class DayCell(val date: String, val day: Int, val status: String, val logCount: Int, val isToday: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarBody(data: BusSnapshot) {
    val now = System.currentTimeMillis()
    val todayKey = BusFormat.dayKey(now)
    var vehicle by remember { mutableStateOf(data.vehicles.firstOrNull()?.name.orEmpty()) }
    var month by remember { mutableStateOf(todayKey.take(7)) }
    var selected by remember { mutableStateOf(todayKey) }
    val logsByDay = remember(data.logs, vehicle) {
        data.logs.filter { it.at != null && it.vehicle == vehicle }.groupBy { BusFormat.dayKey(it.at) }.mapValues { (_, l) -> l.sortedBy { it.at } }
    }
    val cutoffKey = BusFormat.dayKey(now - data.logWindowDays() * 86_400_000L)
    val ym = YearMonth.parse(month)
    val days = (1..ym.lengthOfMonth()).map { d ->
        val date = "%s-%02d".format(month, d)
        val logs = logsByDay[date].orEmpty()
        val status = when {
            date > todayKey || date < cutoffKey -> "unknown"
            logs.any { it.kind.contains("運行開始") } -> "operated"
            logs.isNotEmpty() -> "changed"
            else -> "none"
        }
        DayCell(date, d, status, logs.size, date == todayKey)
    }
    val sel = LocalDate.parse(selected)
    val dailyTab = "${sel.monthValue}月${sel.dayOfMonth}日運用表"
    val hasDailyTab = dailyTab in data.dailyTabs()

    ScreenList {
        item { Heading("車両別稼働カレンダー", "ログシートと日次運用表から、日ごとの記録状況を表示します。「記録なし」は運休ではありません。") }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("車両  ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    SelectField(data.vehicles.map { it.name to it.name }, vehicle, { vehicle = it })
                }
                Text("ログ取得期間：直近${data.logWindowDays()}日分", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { CalendarGrid(month, { m -> month = m; selected = "$m-01" }, days, selected) { selected = it } }
        item { VehicleDayDetail(selected, vehicle, logsByDay[selected].orEmpty(), if (hasDailyTab) dailyTab else null) }
        item {
            Text("判定：運行開始の記録がある日は「運行開始の記録あり」、運用変更のみの日は「運用変更のみ」、ログが無い日は「記録なし」です。ログは変更時にのみ記録されるため、記録なし＝運休ではありません。取得期間外の日付と未来の日付は「判定不能」として表示します。日次運用表の読み込みは、日付を選択したときだけ1回行います。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val WEEK = listOf("月", "火", "水", "木", "金", "土", "日")

private fun statusColors(s: String) = when (s) {
    "operated" -> Brand.Success
    "changed" -> INFO
    "none" -> Brand.MutedFg
    else -> Color(0xFFB0B7BE)
}

fun shiftMonth(month: String, delta: Int): String = YearMonth.parse(month).plusMonths(delta.toLong()).toString()

/** 月のカレンダー（Web: components/bus/CalendarGrid.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarGrid(month: String, onMonthChange: (String) -> Unit, days: List<DayCell>, selected: String, onSelect: (String) -> Unit) {
    val ym = YearMonth.parse(month)
    val offset = (ym.atDay(1).dayOfWeek.value + 6) % 7
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(shiftMonth(month, -1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前の月") }
            Text("${ym.year}年${ym.monthValue}月", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onMonthChange(shiftMonth(month, 1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "次の月") }
        }
        Row { WEEK.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        val cells: List<DayCell?> = List(offset) { null } + days
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { d ->
                    if (d == null) Spacer(Modifier.weight(1f)) else {
                        val c = statusColors(d.status)
                        val isSel = d.date == selected
                        Column(
                            Modifier.weight(1f).heightIn(min = 46.dp)
                                .background(if (d.status == "none" || d.status == "unknown") MaterialTheme.colorScheme.surface else c.copy(alpha = 0.1f), RoundedCornerShape(5.dp))
                                .border(if (isSel) 2.dp else 1.dp, if (isSel) Brand.Orange else c.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
                                .clickable { onSelect(d.date) }.padding(3.dp),
                        ) {
                            Text("${d.day}", fontSize = 11.sp, fontWeight = if (d.isToday) FontWeight.Bold else FontWeight.Normal, color = if (d.isToday) Brand.Orange else MaterialTheme.colorScheme.onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (d.status != "unknown") Box(Modifier.size(6.dp).background(c, CircleShape))
                                if (d.logCount > 0) Text(" ${d.logCount}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf("operated" to "運行開始の記録あり", "changed" to "運用変更のみ", "none" to "記録なし（運休ではありません）", "unknown" to "判定不能").forEach { (k, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(statusColors(k), CircleShape)); Text(" $l", fontSize = 10.sp) }
            }
        }
    }
}

/** 選んだ日の記録（Web: components/bus/VehicleDayDetail.jsx） */
@Composable
private fun VehicleDayDetail(date: String, vehicle: String, logs: List<OpLog>, dailyTab: String?) {
    val rows by produceState<List<List<String>>?>(null, dailyTab, vehicle) {
        value = if (dailyTab == null || vehicle.isBlank()) null else runCatching {
            Api.rpc("server-fns#getSheetRows", jsonOf("sheetName" to dailyTab, "vehicle" to vehicle, "range" to "A1:G", "maxRows" to 400))
                .items("rows").map { r -> r.list("cells").map { it.text } }
        }.getOrDefault(emptyList())
    }
    val d = LocalDate.parse(date)
    val headerRow = rows?.firstOrNull { r -> r.any { it.contains("車両") } }
    val bodyRows = rows.orEmpty().filter { it !== headerRow && it.any { c -> c.isNotBlank() } }
    Panel(title = "${d.year}年${d.monthValue}月${d.dayOfMonth}日 $vehicle の記録", action = { Text("${logs.size} 件の変更  ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
        if (logs.isEmpty()) Text("この日の運用変更ログはありません。ログは変更があったときのみ記録されるため、記録がないことは運休を意味しません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        logs.forEachIndexed { i, log ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(BusFormat.time(log.at), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                    Tag(log.kinds.joinToString("・"), logKindColor(log.kind))
                }
                Text("変更前 ${log.from.ifBlank { "—" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("変更後 ${log.to.ifBlank { "—" }}", fontSize = 11.sp)
            }
        }
        Text("日次運用表　${dailyTab ?: "シートなし"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        when {
            dailyTab == null -> Text("この日の日次運用表シートは見つかりませんでした。判定不能として扱います。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows == null -> Text("読み込み中…", fontSize = 12.sp)
            bodyRows.isEmpty() -> Text("この日の運用表に $vehicle の記録はありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> SheetTable(headerRow, bodyRows)
        }
    }
}

/** シートの表（横スクロール）。 */
@Composable
fun SheetTable(header: List<String>?, rows: List<List<String>>) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        if (header != null) Row { header.forEach { Text(it, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(110.dp).padding(4.dp)) } }
        rows.forEach { r -> Row { r.forEach { Text(it, fontSize = 11.sp, modifier = Modifier.width(110.dp).padding(4.dp)) } } }
    }
}

/** 運行変更履歴（Web: logs.jsx） */
@Composable
fun LogsScreen() {
    BusGate { data -> LogsBody(data) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsBody(data: BusSnapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val keys = listOf("vehicle", "from", "to", "kind", "before", "after", "route", "destination")
    var filters by remember { mutableStateOf(keys.associateWith { if (it == "kind") "all" else "" }) }
    var limit by remember { mutableIntStateOf(200) }
    val logsJson = remember(data.logs) { JsonArray(data.logs.map { jsonOf("row" to it.row, "at" to it.at, "vehicle" to it.vehicle, "kind" to it.kind, "from" to it.from, "to" to it.to) }) }
    val results by produceState<List<OpLog>>(emptyList(), logsJson, filters) {
        value = (Logic.call("bus/analysis", "filterLogs", logsJson, filters) as? JsonArray)?.map { OpLog.from(it) }.orEmpty()
    }
    val shown = results.take(limit)
    fun set(k: String, v: String) { limit = 200; filters = filters + (k to v) }
    val vehicleNames = data.vehicles.map { it.name }.distinct()

    ScreenList {
        item { Heading("運行変更履歴", "ログシートの記録を一括で読み込み、端末内で絞り込んでいます。条件を変えても追加のAPIアクセスは発生しません。") }
        item {
            Panel {
                OutlinedTextField(filters["vehicle"].orEmpty(), { set("vehicle", it) }, label = { Text("車両名・車番", fontSize = 12.sp) }, placeholder = { Text("例：奈良200") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val v = filters["vehicle"].orEmpty()
                if (v.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    vehicleNames.filter { it.contains(v) && it != v }.take(6).forEach { n -> Text(n, fontSize = 11.sp, color = INFO, modifier = Modifier.clickable { set("vehicle", n) }.padding(4.dp)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SelectField(listOf("all" to "すべて") + LOG_KINDS.map { it to it }, filters["kind"] ?: "all", { set("kind", it) }, label = "変更種別")
                    DateField(filters["from"].orEmpty(), { set("from", it) }, label = "開始日")
                    DateField(filters["to"].orEmpty(), { set("to", it) }, label = "終了日")
                }
                listOf("before" to "変更前の運用", "after" to "変更後の運用", "route" to "系統番号", "destination" to "行先").forEach { (k, l) ->
                    OutlinedTextField(filters[k].orEmpty(), { set(k, it) }, label = { Text(l, fontSize = 12.sp) }, placeholder = { Text(when (k) { "route" -> "例：11"; "destination" -> "例：奈良駅"; else -> "部分一致" }) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${results.size} 件", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("（読み込み済み ${data.logs.size} 件 / 直近${data.logWindowDays()}日）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SmallButton("条件をクリア", { limit = 200; filters = keys.associateWith { if (it == "kind") "all" else "" } }, icon = Icons.Outlined.RestartAlt)
                }
                SmallButton("CSV出力", {
                    scope.launch {
                        try {
                            val where = Export.csv(
                                context, "運行変更履歴_${filters["from"]?.ifBlank { null } ?: "全期間"}_${filters["to"]?.ifBlank { null } ?: "最新"}.csv",
                                listOf("変更日時", "車両", "変更種別", "変更前", "変更後"),
                                results.map { l -> listOf(BusFormat.dateTime(l.at), l.vehicle, l.kinds.joinToString("・"), l.from, l.to) },
                            )
                            snackbar.showSnackbar("$where に保存しました")
                        } catch (e: Throwable) { snackbar.showSnackbar(e.userMessage()) }
                    }
                }, enabled = results.isNotEmpty(), icon = Icons.Outlined.Download, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (results.isEmpty()) item { Text("条件に一致する履歴がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item {
            Panel {
                shown.forEachIndexed { i, log ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(BusFormat.dateTime(log.at), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("  ${log.vehicle.ifBlank { "—" }}  ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Tag(log.kinds.joinToString("・"), logKindColor(log.kind))
                        }
                        Text("${log.from.ifBlank { "—" }} → ${log.to.ifBlank { "—" }}", fontSize = 11.sp)
                    }
                }
            }
        }
        if (results.size > shown.size) item { SmallButton("さらに表示（残り ${results.size - shown.size} 件）", { limit += 300 }) }
    }
}
