package app.narabuspulse.feature.research

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

private val TABS = listOf("/research" to "横断検索", "/research/vehicles" to "車両別運用履歴", "/research/routes" to "系統別充当車両", "/research/diff" to "運用表の差分")

@Composable
fun ResearchNav(current: String) {
    val nav = LocalNav.current
    ChipRow(TABS, current, { if (it != current) nav.go(it) })
}

/** 1回押すと実行する検索の状態（Web: setState({loading,error,result})）。 */
class RunState {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<JsonElement?>(null)
}

@Composable
fun rememberRunner(fallback: String): Pair<RunState, (suspend () -> JsonElement) -> Unit> {
    val scope = rememberCoroutineScope()
    val st = remember { RunState() }
    return st to { block ->
        st.loading = true; st.error = null; st.result = null
        scope.launch {
            try { st.result = block() } catch (e: Throwable) { if (e is CancellationException) throw e; st.error = e.userMessage().ifBlank { fallback } } finally { st.loading = false }
        }
    }
}

@Composable
fun RetryError(message: String, retry: () -> Unit) {
    Note(message, tone = Brand.Destructive)
    TextButton(onClick = retry) { Text("再試行", fontSize = 12.sp) }
}

data class DayRange(val from: String, val to: String)

private val PRESETS = listOf(14, 30, 90)

/** 期間指定（Web: components/research/RangeFields.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RangeFields(range: DayRange, onChange: (DayRange) -> Unit, disabled: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Muted("開始日", 12); DateField(range.from, { if (!disabled) onChange(range.copy(from = it)) })
        Muted("終了日", 12); DateField(range.to, { if (!disabled) onChange(range.copy(to = it)) })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PRESETS.forEach { d -> SmallButton("直近${d}日", { onChange(DayRange(addDays(range.to, -(d - 1)), range.to)) }, enabled = !disabled) }
    }
}

fun defaultRange(): DayRange { val t = jstTodayKey(); return DayRange(addDays(t, -29), t) }
fun fmtDate(key: String?): String = key?.takeIf { it.isNotBlank() }?.replace("-", "/") ?: "—"

private val TOOLS = listOf(
    Triple("/operations", "運用表ビューワー", "保存済みの運用表をそのまま表示"),
    Triple("/research/diff", "運用表の差分比較", "2つの運用表を車両・運行で照合"),
    Triple("/forecast/plan", "1日運用予測", "運用表優先の予測と根拠"),
    Triple("/forecast/verify", "予測の検証", "予測と実際の運用を照合"),
    Triple("/calendar", "稼働カレンダー", "運用表・運行記録のある日"),
    Triple("/vehicle-db", "車両データベース", "形式・営業所・車歴"),
    Triple("/nara/stops", "停留所検索", "停留所の詳細・接近車両"),
)

/** 運用調査データセンター（Web: research.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchCenterScreen() {
    val nav = LocalNav.current
    var q by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(defaultRange()) }
    val (st, launch) = rememberRunner("検索できませんでした")
    fun run() { if (q.isBlank()) return; launch { Api.rpc("research/fns#searchOperations", jsonOf("q" to q.trim(), "from" to range.from, "to" to range.to)) } }
    val result = st.result
    ScreenList {
        item { Heading("運用調査データセンター", "保存済みの運用表（Googleスプレッドシート）を、車両番号・系統・行先・停留所・運用表名・運行順で横断検索します。結果から車両別運用履歴・系統別充当車両へ移動できます。") }
        item { ResearchNav("/research") }
        item {
            Panel {
                OutlinedTextField(q, { q = it }, label = { Text("検索キーワード") }, placeholder = { Text("車両番号・系統・行先・停留所・運用表名", fontSize = 12.sp) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
                RangeFields(range, { range = it }, st.loading)
                SmallButton(if (st.loading) "検索中…" else "検索", { run() }, enabled = !st.loading && q.isNotBlank(), icon = Icons.Outlined.Search, primary = true)
                Muted("期間内の新しい24枚の運用表を読み込みます。奈良交通APIへの追加呼び出しは行いません。", 10)
            }
        }
        st.error?.let { e -> item { RetryError(e) { run() } } }
        if (result != null) {
            item {
                Text("「${result.str("q")}」の検索結果: ${result.int("total") ?: 0}件（運用表${result.int("range.sheets") ?: 0}枚）", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            if ((result.int("total") ?: 0) == 0) item { Muted("一致する記載はありませんでした。キーワードを短くするか、期間を広げてください。", 12) }
            else {
                item { ResultGroup("🚌 車両", result.items("vehicles")) { nav.go("/research/vehicles?vehicle=" + android.net.Uri.encode(it)) } }
                item { ResultGroup("🚏 系統", result.items("routes")) { nav.go("/research/routes?route=" + android.net.Uri.encode(it)) } }
                item { ResultGroup("📍 停留所", result.items("stops")) { nav.go("/nara/stops") } }
            }
            val rows = result.items("rows")
            if (rows.isNotEmpty()) item { AssignmentRecords(rows, result.int("total") ?: rows.size, destKey = "to") }
        }
        item {
            Panel(title = "関連する画面") {
                TOOLS.forEachIndexed { i, (to, label, hint) ->
                    if (i > 0) RowDivider()
                    Column(Modifier.fillMaxWidth().clickable { nav.go(to) }.padding(vertical = 6.dp)) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Muted(hint, 11)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultGroup(title: String, items: List<JsonElement>, onPick: (String) -> Unit) {
    Panel(title = title) {
        if (items.isEmpty()) Muted("該当なし", 12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { it -> val name = it.str("name").orEmpty(); Tag("$name  ${it.int("count") ?: 0}", INFO, Modifier.clickable { onPick(name) }) }
        }
    }
}

/** 該当系統の運行記載（Web: AssignmentRecords） */
@Composable
fun AssignmentRecords(rows: List<JsonElement>, total: Int, destKey: String = "destination") {
    Panel(title = "運行記載", action = { Muted("${rows.size}件を表示${if (total > rows.size) "／全${total}件" else ""}  ") }) {
        rows.forEachIndexed { i, row ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(listOf(fmtDate(row.str("date")), row.str("timeText")?.ifBlank { null } ?: "—", row.str("route").orEmpty(), row.str("vehicle").orEmpty(), row.str(destKey)?.ifBlank { null } ?: "行先記載なし").filter { it.isNotBlank() }.joinToString("　"), fontSize = 12.sp)
                Muted("運用表「${row.str("sheet").orEmpty()}」${row.str("serviceLabel")?.takeIf { it.isNotBlank() }?.let { "・$it" } ?: ""}", 10)
            }
        }
    }
}

/** 車両ごとの充当状況（Web: AssignmentSummary） */
@Composable
fun AssignmentSummary(summary: List<JsonElement>) {
    summary.forEachIndexed { i, row ->
        if (i > 0) RowDivider()
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.str("vehicle").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${row.int("runs") ?: 0}回", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Muted("${row.str("vehicleType")?.ifBlank { null } ?: "形式未登録"}・${row.str("garage")?.ifBlank { null } ?: "営業所未登録"}　${row.int("days") ?: 0}日／最終 ${fmtDate(row.str("lastDate"))}", 11)
            val d = row.list("destinations").map { it.text }
            if (d.isNotEmpty()) Muted(d.joinToString(" / "), 10)
        }
    }
}

private val WEEKDAY = listOf("月", "火", "水", "木", "金", "土", "日")
fun weekdayOf(date: String?): String = runCatching { WEEKDAY[LocalDate.parse(date).dayOfWeek.value - 1] }.getOrDefault("")

private val CONF_LABEL = mapOf("high" to "根拠十分", "normal" to "参考", "unavailable" to "判定不能")
private fun confColor(c: String?): Color = when (c) { "high" -> Brand.Success; "normal" -> INFO; else -> Brand.MutedFg }

@Composable
fun PatternBadge(pattern: JsonElement?) {
    if (pattern == null || pattern is kotlinx.serialization.json.JsonNull) return
    val c = pattern.str("confidence")
    Tag("${pattern.str("label").orEmpty()}　${CONF_LABEL[c] ?: "参考"}", confColor(c))
}

@Composable
fun PatternEvidence(pattern: JsonElement?) {
    if (pattern == null || pattern is kotlinx.serialization.json.JsonNull) return
    val m = pattern.objAt("metrics")
    val items = listOfNotNull(
        if (m.str("startText") != null && m.str("endText") != null) "${m.str("startText")}〜${m.str("endText")}" else null,
        m.int("runs")?.takeIf { it > 0 }?.let { "運行${it}件" },
        m.dbl("medianInterval")?.let { "運行間隔 中央値${fmtNum(it)}分" },
        m.int("routeCount")?.takeIf { it > 0 }?.let { "系統${it}種類" },
    )
    pattern.str("reason")?.takeIf { it.isNotBlank() }?.let { Muted("根拠: $it", 10) }
    if (items.isNotEmpty()) Muted(items.joinToString(" ／ "), 10)
}

/** 1日分（1車両）の運用表の記載と運行記録（Web: OperationDayCard.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperationDayCard(day: JsonElement) {
    val segments = day.items("segments"); val runs = day.items("runs")
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("${day.str("date")}（${weekdayOf(day.str("date"))}）", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            day.str("sheet")?.takeIf { it.isNotBlank() }?.let { Tag(it, Brand.MutedFg) }
            day.str("serviceLabel")?.takeIf { it.isNotBlank() }?.let { Muted(it) }
            PatternBadge(day.objAt("pattern"))
        }
        Text("運用表の記載（予定・${segments.size}件）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        if (segments.isEmpty()) Muted("この日の運用表に、この車両の運行記載はありません。")
        segments.forEachIndexed { i, seg ->
            val prev = segments.getOrNull(i - 1)
            val gap = if (prev != null && seg.int("minutes") != null && prev.int("minutes") != null) seg.int("minutes")!! - prev.int("minutes")!! else null
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                Text(seg.str("timeText")?.ifBlank { null } ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                seg.str("route")?.takeIf { it.isNotBlank() }?.let { Tag(it, INFO) }
                Text("${seg.str("from").orEmpty()} → ${seg.str("to")?.ifBlank { null } ?: "行先記載なし"}", fontSize = 12.sp)
            }
            if (gap != null && gap >= 30) Muted("前の運行から${gap}分後の発車", 10)
        }
        PatternEvidence(day.objAt("pattern"))
        Text("確認できた運行記録（監視ログ・${runs.size}件）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        if (runs.isEmpty()) Muted("この日の運行記録はありません。記録が無いことは運休を意味しません。")
        runs.forEach { run ->
            Column(Modifier.padding(vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${run.str("atText")?.ifBlank { null } ?: "—"}${run.str("endText")?.takeIf { it.isNotBlank() }?.let { "〜$it" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    run.str("route")?.takeIf { it.isNotBlank() }?.let { Tag(it, Brand.Success) }
                    Text(run.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp)
                }
                val fs = run.str("fromStop")?.ifBlank { null }; val ls = run.str("lastStop")?.ifBlank { null }
                if (fs != null || ls != null) Muted("${fs ?: "始発不明"}${ls?.let { " → 最終確認 $it" } ?: ""}", 10)
            }
        }
        Muted("出典: ${day.str("sheet")?.takeIf { it.isNotBlank() }?.let { "運用表「$it」" } ?: "運用表なし"}・運行記録（5分間隔の監視ログ）", 10)
    }
}

/** 車両別運用履歴（Web: research.vehicles.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchVehiclesScreen(initialVehicle: String?) {
    val sources = rememberLoad("research-sources") { Api.rpc("research/fns#getResearchSources", jsonOf()) }
    var vehicle by remember { mutableStateOf(initialVehicle.orEmpty()) }
    var range by remember { mutableStateOf(defaultRange()) }
    val (st, launch) = rememberRunner("運用履歴を取得できませんでした")
    LaunchedEffect(sources.data) { if (vehicle.isBlank()) sources.data.items("vehicles").firstOrNull()?.str("name")?.let { vehicle = it } }
    fun run() { if (vehicle.isBlank()) return; launch { Api.rpc("research/fns#getVehicleOperationHistory", jsonOf("vehicle" to vehicle.trim(), "from" to range.from, "to" to range.to)) } }
    val result = st.result
    val names = sources.data.items("vehicles").mapNotNull { it.str("name") }
    ScreenList {
        item { Heading("車両別運用履歴", "保存済みの運用表の記載（予定）と、5分間隔の監視で確認できた運行記録（実績）を日付ごとに並べます。記録が無い時間帯は運休・待機と断定せず、そのまま「記録なし」と表示します。") }
        item { ResearchNav("/research/vehicles") }
        if (sources.data?.bool("configured") == false) item { Note("スプレッドシートが設定されていません。設定画面で運用表のスプレッドシートを指定してください（未設定の間は運行記録のみ表示されます）。", tone = Brand.Warning) }
        item {
            Panel {
                OutlinedTextField(vehicle, { vehicle = it }, label = { Text("車両番号") }, placeholder = { Text("例 奈良200か219", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val sugg = if (vehicle.isBlank()) emptyList() else names.filter { it.contains(vehicle) && it != vehicle }.take(6)
                if (sugg.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { sugg.forEach { n -> Tag(n, INFO, Modifier.clickable { vehicle = n }) } }
                RangeFields(range, { range = it }, st.loading)
                SmallButton(if (st.loading) "読み込み中…" else "履歴を表示", { run() }, enabled = !st.loading && vehicle.isNotBlank(), icon = Icons.Outlined.Search, primary = true)
                Muted("運用表は期間内の新しい${result?.int("range.sheets") ?: 24}枚までを読み込みます（それ以前は対象外）。奈良交通APIへの追加呼び出しは行いません。", 10)
            }
        }
        sources.error?.let { item { RetryError("運用表の一覧を取得できませんでした。") { sources.reload() } } }
        if (sources.loading && sources.data == null) item { Muted("運用表の一覧を読み込み中…", 12) }
        st.error?.let { e -> item { RetryError(e) { run() } } }
        if (st.loading) item { Muted("運用表と運行記録を読み込み中…", 12) }
        if (result != null) {
            val t = result.objAt("totals")
            item {
                Column {
                    Text(result.str("vehicle.name").orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Muted("${result.str("vehicle.vehicleType")?.ifBlank { null } ?: "形式未登録"}・${result.str("vehicle.garage")?.ifBlank { null } ?: "営業所未登録"}", 12)
                }
            }
            item {
                KpiRow(
                    Triple("運用表のある日", "${t.int("planDays") ?: 0}", null), Triple("運行記録のある日", "${t.int("runDays") ?: 0}", null),
                    Triple("最終の運用表", t.str("lastPlanDate")?.drop(5) ?: "—", null), Triple("最終の運行記録", t.str("lastRunDate")?.drop(5) ?: "—", null),
                    units = listOf("日", "日", null, null),
                )
                Muted("運行記載 ${t.int("planRuns") ?: 0}件 ／ 記録 ${t.int("observedRuns") ?: 0}件 ／ 最終運用表 ${t.str("lastPlanDate") ?: "期間内に記載なし"} ／ 最終記録 ${t.str("lastRunDate") ?: "期間内に記録なし"}", 10)
            }
            if (result.bool("range.truncated") == true) item { Note("期間内に運用表が${result.int("range.inRange")}枚あります。読み込み上限のため、新しい${result.int("range.sheets")}枚だけを表示しています。", tone = Brand.Warning) }
            val days = result.items("days")
            if (days.isEmpty()) item { Muted("この期間に、この車両の運用表の記載も運行記録もありません。期間を広げて再検索してください。", 12) }
            items(days.size) { i -> OperationDayCard(days[i]) }
        }
    }
}

/** 系統別充当車両（Web: research.routes.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchRoutesScreen(initialRoute: String?) {
    var route by remember { mutableStateOf(initialRoute.orEmpty()) }
    var vehicle by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(defaultRange()) }
    val (st, launch) = rememberRunner("充当車両を取得できませんでした")
    fun run() { if (route.isBlank()) return; launch { Api.rpc("research/fns#searchRouteAssignments", jsonOf("route" to route.trim(), "vehicle" to vehicle.trim().ifBlank { null }, "from" to range.from, "to" to range.to)) } }
    LaunchedEffect(Unit) { if (!initialRoute.isNullOrBlank()) run() }
    val result = st.result
    ScreenList {
        item { Heading("系統別充当車両", "保存済みの運用表に「その系統の運行」として記載された車両を集計します。記載のある車両だけを対象とし、充当が確認できない車両は表示しません。") }
        item { ResearchNav("/research/routes") }
        item {
            Panel {
                OutlinedTextField(route, { route = it }, label = { Text("系統番号・系統名") }, placeholder = { Text("例 160", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vehicle, { vehicle = it }, label = { Text("車両番号（任意）") }, placeholder = { Text("絞り込む場合", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RangeFields(range, { range = it }, st.loading)
                SmallButton(if (st.loading) "検索中…" else "検索", { run() }, enabled = !st.loading && route.isNotBlank(), icon = Icons.Outlined.Search, primary = true)
                Muted("車両形式・営業所は車両データベースの登録値です。未登録の項目は「未登録」と表示し、推測で補いません。", 10)
            }
        }
        st.error?.let { e -> item { RetryError(e) { run() } } }
        if (st.loading) item { Muted("運用表を確認しています…", 12) }
        if (result != null) {
            val rare = result.items("rare")
            item {
                KpiRow(
                    Triple("該当する運行記載", "${result.int("total") ?: 0}", null), Triple("充当した車両", "${result.items("summary").size}", null),
                    Triple("運用表", "${result.int("range.sheets") ?: 0}", null), Triple("単発の充当", "${rare.size}", INFO),
                    units = listOf("件", "台", "枚", "台"),
                )
                Muted("期間内の運用表 ${result.int("range.inRange") ?: 0}枚 ／ 単発＝期間内に1回だけの車両", 10)
            }
            if (result.bool("range.truncated") == true) item { Note("期間内に運用表が${result.int("range.inRange")}枚あります。読み込み上限のため、新しい${result.int("range.sheets")}枚だけを集計しています。", tone = Brand.Warning) }
            if ((result.int("total") ?: 0) == 0) item { Muted("「${result.str("route")}」に一致する運行記載は見つかりませんでした。系統番号の表記（例 160）や期間を変えて再検索してください。", 12) }
            else {
                item { Panel(title = "車両ごとの充当状況") { AssignmentSummary(result.items("summary")) } }
                if (rare.isNotEmpty()) item {
                    Panel(title = "✨ 珍しい充当（期間内1回のみ）") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { rare.forEach { Tag("${it.str("vehicle")}（${fmtDate(it.str("lastDate"))}）", INFO) } }
                    }
                }
                item { AssignmentRecords(result.items("rows"), result.int("total") ?: 0) }
            }
        }
    }
}

private val STATUS = mapOf(
    "changed" to ("差分あり" to Brand.Warning), "base_only" to ("基準のみ" to Brand.Destructive), "compare_only" to ("比較のみ" to Brand.Success),
    "same" to ("一致" to Brand.MutedFg), "no_runs" to ("運行記載なし" to Brand.MutedFg),
)
private val DIFF_LABELS = mapOf("same" to "変更なし", "shifted" to "時刻変更", "changed" to "内容変更", "removed" to "基準のみ", "added" to "比較のみ")
private val DIFF_TONE = mapOf("same" to Brand.MutedFg, "shifted" to Brand.Warning, "changed" to INFO, "removed" to Brand.Destructive, "added" to Brand.Success)

@Composable
private fun StatusBadge(status: String?) { val (l, c) = STATUS[status] ?: STATUS.getValue("same"); Tag(l, c) }

private fun breakdown(c: JsonElement?): String = listOfNotNull(
    c.int("shifted")?.takeIf { it > 0 }?.let { "時刻$it" }, c.int("changed")?.takeIf { it > 0 }?.let { "内容$it" },
    c.int("removed")?.takeIf { it > 0 }?.let { "基準のみ$it" }, c.int("added")?.takeIf { it > 0 }?.let { "比較のみ$it" },
).joinToString("・").ifBlank { "—" }

/** 運用表の差分比較（Web: research.diff.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResearchDiffScreen() {
    val sources = rememberLoad("research-sources") { Api.rpc("research/fns#getResearchSources", jsonOf()) }
    var baseDate by remember { mutableStateOf("") }
    var compareDate by remember { mutableStateOf("") }
    var onlyDiff by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf("") }
    val (st, launch) = rememberRunner("比較できませんでした")
    val dates = sources.data.items("dates")
    LaunchedEffect(sources.data) {
        if (dates.isNotEmpty()) { if (baseDate.isBlank()) baseDate = dates[0].str("date").orEmpty(); if (compareDate.isBlank()) compareDate = dates.getOrNull(1)?.str("date").orEmpty() }
    }
    val canRun = baseDate.isNotBlank() && compareDate.isNotBlank() && baseDate != compareDate
    fun run() { if (!canRun) return; selected = ""; launch { Api.rpc("research/fns#comparePlanDays", jsonOf("baseDate" to baseDate, "compareDate" to compareDate)) } }
    val result = st.result
    val vehicles = result.items("vehicles")
    val shown = if (onlyDiff) vehicles.filter { it.str("status") != "same" && it.str("status") != "no_runs" } else vehicles
    val opts = if (dates.isEmpty()) listOf("" to "運用表がありません") else dates.map { val d = it.str("date").orEmpty(); d to "$d（${it.str("serviceLabel").orEmpty()}）${it.str("sheet").orEmpty()}" }
    ScreenList {
        item { Heading("運用表の差分比較", "保存済みの運用表2枚を選び、車両ごとの運行（時刻・系統・始発・行先）を突き合わせます。片方にしか無い運行は「記載なし」として区別し、別の日や別の運行区分の運用表で補完はしません。") }
        item { ResearchNav("/research/diff") }
        if (sources.data?.bool("configured") == false) item { Note("スプレッドシートが設定されていません。設定画面で運用表のスプレッドシートを指定してください。", tone = Brand.Warning) }
        item {
            Panel {
                SelectField(opts, baseDate, { baseDate = it }, label = "基準の運用表", modifier = Modifier.fillMaxWidth())
                SelectField(opts, compareDate, { compareDate = it }, label = "比較する運用表", modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                SmallButton(if (st.loading) "比較中…" else "差分を比較", { run() }, enabled = !st.loading && canRun, icon = Icons.Outlined.CompareArrows, primary = true, modifier = Modifier.padding(top = 6.dp))
                Muted("同じ日付どうしは比較できません。読み込むのは選んだ2枚だけで、奈良交通APIへの追加呼び出しは行いません。", 10)
            }
        }
        sources.error?.let { item { RetryError("運用表の一覧を取得できませんでした。") { sources.reload() } } }
        if (sources.loading && sources.data == null) item { Muted("運用表の一覧を読み込み中…", 12) }
        st.error?.let { e -> item { RetryError(e) { run() } } }
        if (result?.bool("sameDate") == true) item { Note("同じ日付が選ばれています。基準と比較で別の日付を選んでください。", tone = Brand.Warning) }
        result?.objAt("missing")?.let { m ->
            item { Note("${if (m.bool("base") == true) result.str("baseDate") else result.str("compareDate")} の運用表が見つかりませんでした。一覧にある日付を選び直してください。", tone = Brand.Warning) }
        }
        val t = result?.objAt("totals")
        if (result != null && t != null) {
            item {
                val diffRuns = listOf("shifted", "changed", "removed", "added").sumOf { t.int(it) ?: 0 }
                KpiRow(
                    Triple("基準の運行", "${t.int("runsBase") ?: 0}", null), Triple("比較の運行", "${t.int("runsCompare") ?: 0}", null),
                    Triple("差分のある車両", "${t.int("vehiclesChanged") ?: 0}", if ((t.int("vehiclesChanged") ?: 0) > 0) Brand.Warning else null), Triple("差分のある運行", "$diffRuns", INFO),
                    units = listOf("件", "件", "台", "件"),
                )
                Muted("基準 ${t.int("vehiclesBase") ?: 0}台・${result.str("base.sheet").orEmpty()} ／ 比較 ${t.int("vehiclesCompare") ?: 0}台・${result.str("compare.sheet").orEmpty()}", 10)
                Muted("基準のみ ${t.int("baseOnly") ?: 0}台／比較のみ ${t.int("compareOnly") ?: 0}台 ／ 時刻変更${t.int("shifted") ?: 0}・内容変更${t.int("changed") ?: 0}・基準のみ${t.int("removed") ?: 0}・比較のみ${t.int("added") ?: 0}", 10)
            }
            if (result.bool("sameService") == false) item {
                Note("運行区分が異なる運用表を比較しています（${result.str("base.serviceLabel")?.ifBlank { null } ?: "不明"} と ${result.str("compare.serviceLabel")?.ifBlank { null } ?: "不明"}）。差分にはダイヤ自体の違いが含まれるため、車両の異動や運用変更として読み取らないでください。", tone = Brand.Warning)
            }
            val dups = result.list("base.duplicates").map { it.text } + result.list("compare.duplicates").map { it.text }
            if (dups.isNotEmpty()) item { Muted("同じ日付の別シート（${dups.joinToString(" / ")}）は比較に使っていません。") }
            item {
                Panel(title = "車両ごとの差分", action = {
                    Row(verticalAlignment = Alignment.CenterVertically) { Muted("${shown.size}台を表示"); Checkbox(onlyDiff, { onlyDiff = it }); Muted("差分のある車両のみ") }
                }) {
                    if (shown.isEmpty()) Muted("この条件に当てはまる車両はありません。「すべての車両」に切り替えるか、別の日付の組み合わせを選んでください。", 12)
                    shown.forEachIndexed { i, row ->
                        if (i > 0) RowDivider()
                        val name = row.str("vehicle").orEmpty()
                        Column(Modifier.fillMaxWidth().clickable { selected = if (selected == name) "" else name }.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (selected == name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                StatusBadge(row.str("status"))
                            }
                            Muted("基準 ${row.int("runsBase") ?: 0}件 / 比較 ${row.int("runsCompare") ?: 0}件　${breakdown(row.objAt("counts"))}", 11)
                            if (selected == name) PlanDiffDetail(row, result.str("base.date").orEmpty(), result.str("compare.date").orEmpty())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentLine(label: String, seg: JsonElement?) {
    Column(Modifier.padding(top = 2.dp)) {
        Muted(label, 10)
        if (seg == null || seg is kotlinx.serialization.json.JsonNull) Muted("この運行は記載なし", 11)
        else Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(seg.str("timeText")?.ifBlank { null } ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            seg.str("route")?.takeIf { it.isNotBlank() }?.let { Tag(it, INFO) }
            Text("${seg.str("from")?.ifBlank { null } ?: "始発記載なし"} → ${seg.str("to")?.ifBlank { null } ?: "行先記載なし"}", fontSize = 12.sp)
        }
    }
}

/** 選択した1車両の運転単位の差分（Web: PlanDiffDetail.jsx） */
@Composable
private fun PlanDiffDetail(vehicle: JsonElement, baseDate: String, compareDate: String) {
    val rows = vehicle.items("rows")
    Column(Modifier.padding(top = 6.dp)) {
        Muted("$baseDate ${vehicle.int("runsBase") ?: 0}件 → $compareDate ${vehicle.int("runsCompare") ?: 0}件　差分 ${rows.count { it.str("status") != "same" }}件 / 全 ${rows.size}件", 11)
        if (rows.isEmpty()) Muted("この車両は両方の運用表に同じ内容で記載されています。")
        rows.forEach { row ->
            val s = row.str("status").orEmpty()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(DIFF_LABELS[s] ?: s, DIFF_TONE[s] ?: Brand.MutedFg)
                    val f = row.list("fields").map { it.text }
                    if (f.isNotEmpty()) Muted("変更点: ${f.joinToString("・")}")
                    row.int("deltaMinutes")?.takeIf { it != 0 }?.let { Text("${if (it > 0) "+$it" else "$it"}分", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.Warning) }
                }
                SegmentLine("基準（${baseDate.drop(5)}）", row.objAt("base"))
                SegmentLine("比較（${compareDate.drop(5)}）", row.objAt("compare"))
            }
        }
    }
}
