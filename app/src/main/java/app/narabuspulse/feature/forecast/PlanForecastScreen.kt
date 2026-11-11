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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.feature.research.rememberRunner
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private val MODE_TONE = mapOf("plan" to Brand.Success, "legacy" to INFO, "unavailable" to Brand.Warning)
private val STAGE = mapOf("plan" to "第1段階", "legacy" to "第2段階", "unavailable" to "判定不能")

/** lib/forecast/plan-weights.js の定数（ロジックバンドルから取得） */
@Composable
fun rememberPlanWeights(): JsonElement? = produceState<JsonElement?>(null) {
    value = jsonOf(
        "weights" to Logic.constant("forecast/plan-weights", "PLAN_WEIGHTS"),
        "labels" to Logic.constant("forecast/plan-weights", "PLAN_WEIGHT_LABELS"),
        "version" to Logic.constant("forecast/plan-weights", "PLAN_ENGINE_VERSION"),
        "note" to Logic.constant("forecast/plan-weights", "PLAN_METHOD_NOTE"),
    )
}.value

@Composable
fun rememberServiceType(date: String): JsonElement? = produceState<JsonElement?>(null, date) {
    value = if (date.isBlank()) null else Logic.call("collect/serviceDay", "serviceTypeOf", date)
}.value

/** 1日運用予測（運用表優先）（Web: forecast.plan.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanForecastScreen() {
    val sources = rememberLoad("plan-sources") { Api.rpc("forecast/plan-fns#listPlanSources", jsonOf()) }
    var vehicle by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(jstTodayKey()) }
    val (st, launch) = rememberRunner("予測できませんでした")
    LaunchedEffect(sources.data) { if (vehicle.isBlank()) sources.data.items("vehicles").firstOrNull()?.str("vehicle")?.let { vehicle = it } }
    fun run() = launch { Api.rpc("forecast/plan-fns#getPlanForecast", jsonOf("vehicle" to vehicle, "date" to date)) }
    val weights = rememberPlanWeights()
    val svc = rememberServiceType(date)
    val result = st.result
    val decision = result?.objAt("decision"); val plan = result?.objAt("plan"); val old = result?.objAt("old")
    val mode = decision.str("mode").orEmpty()
    val chosen = when (mode) { "plan" -> plan.items("items").size; "legacy" -> old.items("items").size; else -> 0 }
    val targetType = result.str("target.serviceType") ?: svc.str("type").orEmpty()
    val targetLabel = result.str("target.serviceLabel") ?: svc.str("label").orEmpty()
    val dates = sources.data.items("dates")
    ScreenList {
        item { Heading("1日運用予測（運用表優先）", "まず、スプレッドシートに保存された過去の運用表から、その車両の1日全体の運用パターンが似ている日を探して予測します。十分な類似運用が見つからない場合だけ、従来の統計的アルゴリズム（運行記録ベース）へ自動で切り替えます。") }
        item { ForecastNav("/forecast/plan") }
        if (sources.data?.bool("configured") == false) item { Note("スプレッドシートが設定されていません。設定画面で読み取り対象のスプレッドシートを指定してください（未設定の間は統計アルゴリズムで予測します）。", tone = Brand.Warning) }
        item {
            Panel {
                SelectField(listOf("" to "車両を選択") + sources.data.items("vehicles").map { val v = it.str("vehicle").orEmpty(); v to "$v（運用表 ${it.int("days") ?: 0}枚・最終 ${it.str("lastDate") ?: "—"}）" }, vehicle, { vehicle = it }, label = "車両", modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Muted("予測対象日", 12); DateField(date, { date = it })
                    SmallButton(if (st.loading) "予測中…" else "予測する", { run() }, enabled = !st.loading && vehicle.isNotBlank() && date.isNotBlank(), icon = Icons.Outlined.AutoAwesome, primary = true)
                }
                val saved = dates.take(4)
                Muted("保存済みの運用表と運行記録だけを読みます（奈良交通APIへの追加アクセスはありません）。" + if (saved.isEmpty()) "運用表がまだ確認できていません" else
                    "読み込み対象：" + saved.joinToString(" / ") { d -> "${d.str("sheet")?.ifBlank { null } ?: "${d.str("date")}運用表"}（${d.str("serviceLabel")?.ifBlank { null } ?: "区分不明"}）" } + if (dates.size > saved.size) " ほか" else "", 10)
            }
        }
        if (sources.data?.bool("configured") != false && dates.isNotEmpty()) item { PlanSheetScope(dates, targetType, date) }
        if (sources.loading && sources.data == null) item { Muted("運用表を読み込んでいます…", 12) }
        sources.error?.let { item { ErrorText(it) } }
        st.error?.let { item { ErrorText(it) } }
        if (result != null) {
            item {
                KpiRow(
                    Triple("予測方式", decision.str("shortLabel") ?: "—", MODE_TONE[mode]),
                    Triple("予測対象日", plan.str("targetDate") ?: date, null),
                    Triple("予測した運行", "$chosen", if (mode == "unavailable") Brand.MutedFg else null),
                    if (mode == "legacy") Triple("根拠に使った履歴", "${old.int("dataDays") ?: 0}", INFO) else Triple("平均類似度", plan.dbl("score")?.let { fmtNum(it) } ?: "—", INFO),
                    units = listOf(null, null, "件", if (mode == "legacy") "日" else null),
                )
                val hint3 = when (mode) { "legacy" -> "時間帯（朝〜夜）ごとに1件を集計"; "plan" -> "基準日 ${plan.str("referenceDate") ?: "—"} の運用（${plan.int("referenceRunCount") ?: 0}件）"; else -> "根拠が足りないため作成していません" }
                Muted("${decision.str("label").orEmpty()} ／ ${result.str("targetService.label").orEmpty()}${result.str("targetService.detail")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""} ／ $hint3" +
                    if (mode == "legacy") " ／ 運行記録 ${old.int("runsUsed") ?: 0} 件" else " ／ 1日のパターンの近さ。的中率ではありません", 10)
            }
            if (decision != null) item { PlanModeNotice(decision, plan, old) }
            item {
                val tgt = if (result.bool("target.saved") == true) "${result.str("target.sheet")}（${result.str("target.date")}・${result.str("target.serviceLabel")}）"
                    else "${result.str("target.date") ?: date} の運用表（${targetLabel.ifBlank { "—" }}）はまだ未作成のため、日付と運行区分で特定"
                Muted("予測対象の運用表：$tgt ／ 根拠に使える運用表：同じ運行区分（${targetLabel.ifBlank { "—" }}）の ${result.int("sources.sameServiceCount") ?: 0} 枚のみ（読み込み ${result.int("sources.sheetCount") ?: 0} 枚・別区分 ${result.int("sources.excludedCount") ?: 0} 枚は除外）", 10)
            }
            if (mode == "plan" && plan != null) {
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { ConfidenceBadge(plan.str("confidence")); Text(plan.str("confidenceReason").orEmpty(), fontSize = 12.sp, modifier = Modifier.weight(1f)) }
                        val m = plan.objAt("metrics")
                        Muted("読んだ運用表 ${m.int("sheetDays") ?: 0} 日　同じ運行区分 ${m.int("sameTypeDays") ?: 0} 日　根拠に使った日 ${m.int("usableDays") ?: 0} 日　運行数 ${m.str("runCountRange") ?: "—"}　運行数が基準日と同じ割合 ${pct(m.dbl("runStability"))}　項目の一致度 ${pct(m.dbl("itemCoverage"))}　時刻の幅（中央値）${m.dbl("timeSpread")?.let { fmtNum(it) } ?: "—"} 分", 10)
                        plan.list("basis").forEach { Muted("・${it.text}", 11) }
                        plan.items("missing").take(4).forEach { Muted("※ ${it.str("reason").orEmpty()}", 10) }
                    }
                }
                item { PlanDayTimeline(plan) }
            }
            if (mode == "legacy" && old != null) {
                item { ForecastTimeline(old, "統計アルゴリズムで予測した時間帯別の運用（朝 → 夜）") }
                item {
                    Panel(title = "統計アルゴリズムの根拠") {
                        val m = old.objAt("metrics")
                        Muted("同じ運行区分の履歴 ${old.int("dataDays") ?: 0} 日（全履歴 ${m.int("totalDays") ?: old.int("dataDays") ?: 0} 日）　類似日として採用 ${m.int("similarDays") ?: 0} 日　使用した運行記録 ${old.int("runsUsed") ?: 0} 件　時刻の幅（中央値）${m.dbl("timeSpread")?.let { fmtNum(it) } ?: "—"} 分　運行順序の安定 ${pct(m.dbl("topPatternShare"))}", 11)
                        Muted("${old.str("confidenceReason").orEmpty()}${if (old.bool("truncated") == true) "（記録が多いため、直近の記録に絞って集計しています）" else ""}", 10)
                    }
                }
            }
            if (mode == "unavailable") item { Note("参考データ不足のため予測不能\n運用表にも運行記録にも、この車両の1日運用を組み立てられるだけのデータがありません。データが増えると自動的に予測されます。", tone = Brand.Warning) }
            result.objAt("actual")?.objAt("comparison")?.let { cmp ->
                item {
                    Panel(title = "対象日の運用表との照合", subtitle = "${result.str("actual.date")} の運用表・${result.int("actual.runCount") ?: 0} 件／照合対象：${decision.str("shortLabel").orEmpty()}") {
                        PlanDiffTable(cmp, "${result.str("actual.date")} の運用表と予測の違い")
                    }
                }
            }
            if (mode == "plan") item { PlanSimilarList(plan.items("similarDays"), weights) }
            if (mode == "legacy" && plan.items("similarDays").isNotEmpty()) item { PlanSimilarList(plan.items("similarDays"), weights, "運用表の類似候補（参考・採用していません）") }
            if (mode == "plan") item { PlanOldCompare(old) }
            item {
                Panel(title = "比較に使っている要素（運用表方式・v${weights.str("version") ?: ""}）") {
                    weights?.objAt("weights")?.entries?.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth()) { Text(weights.str("labels.$k") ?: k, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${v.text}%", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                    Muted("${weights.str("note").orEmpty()}${plan.str("note")?.let { " $it" } ?: ""}${plan.str("comparisonBasis")?.let { " $it。" } ?: ""}", 10)
                    Muted("判定順序：① 過去の1日単位の運用表 → ② 従来の統計的予測アルゴリズム → ③ 予測不能。運用表に記録がない場合は「運休・運行なし」とみなさず、統計アルゴリズムへ切り替えます。", 10)
                    val cols = sources.data.list("structure.columns").joinToString("・") { it.text }
                    Muted("運用表は1日1枚の独立したデータとして扱い（識別は日付とシート名）、同じ運行区分（平日／土曜／日祝）の運用表だけを比較します。別の運用表の運行を同じ日の運用として結合したり、データ不足を別の運用表で補完したりはしません。参考 ${result.int("sources.sheetCount") ?: 0} 枚の運用表を読み込みました。" +
                        if (cols.isNotBlank()) "シートの列は $cols のみで、営業所・車庫の列はありません。" else "", 10)
                }
            }
        }
        if (result == null && !st.loading && !sources.loading) item { Muted("車両と予測対象日を選んで「予測する」を押してください。予測は、その日より前に保存された運用表と運行記録だけを使います。", 12) }
    }
}

@Composable
private fun PlanModeNotice(decision: JsonElement, plan: JsonElement?, legacy: JsonElement?) {
    val mode = decision.str("mode").orEmpty()
    val tone = MODE_TONE[mode] ?: Brand.Warning
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(decision.str("label").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tone, modifier = Modifier.weight(1f))
            Tag(STAGE[mode] ?: "判定不能", tone)
        }
        Text(decision.str("message").orEmpty(), fontSize = 12.sp)
        when (mode) {
            "plan" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfidenceBadge(plan.str("confidence")); Muted("基準日 ${plan.str("referenceDate") ?: "—"}（類似度 ${plan.dbl("score")?.let { fmtNum(it) } ?: "—"}）")
                }
                Muted("根拠 ${plan.int("metrics.usableDays") ?: 0} 日・基準日 ${plan.int("referenceRunCount") ?: 0} 件 ／ 判定：運行順序・系統行先・時刻の近さ・運行間隔が類似", 10)
            }
            "legacy" -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfidenceBadge(legacy.str("confidence")); Muted("根拠 ${legacy.int("dataDays") ?: 0} 日・記録 ${legacy.int("runsUsed") ?: 0} 件")
                }
                Muted("同じ運行区分 ${legacy.int("metrics.categoryDays") ?: 0} 日・類似日 ${legacy.int("metrics.similarDays") ?: 0} 日 ／ 判定：同一車両の履歴・曜日区分・運行順序・時刻・系統行先・直近傾向", 10)
                decision.str("plan.reason")?.let { Muted("運用表を使わなかった理由：$it", 11) }
            }
            else -> {
                Muted("運用表：${decision.str("plan.reasonDetail") ?: decision.str("plan.reason") ?: "対象車両の運用パターンがありません"}", 11)
                Muted("統計：${legacy.str("confidenceReason") ?: legacy.str("error") ?: "運行記録が2日分に満たない状態です"}", 11)
            }
        }
        if (decision.str("code") == "plan_low_confidence") Muted("統計アルゴリズムでも十分なデータがないため、運用表の予測を低い信頼度で表示しています。実際の運用と異なる場合があります。", 10)
    }
}

@Composable
private fun PlanSheetScope(sheets: List<JsonElement>, targetType: String, targetDate: String) {
    val same = sheets.count { it.str("serviceType") == targetType }
    Panel(title = "読み込み対象の運用表（日付ごとに独立）", subtitle = "対象日 ${targetDate.ifBlank { "—" }} と同じ運行区分 $same 枚／別区分 ${sheets.size - same} 枚（予測では使いません）") {
        DataTable(
            listOf(Col("運用表（シート）", 140.dp), Col("日付", 90.dp), Col("運行区分", 64.dp), Col("作成日時", 120.dp), Col("車両", 48.dp, true), Col("運行", 48.dp, true), Col("扱い", 220.dp)),
            sheets.map { s ->
                val ok = s.str("serviceType") == targetType
                val dup = s.list("duplicateSheets").map { it.text }
                listOf(s.str("sheet").orEmpty(), s.str("date").orEmpty(), s.str("serviceLabel")?.ifBlank { null } ?: "—", s.str("builtAt")?.ifBlank { null } ?: "—", s.int("vehicles")?.toString() ?: "—", s.int("runs")?.toString() ?: "—",
                    (if (ok) "採用（同区分）" else "参考外（別区分）") + if (dup.isNotEmpty()) " ／ 同日内の別シート（${dup.joinToString(" / ")}）は結合しません" else "")
            },
        )
        Muted("同じ車両番号でも、別の運用表の記録は1つの運用として結合しません。別の運行区分の運用表は類似運用の検索にも使わず、データ不足を理由に別の運用表から運行を補完することもありません（運用表の識別は日付とシート名で行っています）。", 10)
    }
}

private fun kindTone(kind: String?): Color = when { kind == null -> Brand.MutedFg; "回送" in kind -> Brand.MutedFg; "待機" in kind -> Brand.Warning; else -> Brand.Orange }

@Composable
private fun PlanDayTimeline(plan: JsonElement) {
    val items = plan.items("items")
    Panel(title = "予測された1日の運用（始発 → 最終）", action = { Muted("基準日 ${plan.str("referenceDate") ?: "—"}・予測 ${items.size} 件  ") }) {
        if (items.isEmpty()) Muted("比較できる運用表が足りないため、1日の運用を組み立てられませんでした。", 12)
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 6.dp)) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 11.sp, color = Brand.MutedFg)
                    Text(item.str("timeText").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Muted("（${item.str("rangeText").orEmpty()}）", 10)
                    Tag(item.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                    Text("${item.str("from")?.ifBlank { null } ?: "始発不明"} → ${item.str("to")?.ifBlank { null } ?: "終点不明"}", fontSize = 12.sp)
                    item.str("kind")?.takeIf { it.isNotBlank() }?.let { Tag(it, kindTone(it)) }
                }
                Muted("根拠 ${item.int("supportDays") ?: 0}/${item.int("usableDays") ?: 0} 日${item.str("gapText")?.takeIf { it.isNotBlank() }?.let { "　⏱ 前の運行から $it" } ?: ""}", 10)
                Muted("実際の記録：" + item.items("sources").take(3).joinToString(" / ") { "${it.str("date").orEmpty().drop(5)} ${it.str("timeText").orEmpty()}" }.ifBlank { "—" }, 10)
                val alts = item.items("alternatives")
                if (alts.isNotEmpty()) Muted("他候補：" + alts.joinToString(" / ") { a -> "${a.str("route")?.ifBlank { null } ?: "系統不明"} ${a.str("to").orEmpty()}（${a.int("days") ?: 0}日・${a.str("timeText").orEmpty()}）" }, 10)
                if (item.bool("lowSupport") == true) Text("この順番の運行は類似日の一部にしか無いため、参考度が低い項目です（一致 ${pct(item.dbl("coverage"))}）。", fontSize = 10.sp, color = Brand.Warning)
            }
        }
    }
}

@Composable
private fun PlanSimilarList(days: List<JsonElement>, weights: JsonElement?, title: String = "類似運用日と、その日の運用表との比較") {
    var open by remember(days) { mutableStateOf(days.firstOrNull()?.str("date")) }
    if (days.isEmpty()) { Panel(title = "類似運用日と比較") { Muted("この車両の運用表が2日分未満のため、類似運用日を特定できませんでした。", 12) }; return }
    val labels = weights?.objAt("labels")?.obj?.entries?.map { it.key to it.value.text }.orEmpty()
    Panel(title = title, subtitle = "類似度はパターンの近さで、的中率ではありません") {
        days.forEachIndexed { i, day ->
            if (i > 0) RowDivider()
            val date = day.str("date").orEmpty(); val expanded = open == date
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.fillMaxWidth().clickable { open = if (expanded) null else date }) {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text("${if (expanded) "▾" else "▸"} $date", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Muted(day.str("serviceLabel").orEmpty())
                        Muted("${day.int("runCount") ?: 0}件・${day.str("firstText").orEmpty()}〜${day.str("lastText").orEmpty()}", 10)
                        if (day.bool("isReference") == true) Tag("基準日", Brand.Success)
                        if (day.bool("used") == false) Tag("参考外", Brand.MutedFg)
                        Tag("類似 ${day.dbl("score")?.let { fmtNum(it) } ?: "—"}", INFO)
                    }
                    Muted(day.str("patternText").orEmpty(), 10)
                }
                if (expanded) {
                    Muted(labels.joinToString("　") { (k, l) -> "$l ${pct(day.dbl("parts.$k"))}" } + "　一致した順序 ${day.int("matched.order") ?: 0} 件・時刻一致 ${day.int("matched.times") ?: 0} 件", 10)
                    day.items("sequence").forEach { s ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                            Text("${s.int("order") ?: 0}", fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.width(18.dp))
                            Text(s.str("timeText").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Tag(s.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                            Text("${s.str("from")?.ifBlank { null } ?: "始発不明"} → ${s.str("to")?.ifBlank { null } ?: "終点不明"}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                            s.str("kind")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                        }
                    }
                    day.objAt("comparison")?.let { PlanDiffTable(it, "$date の運用表と予測の違い") }
                }
            }
        }
    }
}

private fun segText(s: JsonElement?): String {
    val from = s.str("from").orEmpty(); val to = s.str("to").orEmpty()
    return "${s.str("timeText").orEmpty()} ${s.str("route")?.ifBlank { null } ?: "系統不明"} $from${if (from.isNotBlank() || to.isNotBlank()) " → " else ""}$to"
}

/** 予測と運用表の照合表（Web: components/forecast/PlanDiffTable.jsx） */
@Composable
fun PlanDiffTable(comparison: JsonElement, title: String) {
    val rows = comparison.items("rows"); val extras = comparison.items("extras"); val t = comparison.objAt("totals")
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Muted("一致 ${t.int("match") ?: 0}・部分 ${t.int("partial") ?: 0}・不一致 ${t.int("mismatch") ?: 0}・運用表のみ ${t.int("extra") ?: 0}${t.dbl("timeError")?.let { "・時刻差の中央値 ${fmtNum(it)}分" } ?: ""}", 10)
        rows.forEachIndexed { i, row ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.width(20.dp))
                    Text("予測 ${segText(row.objAt("predicted"))}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    VerdictBadge(row.str("status"))
                }
                val actual = row.objAt("actual")
                val diff = row.dbl("timeDiff")?.let { d -> "${if (d > 0) "+" else ""}${fmtNum(d)}分" } ?: "—"
                Muted("運用表 ${if (actual != null) segText(actual) else "同じ系統・行先の運行なし"}　差 $diff", 11)
                row.str("reason")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
            }
        }
        if (extras.isNotEmpty()) Muted("その日の運用表にだけある運行（予測外）: " + extras.joinToString(" / ") { "${it.str("timeText").orEmpty()} ${it.str("route")?.ifBlank { null } ?: "系統不明"} ${it.str("to").orEmpty()}" }, 10)
    }
}

@Composable
private fun PlanOldCompare(old: JsonElement?) {
    Panel(title = "従来方式との比較") {
        if (old == null || old is JsonNull) { Muted("この車両・日付の運行記録（車両の観測履歴）が無いため、従来方式の予測はありません。", 12); return@Panel }
        Muted(old.str("label").orEmpty())
        old.str("error")?.let { Text(it, fontSize = 12.sp, color = Brand.Warning); return@Panel }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfidenceBadge(old.str("confidence")); Muted("根拠 ${old.int("dataDays") ?: 0} 日・記録 ${old.int("runsUsed") ?: 0} 件　参考スコア ${old.dbl("score")?.let { fmtNum(it) } ?: "—"}")
        }
        Muted("従来方式は時間帯（朝・午前・昼・午後・夕方・夜）ごとに1件を集計するため、1日の運行順序そのものは表現しません。新方式は運用表の運行順序をそのまま比較します。", 10)
        if (old.bool("available") == true) old.items("items").forEach { it ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                Text(it.str("label").orEmpty(), fontSize = 11.sp, modifier = Modifier.width(36.dp))
                Text(it.str("timeText").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Tag(it.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                Text(it.str("destination")?.ifBlank { null } ?: "行先不明", fontSize = 12.sp)
            }
        } else Muted("従来方式では予測できませんでした（${old.str("confidenceReason") ?: "根拠不足"}）。", 11)
        old.objAt("comparison")?.let { PlanDiffTable(it, "従来方式の予測と当日の運用表") }
    }
}
