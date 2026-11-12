package app.narabuspulse.feature.forecast

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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Busy
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.analytics.rememberLoadRpc
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.DateRange
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.RangePicker
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.feature.research.rememberRunner
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.HBar
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
import kotlin.math.roundToInt

private val TABS = listOf("/forecast" to "1日運用予測", "/forecast/plan" to "運用表優先の予測", "/forecast/verify" to "運用表で検証", "/forecast/history" to "予測履歴", "/forecast/accuracy" to "予測精度分析")

@Composable
fun ForecastNav(current: String) {
    val nav = LocalNav.current
    ChipRow(TABS, current, { if (it != current) nav.go(it) })
}

/** lib/forecast/weights.js の定数 */
data class Period(val key: String, val label: String, val from: Int, val to: Int)
val PERIODS = listOf(Period("morning", "朝", 5, 9), Period("am", "午前", 9, 12), Period("noon", "昼", 12, 15), Period("pm", "午後", 15, 18), Period("evening", "夕方", 18, 21), Period("night", "夜", 21, 27))
private val DEFAULT_WEIGHTS = listOf("sameVehicle" to 30, "weekdayCategory" to 20, "runOrder" to 20, "timeProximity" to 15, "routeDestination" to 10, "recency" to 5)
private val WEIGHT_LABELS = mapOf("sameVehicle" to "同じ車両", "weekdayCategory" to "同じ曜日・祝日区分", "runOrder" to "運行順序の一致", "timeProximity" to "時刻・時間帯の近さ", "routeDestination" to "系統・行先の一致", "recency" to "直近の運行傾向")
private val CONFIDENCE = mapOf("high" to ("高" to Brand.Success), "normal" to ("普通" to INFO), "low" to ("低" to Brand.Warning), "unavailable" to ("予測不可" to Brand.MutedFg))
private val VERDICT = mapOf("match" to ("一致" to Brand.Success), "partial" to ("部分一致" to Brand.Warning), "mismatch" to ("不一致" to Brand.Destructive), "undecided" to ("判定不能" to Brand.MutedFg))

@Composable
fun ConfidenceBadge(level: String?) { val (l, c) = CONFIDENCE[level] ?: CONFIDENCE.getValue("unavailable"); Tag("信頼度 $l", c) }

@Composable
fun VerdictBadge(verdict: String?) { val (l, c) = VERDICT[verdict] ?: VERDICT.getValue("undecided"); Tag(l, c) }

fun pct(v: Double?): String = "${((v ?: 0.0) * 100).roundToInt()}%"

/** 予測された1日の運行順序（Web: ForecastTimeline.jsx） */
@Composable
fun ForecastTimeline(plan: JsonElement, title: String = "予測された1日の運行順序（朝 → 夜）") {
    val items = plan.items("items"); val missing = plan.items("missing")
    val basis = plan.list("usableDates").size.takeIf { it > 0 } ?: plan.int("metrics.similarDays") ?: 0
    Panel(title = title, action = { Muted("予測 ${items.size} 件 / 予測不可 ${missing.size} 件  ") }) {
        PERIODS.forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            val item = items.firstOrNull { it.str("period") == p.key }
            val reason = missing.firstOrNull { it.str("period") == p.key }?.str("reason")
            Row(Modifier.padding(vertical = 6.dp)) {
                Column(Modifier.width(56.dp)) {
                    Text(p.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Muted("${p.from}〜${if (p.to > 24) p.to - 24 else p.to}時", 10)
                }
                Column(Modifier.weight(1f)) {
                    if (item != null) {
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(item.str("timeText").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Muted("範囲 ${item.str("rangeText").orEmpty()}", 10)
                            Tag(item.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                            Text(item.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp)
                            Muted("参考スコア ${item.dbl("score")?.let { fmtNum(it) } ?: "—"}", 10)
                        }
                        Muted("予測順序 ${item.int("order") ?: 0} 番目・根拠 ${item.int("support") ?: 0}/$basis 日（記録 ${item.int("sampleItems") ?: 0} 件）", 10)
                        val alts = item.items("alternatives")
                        if (alts.isNotEmpty()) Muted("他候補：" + alts.joinToString(" / ") { a -> "${a.str("route")?.ifBlank { null } ?: "系統不明"} ${a.str("destination").orEmpty()}（${a.int("days") ?: 0}日・${a.str("timeText").orEmpty()}）" }, 10)
                    } else {
                        Text("予測不可", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
                        Muted(reason ?: "この時間帯の記録が無いため予測していません", 10)
                    }
                }
            }
        }
    }
}

private val PARTS = listOf("weekdayCategory" to "曜日区分", "runOrder" to "運行順序", "timeProximity" to "時刻", "routeDestination" to "系統・行先", "recency" to "直近")

/** 類似運用日（Web: SimilarDays.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimilarDays(days: List<JsonElement>) {
    var open by remember { mutableStateOf<String?>(null) }
    if (days.isEmpty()) { Panel(title = "類似運用日") { Muted("類似運用データ不足のため、過去の類似日を特定できませんでした。", 12) }; return }
    Panel(title = "類似運用日（最大5件）", subtitle = "類似度スコアは予測の正解率ではありません") {
        days.forEachIndexed { i, day ->
            if (i > 0) RowDivider()
            val date = day.str("date").orEmpty(); val expanded = open == date
            val seq = day.items("sequence")
            Column(Modifier.fillMaxWidth().clickable { open = if (expanded) null else date }.padding(vertical = 6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("${if (expanded) "▾" else "▸"} $date", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Muted("${day.str("weekdayLabel").orEmpty()}${day.str("dowLabel")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}")
                    Tag("類似 ${day.dbl("score")?.let { fmtNum(it) } ?: "—"}", INFO)
                    Muted("比較 ${day.int("matchedItems") ?: 0} 項目", 10)
                }
                Muted(seq.joinToString(" → ") { it.str("route")?.ifBlank { null } ?: "系統不明" }.ifBlank { "系統の記録なし" }, 11)
                if (expanded) {
                    Muted("実際の運行 ${seq.size} 件・${if (day.bool("continuous") == true) "連続した運用として確認" else "断続（間隔の空き ${day.int("breaks") ?: 0} か所）"}・終了時刻あり ${day.int("completeness") ?: 0}%", 10)
                    seq.forEach { s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                            Text("${s.int("order") ?: 0}", fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.width(18.dp))
                            Text(s.str("timeText").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Tag(s.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                            Text(s.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { PARTS.forEach { (k, l) -> Tag("$l ${pct(day.dbl("scoreParts.$k"))}", Brand.MutedFg) } }
                }
            }
        }
    }
}

/** 予測結果の自動検証（Web: VerifyCard.jsx） */
@Composable
fun VerifyCard(forecastId: String) {
    val (st, launch) = rememberRunner("照合できませんでした")
    Panel(title = "予測結果の自動検証") {
        Muted("予測時点より後に確認された運行記録だけを実績として照合します（予測の入力に使った記録は判定に使いません）。")
        SmallButton(if (st.loading) "照合中…" else "照合する", { launch { Api.rpc("forecast/server-fns#verifyForecast", jsonOf("forecastId" to forecastId)).objAt("result") ?: kotlinx.serialization.json.JsonNull } }, enabled = !st.loading, icon = Icons.Outlined.CheckCircle)
        st.error?.let { ErrorText(it) }
        st.result?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { r -> VerifyResult(r) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerifyResult(r: JsonElement) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        VerdictBadge(r.str("verdict"))
        Text("一致 ${r.int("match") ?: 0}", fontSize = 12.sp); Text("部分一致 ${r.int("partial") ?: 0}", fontSize = 12.sp); Text("不一致 ${r.int("mismatch") ?: 0}", fontSize = 12.sp)
        Muted("時刻誤差中央値 ${r.dbl("timeError")?.let { "${fmtNum(it)} 分" } ?: "—"}")
        r.str("dataRange")?.takeIf { it.isNotBlank() }?.let { Muted("対象 $it") }
    }
    r.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
    r.items("items").forEachIndexed { i, it ->
        if (i > 0) RowDivider()
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it.str("period").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                Text("予測 ${it.str("predicted").orEmpty()} ${it.str("predictedTime").orEmpty()}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                VerdictBadge(it.str("status"))
            }
            val diff = it.dbl("timeDiff")?.let { d -> "${if (d > 0) "+" else ""}${fmtNum(d)}分" } ?: "—"
            Muted("実際 ${it.str("actual")?.ifBlank { null } ?: "—"} ${it.str("actualTime")?.ifBlank { null } ?: "—"}　差 $diff", 11)
        }
    }
}

/** 1日運用予測（Web: forecast.index.jsx） */
@Composable
fun ForecastScreen() {
    val sources = rememberLoad("forecast-vehicles") { Api.rpc("forecast/server-fns#listForecastVehicles", jsonOf()) }
    var vehicle by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(jstTodayKey()) }
    val (st, launch) = rememberRunner("予測できませんでした")
    LaunchedEffect(sources.data) { if (vehicle.isBlank()) sources.data.items("vehicles").firstOrNull()?.str("vehicle")?.let { vehicle = it } }
    fun run() = launch { Api.rpc("forecast/server-fns#predictVehicleDay", jsonOf("vehicle" to vehicle, "date" to date)) }
    val plan = st.result?.objAt("plan")
    ScreenList {
        item { Heading("1日運用予測", "同じ車両の過去の運行記録から、予測対象日の朝から夜までの運用順序・系統・行先・時刻を統計的に予測します。外部APIへの追加アクセスは行いません。") }
        item { ForecastNav("/forecast") }
        item {
            Panel {
                SelectField(listOf("" to "車両を選択") + sources.data.items("vehicles").map { val v = it.str("vehicle").orEmpty(); v to "$v（記録 ${it.int("records") ?: 0} 件）" }, vehicle, { vehicle = it }, label = "車両", modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Muted("予測対象日", 12); DateField(date, { date = it })
                    SmallButton(if (st.loading) "予測中…" else "予測する", { run() }, enabled = !st.loading && vehicle.isNotBlank() && date.isNotBlank(), icon = Icons.Outlined.AutoAwesome, primary = true)
                }
                val rd = sources.data.list("recordedDays").map { it.text }
                Muted("保存済みの運行記録だけを使用します（奈良交通APIへの追加アクセスはありません）。履歴のある日：${if (rd.isEmpty()) "なし" else rd.take(6).joinToString(" / ")}", 10)
            }
        }
        if (sources.loading && sources.data == null) item { Muted("車両と履歴を確認しています…", 12) }
        sources.error?.let { item { ErrorText(it) } }
        st.error?.let { item { ErrorText(it) } }
        if (plan != null) {
            val m = plan.objAt("metrics")
            item {
                KpiRow(
                    Triple("予測対象日", plan.str("targetDate").orEmpty(), null), Triple("参考スコア", plan.dbl("score")?.let { fmtNum(it) } ?: "—", INFO),
                    Triple("根拠の履歴", "${plan.int("dataDays") ?: 0}", null), Triple("予測できた運行", "${plan.items("items").size}", if (plan.bool("available") == true) Brand.Success else Brand.MutedFg),
                    units = listOf(null, null, "日", "件"),
                )
                Muted("${plan.str("weekday.label").orEmpty()}${plan.str("weekday.dowLabel")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}${if (plan.bool("weekday.holidayKnown") == true) "" else "・祝日データ未確認"} ／ スコアは類似度の合計。的中率ではありません ／ 類似日 ${m.int("similarDays") ?: 0} 日 / 同区分 ${m.int("categoryDays") ?: 0} 日", 10)
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { ConfidenceBadge(plan.str("confidence")); Text(plan.str("confidenceReason").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f)) }
                    if (m != null) Muted("履歴日数 ${m.int("dataDays") ?: 0}　使用した記録 ${m.int("runsUsed") ?: plan.int("runsUsed") ?: 0} 件　類似日数 ${m.int("similarDays") ?: 0}　最多パターン占有率 ${pct(m.dbl("topPatternShare"))}　時刻ばらつき（IQR中央値）${m.dbl("timeSpread")?.let { fmtNum(it) } ?: "—"} 分　欠損時間帯 ${pct(m.dbl("missingRatio"))}　終了時刻の記録率 ${pct(m.dbl("completeness"))}", 10)
                    if (plan.bool("available") != true) Note("予測に必要な履歴が不足しています。参考スコアの高い日（下の類似運用日）を確認してください。予測結果は作成していません。", tone = Brand.Warning)
                }
            }
            item { ForecastTimeline(plan) }
            item { SimilarDays(plan.items("similarDays")) }
            st.result.str("forecastId")?.takeIf { plan.bool("available") == true }?.let { id -> item { VerifyCard(id) } }
            item {
                Panel(title = "予測に使っている重み") {
                    DEFAULT_WEIGHTS.forEach { (k, v) -> Row(Modifier.fillMaxWidth()) { Text(WEIGHT_LABELS[k].orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("$v%", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                    Muted("類似度スコアは過去の運行との近さを表す参考値で、的中率や実際の確率ではありません。予測は同じ車両の記録だけを根拠にし、記録の無い時間帯は予測せず「予測不可」と表示します。", 10)
                }
            }
        }
        if (plan == null && !st.loading && !sources.loading) item { Muted("車両と予測対象日を選んで「予測する」を押してください。", 12) }
    }
}

/** 予測履歴（Web: forecast.history.jsx + HistoryList.jsx） */
@Composable
fun ForecastHistoryScreen() {
    var vehicle by remember { mutableStateOf("") }
    val sources = rememberLoad("forecast-vehicles") { Api.rpc("forecast/server-fns#listForecastVehicles", jsonOf()) }
    val q = rememberLoad("forecast-history", vehicle) { Api.rpc("forecast/server-fns#listForecastHistory", jsonOf("vehicle" to vehicle.ifBlank { null }, "limit" to 30)) }
    ScreenList {
        item { Heading("予測履歴", "保存した1日運用予測の記録です。予測時点の内容は書き換えず、そのまま残します。") }
        item { ForecastNav("/forecast/history") }
        item {
            Panel {
                SelectField(listOf("" to "すべての車両") + sources.data.items("vehicles").mapNotNull { it.str("vehicle") }.map { it to it }, vehicle, { vehicle = it }, label = "車両で絞り込み", modifier = Modifier.fillMaxWidth())
                Muted("直近30件を表示。照合ボタンで、予測時点より後に確認された実績と比較します。", 10)
            }
        }
        if (q.loading && q.data == null) item { Muted("読み込んでいます…", 12) }
        q.error?.let { item { ErrorText(it) } }
        q.data?.let { d ->
            val rows = d.items("items")
            if (rows.isEmpty()) item { Muted("この条件の予測履歴はまだありません。", 12) }
            items(rows.size) { i -> ForecastRow(rows[i]) { q.reload() } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForecastRow(row: JsonElement, onChecked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val check = row.objAt("check")
    Panel {
        Column(Modifier.fillMaxWidth().clickable { open = !open }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text("${if (open) "▾" else "▸"} ${row.str("targetDate").orEmpty()}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(row.str("vehicle").orEmpty(), fontSize = 12.sp)
                Tag(row.str("weekdayLabel").orEmpty(), Brand.MutedFg)
            }
            Muted("${BusFormat.dateTime(row.long("predictedAt"))} 予測", 10)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            ConfidenceBadge(row.str("confidence"))
            Muted("スコア ${row.dbl("score")?.let { fmtNum(it) } ?: "—"}")
            if (check != null) VerdictBadge(check.str("verdict"))
            else SmallButton(if (busy) "照合中…" else "照合", {
                busy = true; err = null
                scope.launch {
                    try { Api.rpc("forecast/server-fns#verifyForecast", jsonOf("forecastId" to row.str("id"))); onChecked() }
                    catch (e: Throwable) { if (e is CancellationException) throw e; err = e.userMessage() } finally { busy = false }
                }
            }, enabled = !busy, icon = Icons.Outlined.Refresh)
        }
        err?.let { ErrorText(it) }
        if (open) {
            Muted("${row.str("summary").orEmpty()} ・根拠 ${row.int("dataDays") ?: 0} 日・エンジン v${row.str("engineVersion") ?: row.dbl("engineVersion")?.let { fmtNum(it) } ?: ""}", 10)
            row.items("items").forEach { it ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Text(it.str("label").orEmpty(), fontSize = 11.sp, modifier = Modifier.width(36.dp))
                    Text(it.str("timeText").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Tag(it.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                    Text(it.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted("スコア ${it.dbl("score")?.let { s -> fmtNum(s) } ?: "—"}", 10)
                }
            }
            row.items("missing").forEach { Muted("・${it.str("reason").orEmpty()}", 10) }
            if (check != null) {
                Muted("検証 ${BusFormat.dateTime(check.long("checkedAt"))}・一致 ${check.int("matchCount") ?: 0} / 部分 ${check.int("partialCount") ?: 0} / 不一致 ${check.int("mismatchCount") ?: 0}${check.dbl("timeError")?.let { "・時刻誤差中央値 ${fmtNum(it)} 分" } ?: ""}", 10)
                check.str("dataRange")?.takeIf { it.isNotBlank() }?.let { Muted("対象 $it", 10) }
                check.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
            }
        }
    }
}

private val WEEKDAY_LABELS = mapOf("weekday" to "平日", "saturday" to "土曜", "sunday" to "日曜", "holiday" to "祝日", "unknown" to "祝日未確認")

@Composable
private fun RateTable(title: String, kind: String, rows: List<JsonElement>) {
    Panel(title = title) {
        if (rows.isEmpty()) { Muted("判定できる項目がまだありません。", 12); return@Panel }
        rows.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            val key = r.str("key").orEmpty()
            val label = when (kind) { "weekday" -> WEEKDAY_LABELS[key] ?: key; "period" -> PERIODS.firstOrNull { it.key == key }?.label ?: key; else -> key }
            val rate = r.dbl("rate")
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(rate?.let { "${fmtNum(it)}%" } ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Success)
                }
                HBar(((rate ?: 0.0) / 100).toFloat(), color = Brand.Success, height = 6.dp)
                Muted("判定 ${r.int("judged") ?: 0}　一致 ${r.int("match") ?: 0}　部分一致 ${r.int("partial") ?: 0}　不一致 ${r.int("mismatch") ?: 0}", 10)
            }
        }
    }
}

/** 予測精度分析（Web: forecast.accuracy.jsx + AccuracyTables.jsx） */
@Composable
fun ForecastAccuracyScreen() {
    val today = jstTodayKey()
    var range by remember { mutableStateOf(DateRange(addDays(today, -29), today)) }
    val q = rememberLoadRpc("forecast/server-fns#getForecastAccuracy", range)
    val data = q.data
    ScreenList {
        item { Heading("予測精度分析", "保存済みの予測と、予測時点より後に確認された実績を照合した結果を集計します。1日の全運用が確認できない場合は、確認できた項目だけで算出します。") }
        item { ForecastNav("/forecast/accuracy") }
        item { RangePicker(range, { range = it }) }
        if (q.loading) item { Busy() }
        q.error?.let { item { ErrorText(it) } }
        if (data != null) {
            val t = data.objAt("totals")
            if ((t.int("checks") ?: 0) == 0) item { Note("この期間に照合済みの予測がありません。1日運用予測で予測を作成し、対象日の実績が記録されたあとに「照合する」を実行してください。") }
            else {
                fun p(k: String) = t.dbl(k)?.let { "${fmtNum(it)}%" } ?: "—"
                item {
                    KpiRow(
                        Triple("予測件数", "${t.int("checks") ?: 0}", null), Triple("一致率（項目単位）", p("rate"), Brand.Success),
                        Triple("判定不能", "${t.int("undecided") ?: 0}", Brand.MutedFg), Triple("データ充足率", p("dataCompleteness"), INFO),
                        Triple("一致", "${t.int("match") ?: 0}", Brand.Success), Triple("部分一致", "${t.int("partial") ?: 0}", Brand.Warning),
                        Triple("不一致", "${t.int("mismatch") ?: 0}", Brand.Destructive), Triple("時刻誤差（平均）", t.dbl("avgTimeError")?.let { fmtNum(it) } ?: "—", null),
                        units = listOf("件", null, "件", null, "項目", "項目", "項目", if (t.dbl("avgTimeError") == null) null else "分"),
                    )
                    Muted("照合できた ${t.int("checkable") ?: 0} 件 ／ 部分一致 ${p("partialRate")} ／ 予測時点より後の実績が無いものは判定不能 ／ 予測 ${t.int("predictedItems") ?: 0} 項目中 ${t.int("judged") ?: 0} 項目を判定", 10)
                }
                item { RateTable("車両別", "vehicle", data.items("byVehicle")) }
                item { RateTable("曜日区分別", "weekday", data.items("byWeekday")) }
                item { RateTable("時間帯別", "period", data.items("byPeriod")) }
                item { RateTable("系統別", "route", data.items("byRoute")) }
                data.str("note")?.let { item { Muted(it, 10) } }
            }
        }
    }
}
