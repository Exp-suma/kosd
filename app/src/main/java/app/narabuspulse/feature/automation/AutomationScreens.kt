package app.narabuspulse.feature.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Json
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.core.toJson
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId

private val TABS = listOf("/automation" to "ワークフロー", "/automation/reports" to "日報・週報・月報", "/automation/monitor" to "取得監視・データ保全")
private val ACTIONS = listOf("daily_report" to "日報を生成", "weekly_report" to "週報を生成", "monthly_report" to "月報を生成", "notify" to "通知（実行履歴に記録）", "data_check" to "データ取得監視を実行", "weather_refresh" to "気象データを更新", "backup_check" to "バックアップ検査")
private val TRIGGERS = listOf("daily" to "毎日 指定時刻", "weekly" to "毎週 指定曜日・時刻", "snow_forecast" to "指定市町村の降雪予報", "stale_data" to "データが長時間未更新", "vehicle_running" to "特定車両が運行中", "route_running" to "特定系統が運行中", "shoot_window" to "指定地点の撮影適性がしきい値以上")
private val PURPOSES = listOf("normal" to "通常のバス撮影", "front" to "順光撮影", "back" to "逆光撮影", "night" to "夜間撮影", "rain" to "雨天撮影", "snow" to "雪景色撮影", "pan" to "流し撮り")
private val KINDS = listOf("daily" to "日報", "weekly" to "週報", "monthly" to "月報")
private fun label(list: List<Pair<String, String>>, v: String?) = list.firstOrNull { it.first == v }?.second ?: v.orEmpty()
private fun today() = LocalDate.now(ZoneId.of("Asia/Tokyo"))

/** 文字列で保存された JSON（または既にオブジェクト）を読む。 */
private fun parseField(e: JsonElement?): JsonElement? = when (e) {
    is JsonObject, is JsonArray -> e
    is JsonPrimitive -> runCatching { Json.parse(e.content) }.getOrNull()
    else -> null
}
private fun actionsOf(rule: JsonElement) = (parseField((rule as? JsonObject)?.get("actions")) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content ?: it.str("type") }
private fun conditionOf(rule: JsonElement?) = parseField((rule as? JsonObject)?.get("condition"))

private fun describeCondition(rule: JsonElement): String {
    val c = conditionOf(rule)
    return when (rule.str("trigger_type")) {
        "snow_forecast" -> "市町村 ${c.str("code") ?: "未設定"} / ${c.str("thresholdCm") ?: "1"}cm以上"
        "stale_data" -> "${c.str("minutes") ?: "180"}分以上未更新"
        "vehicle_running" -> "車両 ${c.str("vehicle")?.ifBlank { null } ?: "未設定"}"
        "route_running" -> "系統 ${c.str("route")?.ifBlank { null } ?: "未設定"}"
        "shoot_window" -> "地点 ${c.str("lat") ?: "未設定"},${c.str("lon") ?: "未設定"} / 方位 ${c.str("heading") ?: "180"}° / スコア ${c.str("minScore") ?: "65"} 以上"
        else -> ""
    }
}

@Composable
private fun AutomationScaffold(current: String, title: String, description: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    ScreenList {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChipRow(TABS, current, { if (it != current) nav.go(it) }); Heading(title, description) } }
        content()
    }
}

@Composable private fun Note(text: String, danger: Boolean) = Text(text, fontSize = 11.sp, color = if (danger) Brand.Destructive else Brand.Success)

private fun CoroutineScope.guarded(setBusy: (String) -> Unit, key: String, onError: (String) -> Unit, fallback: String, block: suspend () -> Unit) = launch {
    setBusy(key)
    try { block() } catch (e: Throwable) { if (e is CancellationException) throw e; onError(e.userMessage().ifBlank { fallback }) } finally { setBusy("") }
}

/** 自動化ワークフロー（Web: automation.index.jsx + RuleForm.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationScreen() {
    val scope = rememberCoroutineScope(); val confirm = rememberConfirmer()
    val load = rememberLoad { Api.rpc("automation/server-fns#getAutomationOverview", jsonOf()) }
    var creating by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }; var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val data = load.data; val isAdmin = data.bool("isAdmin") == true
    fun fail(t: String) { message = t to true }
    fun execute(id: String?) = scope.guarded({ busy = it }, id ?: "all", ::fail, "実行できませんでした") {
        message = null
        val r = Api.rpc("automation/server-fns#runAutomationNow", if (id != null) jsonOf("id" to id) else jsonOf())
        val detail = r.list("result").takeIf { it.isNotEmpty() }?.joinToString(" / ") { it.text } ?: r.str("result") ?: r.str("error").orEmpty()
        message = if (id != null) "${when (r.str("status")) { "success" -> "実行しました"; "skipped" -> "条件を満たしませんでした"; else -> "実行に失敗しました" }}: $detail" to (r.str("status") == "failed")
        else "実行 ${r.int("ran") ?: 0}件 / スキップ ${r.int("skipped") ?: 0}件 / 失敗 ${r.int("failed") ?: 0}件" to false
        load.reload()
    }
    AutomationScaffold("/automation", "自動化ワークフロー", "条件と処理を組み合わせて、日報の生成・通知・取得監視などを自動で実行します。") {
        if (isAdmin) item { SmallButton(if (busy == "all") "実行中…" else "条件を満たすものを今すぐ実行", { execute(null) }, enabled = busy != "all", icon = Icons.Outlined.PlayArrow, primary = true) }
        message?.let { m -> item { Note(m.first, m.second) } }
        if (load.loading && data == null) item { Muted("読み込んでいます…", 12) }
        load.error?.let { item { Note("⚠ $it", true) } }
        if (data != null) {
            if (isAdmin) item { if (creating) RuleForm(null, { load.reload() }) { creating = false } else SmallButton("ルールを作成", { creating = true }, icon = Icons.Outlined.Add) }
            else item { Muted("ルールの作成・変更・削除は管理者のみ可能です。実行履歴はすべての利用者が確認できます。", 11) }
            val rules = data.items("rules")
            if (rules.isEmpty()) item { Panel { Muted("ルールがありません。作成すると定時実行や条件実行ができます。", 12) } }
            rules.forEach { rule ->
                val id = rule.str("id").orEmpty()
                item(key = "rule-$id") {
                    if (editing == id) RuleForm(rule, { load.reload() }) { editing = null }
                    else Panel {
                        val enabled = rule.bool("enabled") != false
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(if (enabled) "●" else "○", fontSize = 10.sp, color = if (enabled) Brand.Success else Brand.MutedFg)
                            Text(rule.str("name").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Tag("${label(TRIGGERS, rule.str("trigger_type"))}${rule.str("run_time")?.let { " $it" } ?: ""}", Brand.MutedFg)
                        }
                        describeCondition(rule).takeIf { it.isNotBlank() }?.let { Muted(it, 11) }
                        Muted("${rule.long("last_run_at")?.let { "最終 ${BusFormat.dateTime(it)}" } ?: rule.str("last_run_at")?.let { "最終 $it" } ?: "未実行"}${rule.str("last_status")?.let { " / $it" } ?: ""}", 10)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actionsOf(rule).forEach { Tag(label(ACTIONS, it)) }; if (rule.bool("notify_email") == true) Tag("メール通知", Brand.Warning) }
                        rule.str("last_error")?.takeIf { it.isNotBlank() }?.let { Note("エラー: $it", true) }
                        if (isAdmin) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmallButton("今すぐ実行", { execute(id) }, enabled = busy != id, icon = Icons.Outlined.PlayArrow)
                            SmallButton(if (enabled) "無効にする" else "有効にする", {
                                scope.guarded({ busy = it }, id, ::fail, "変更できませんでした") {
                                    Api.rpc("automation/server-fns#saveAutomationRule", jsonOf("id" to id, "name" to rule.str("name"), "trigger_type" to rule.str("trigger_type"), "run_time" to rule.str("run_time")?.ifBlank { null }, "weekday" to rule.int("weekday"),
                                        "condition" to (conditionOf(rule)?.toString() ?: "{}"), "actions" to JsonArray(actionsOf(rule).map { jsonOf("type" to it) }).toString(), "notify_email" to (rule.bool("notify_email") == true), "enabled" to !enabled))
                                    load.reload()
                                }
                            }, enabled = busy != id, icon = Icons.Outlined.PowerSettingsNew)
                            SmallButton("編集", { editing = id })
                            SmallButton("削除", { confirm.ask("「${rule.str("name")}」を削除します。よろしいですか？") { scope.guarded({ busy = it }, id, ::fail, "削除できませんでした") { Api.rpc("automation/server-fns#deleteAutomationRule", jsonOf("id" to id)); load.reload() } } }, enabled = busy != id, icon = Icons.Outlined.Delete)
                        }
                    }
                }
            }
            item {
                Panel(title = "実行履歴（直近30件）") {
                    val logs = data.items("logs")
                    if (logs.isEmpty()) Muted("まだ実行履歴がありません。", 11)
                    logs.forEach { log ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Tag(log.str("status").orEmpty(), when (log.str("status")) { "success" -> Brand.Success; "failed" -> Brand.Destructive; else -> Brand.MutedFg })
                                Text(log.str("rule_name")?.ifBlank { null } ?: "（手動）", fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(log.str("trigger").orEmpty(), 10); Muted(log.long("run_at")?.let { BusFormat.dateTime(it) } ?: log.str("run_at").orEmpty(), 10)
                            }
                            Muted(log.str("result")?.ifBlank { null } ?: log.str("error")?.ifBlank { null } ?: "—", 10)
                        }
                    }
                }
            }
            item { Panel(title = "画面を閉じている間の実行") { Muted("自動実行は /api/automation/run を外部スケジューラ（GASの時間主導トリガー等）から POST すると動きます。COLLECT_TOKEN を設定している場合は x-collect-token ヘッダーが必要です。同じ実行枠は二重に実行されません（実行履歴の枠キーで判定）。", 11) } }
        }
    }
}

@Composable private fun F(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, placeholder: String = "", onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 10.sp) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleForm(rule: JsonElement?, onSaved: () -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope(); val c = remember(rule) { conditionOf(rule) }
    var name by remember { mutableStateOf(rule.str("name").orEmpty()) }; var trigger by remember { mutableStateOf(rule.str("trigger_type") ?: "daily") }
    var runTime by remember { mutableStateOf(rule.str("run_time")?.ifBlank { null } ?: "07:00") }; var weekday by remember { mutableStateOf((rule.int("weekday") ?: 1).toString()) }
    var email by remember { mutableStateOf(rule.bool("notify_email") == true) }
    var actions by remember { mutableStateOf(rule?.let(::actionsOf)?.takeIf { it.isNotEmpty() } ?: listOf("daily_report", "notify")) }
    var cond by remember { mutableStateOf(mapOf("code" to (c.str("code") ?: "tenkawa"), "thresholdCm" to (c.str("thresholdCm") ?: "1"), "minutes" to (c.str("minutes") ?: "180"), "vehicle" to c.str("vehicle").orEmpty(), "route" to c.str("route").orEmpty(),
        "lat" to c.str("lat").orEmpty(), "lon" to c.str("lon").orEmpty(), "heading" to (c.str("heading") ?: "180"), "purpose" to (c.str("purpose") ?: "normal"), "minScore" to (c.str("minScore") ?: "65"))) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    fun set(k: String, v: String) { cond = cond + (k to v) }
    fun n(k: String, d: Double) = cond[k]?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: d
    Panel(title = if (rule != null) "ルールを編集" else "新しいルール") {
        F("ルール名", name, placeholder = "毎朝の日報") { name = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SelectField(TRIGGERS, trigger, { trigger = it }, label = "実行条件")
            if (trigger == "daily" || trigger == "weekly") TimeField(runTime, { runTime = it }, label = "実行時刻（JST）")
            if (trigger == "weekly") SelectField("日月火水木金土".mapIndexed { i, w -> i.toString() to "${w}曜日" }, weekday, { weekday = it }, label = "曜日")
            if (trigger == "shoot_window") SelectField(PURPOSES, cond["purpose"].orEmpty(), { set("purpose", it) }, label = "撮影の目的")
        }
        when (trigger) {
            "snow_forecast" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("市町村コード", cond["code"].orEmpty(), Modifier.weight(1f), placeholder = "tenkawa") { set("code", it) }; F("降雪しきい値（cm/48h）", cond["thresholdCm"].orEmpty(), Modifier.weight(1f), true) { set("thresholdCm", it) } }
            "stale_data" -> F("未更新とみなす分数", cond["minutes"].orEmpty(), number = true) { set("minutes", it) }
            "vehicle_running" -> F("車両番号（車両名）", cond["vehicle"].orEmpty(), placeholder = "奈良200か115") { set("vehicle", it) }
            "route_running" -> F("系統", cond["route"].orEmpty(), placeholder = "1") { set("route", it) }
            "shoot_window" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("緯度", cond["lat"].orEmpty(), Modifier.weight(1f), true, "34.685") { set("lat", it) }; F("経度", cond["lon"].orEmpty(), Modifier.weight(1f), true, "135.805") { set("lon", it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("撮影方位（度・北0）", cond["heading"].orEmpty(), Modifier.weight(1f), true) { set("heading", it) }; F("撮影適性スコアのしきい値", cond["minScore"].orEmpty(), Modifier.weight(1f), true) { set("minScore", it) } }
            }
        }
        Text("実行する処理", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { ACTIONS.forEach { (v, l) -> FilterChip(v in actions, { actions = if (v in actions) actions - v else actions + v }, label = { Text(l, fontSize = 11.sp) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(email, { email = it }); Text("通知をメールでも送る（このルールを作成したユーザー宛・明示的に有効化した場合のみ）", fontSize = 11.sp) }
        if (error.isNotBlank()) Note(error, true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (busy) "保存中…" else "保存", {
                if (name.isBlank()) { error = "ルール名を入力してください"; return@SmallButton }
                if (actions.isEmpty()) { error = "実行する処理を1つ以上選んでください"; return@SmallButton }
                busy = true; error = ""
                val body = when (trigger) {
                    "snow_forecast" -> jsonOf("code" to cond["code"], "thresholdCm" to n("thresholdCm", 1.0))
                    "stale_data" -> jsonOf("minutes" to n("minutes", 180.0))
                    "vehicle_running" -> jsonOf("vehicle" to cond["vehicle"]); "route_running" -> jsonOf("route" to cond["route"])
                    "shoot_window" -> jsonOf("lat" to cond["lat"]?.toDoubleOrNull(), "lon" to cond["lon"]?.toDoubleOrNull(), "heading" to n("heading", 180.0), "purpose" to cond["purpose"], "minScore" to n("minScore", 65.0))
                    else -> jsonOf()
                }
                scope.launch {
                    try {
                        Api.rpc("automation/server-fns#saveAutomationRule", jsonOf("id" to rule.str("id"), "name" to name, "trigger_type" to trigger, "run_time" to runTime.takeIf { trigger == "daily" || trigger == "weekly" }, "weekday" to weekday.toIntOrNull().takeIf { trigger == "weekly" },
                            "condition" to body.toString(), "actions" to toJson(actions.map { jsonOf("type" to it, "message" to name.takeIf { _ -> it == "notify" }) }).toString(), "notify_email" to email, "enabled" to (rule == null || rule.bool("enabled") != false)))
                        onSaved(); onCancel()
                    } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
                }
            }, enabled = !busy, icon = Icons.Outlined.Save, primary = true)
            SmallButton("キャンセル", onCancel)
        }
    }
}

/** 日報・週報・月報（Web: automation.reports.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationReportsScreen() {
    val scope = rememberCoroutineScope(); val confirm = rememberConfirmer()
    var kind by remember { mutableStateOf("daily") }; var date by remember { mutableStateOf(today().toString()) }
    var busy by remember { mutableStateOf("") }; var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val load = rememberLoad { Api.rpc("automation/server-fns#listReports", jsonOf("limit" to 40)) }
    fun fail(t: String) { message = t to true }
    AutomationScaffold("/automation/reports", "日報・週報・月報", "運行記録・位置履歴・収集ログ・撮影記録から自動集計します。同じ期間は上書きされ、二重に作られません。") {
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    ChipRow(KINDS, kind, { kind = it }); DateField(date, { date = it }, label = "基準日")
                    SmallButton(if (busy == "generate") "生成中…" else "生成する", {
                        scope.guarded({ busy = it }, "generate", ::fail, "生成できませんでした") { message = null; val r = Api.rpc("automation/server-fns#generateReport", jsonOf("kind" to kind, "date" to date)); message = "${label(KINDS, kind)}を生成しました（${r.str("report.title").orEmpty()}）" to false; load.reload() }
                    }, enabled = busy != "generate", icon = Icons.Outlined.Refresh, primary = true)
                }
                message?.let { Note(it.first, it.second) }
            }
        }
        if (load.loading && load.data == null) item { Muted("読み込んでいます…", 12) }
        load.error?.let { item { Note("⚠ $it", true) } }
        val rows = load.data.items("rows")
        if (load.data != null && rows.isEmpty()) item { Panel { Muted("まだレポートがありません。上のボタンで生成してください。", 12) } }
        rows.forEach { report ->
            item(key = "rep-${report.str("id")}") {
                val stats = parseField((report as? JsonObject)?.get("stats"))
                Panel {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) { Tag(label(KINDS, report.str("kind"))); Text(report.str("title").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    Muted("${report.long("generated_at")?.let { BusFormat.dateTime(it) } ?: report.str("generated_at").orEmpty()} 生成（${if (report.str("trigger") == "manual") "手動" else "自動"}）", 10)
                    Text(report.str("summary").orEmpty(), fontSize = 12.sp, lineHeight = 18.sp)
                    if (stats.str("summary.runs") != null) KpiRow(Triple("運行記録", "${stats.int("summary.runs") ?: 0}件", null), Triple("観測車両", "${stats.int("summary.vehicles") ?: 0}台", null), Triple("系統", "${stats.int("summary.routes") ?: 0}系統", null), Triple("収集成功率", stats.dbl("collection.successRate")?.let { "${stats.str("collection.successRate")}%" } ?: "—", null))
                    report.str("source_note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                    stats.list("notes").forEach { Muted("・${it.text}", 10) }
                    SmallButton("削除", { confirm.ask("このレポートを削除します。よろしいですか？") { scope.guarded({ busy = it }, report.str("id").orEmpty(), ::fail, "削除できませんでした") { Api.rpc("automation/server-fns#deleteReport", jsonOf("id" to report.str("id"))); load.reload() } } }, enabled = busy != report.str("id"), icon = Icons.Outlined.Delete)
                }
            }
        }
    }
}

private val STATUS: Map<String, Pair<String, Color>> = mapOf("ok" to ("正常" to Brand.Success), "warn" to ("注意" to Brand.Warning), "danger" to ("異常" to Brand.Destructive))
private val TARGETS = listOf("runs" to "運行記録", "positions" to "位置履歴", "snapshots" to "位置スナップショット")

/** データ取得監視・データ保全（Web: automation.monitor.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationMonitorScreen() {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val confirm = rememberConfirmer()
    val load = rememberLoad { Api.rpc("automation/server-fns#getDataHealth", jsonOf()) }
    var runDays by remember { mutableStateOf<String?>(null) }; var logDays by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("runs") }; var beforeDate by remember { mutableStateOf(today().minusDays(365).toString()) }
    var previewed by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf("") }; var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val data = load.data; val isAdmin = data.bool("isAdmin") == true
    fun fail(t: String) { message = t to true }
    AutomationScaffold("/automation/monitor", "データ取得監視・データ保全", "収集の鮮度・成功率・整合性を確認し、保存期間の目安とバックアップを管理します。") {
        if (load.loading && data == null) item { Muted("確認しています…", 12) }
        load.error?.let { item { Note("⚠ $it", true) } }
        message?.let { m -> item { Note(m.first, m.second) } }
        if (data != null) {
            item {
                Panel {
                    data.items("checks").forEach { check ->
                        val (l, color) = STATUS[check.str("status")] ?: ("未確認" to Brand.MutedFg)
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Tag(l, color); Text(check.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            Text(check.str("value").orEmpty(), fontSize = 13.sp); Muted(check.str("detail").orEmpty(), 10)
                        }
                    }
                    Muted("${data.str("note").orEmpty()}（確認 ${BusFormat.dateTime(data.long("fetchedAt"))}）", 10)
                }
            }
            item {
                Panel(title = "データ整合性") {
                    val pd = data.int("integrity.positionDuplicates") ?: 0; val rd = data.int("integrity.runDuplicates") ?: 0
                    KpiRow(Triple("重複した位置記録", "${pd}件", if (pd > 0) Brand.Warning else Brand.Success), Triple("重複した運行記録", "${rd}件", if (rd > 0) Brand.Warning else Brand.Success))
                    KpiRow(Triple("検査した位置記録", "${data.int("integrity.positionSample") ?: 0}件", null), Triple("検査した運行記録", "${data.int("integrity.runSample") ?: 0}件", null))
                    Muted("検査対象は ${data.str("integrity.checkedRange").orEmpty()} の記録です（全件ではありません）。重複は「同じ車両・同じ時刻」の組で判定しています。", 10)
                }
            }
            item {
                Panel(title = "保存データ量") {
                    fun inv(k: String) = "${data.str("inventory.$k") ?: "—"}件"
                    KpiRow(Triple("運行記録", inv("runs"), null), Triple("位置履歴", inv("positions"), null), Triple("スナップショット", inv("snapshots"), null))
                    KpiRow(Triple("撮影記録", inv("photos"), null), Triple("登録車両", "${data.int("inventory.enabledVehicles") ?: 0}/${data.int("inventory.vehicles") ?: 0}台", null))
                }
            }
            item {
                val rdv = runDays ?: (data.int("retention.runDays") ?: 365).toString(); val ldv = logDays ?: (data.int("retention.logDays") ?: 180).toString()
                Panel(title = "保存期間の目安") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("運行記録・位置履歴（日）", rdv, Modifier.weight(1f), true) { runDays = it }; F("ログ（日）", ldv, Modifier.weight(1f), true) { logDays = it } }
                    SmallButton("保存する", {
                        scope.guarded({ busy = it }, "retention", ::fail, "保存できませんでした") { message = null; Api.rpc("automation/server-fns#saveRetentionSettings", jsonOf("runDays" to (rdv.toIntOrNull() ?: 365).coerceIn(30, 3650), "logDays" to (ldv.toIntOrNull() ?: 180).coerceIn(7, 3650))); message = "保存期間の目安を保存しました（自動削除は行いません）" to false; load.reload() }
                    }, enabled = busy != "retention" && isAdmin, icon = Icons.Outlined.Save)
                    if (!isAdmin) Muted("変更は管理者のみ可能です。", 10)
                    Muted("この日数は目安です。自動削除は行いません（下の「データ整理」で件数を確認してから実行します）。", 10)
                }
            }
            item {
                Panel(title = "データ整理（削除は手動・要確認）") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        SelectField(TARGETS, target, { target = it; previewed = null }, label = "対象"); DateField(beforeDate, { beforeDate = it; previewed = null }, label = "この日より前")
                        SmallButton("件数を確認", {
                            scope.guarded({ busy = it }, "preview", ::fail, "件数を確認できませんでした") { message = null; val r = Api.rpc("automation/server-fns#previewCleanup", jsonOf("target" to target, "beforeDate" to beforeDate)); previewed = r; message = "${r.str("label")} ${r.str("count") ?: "—"}件が対象です。削除は内容を確認したうえで実行してください。" to false }
                        }, enabled = busy != "preview" && isAdmin)
                        SmallButton("削除する", {
                            val p = previewed ?: return@SmallButton
                            confirm.ask("${p.str("label")} ${p.int("count") ?: 0}件（$beforeDate より前）を削除します。この操作は取り消せません。") {
                                scope.guarded({ busy = it }, "cleanup", ::fail, "削除できませんでした") { message = null; val r = Api.rpc("automation/server-fns#cleanupData", jsonOf("target" to target, "beforeDate" to beforeDate, "confirm" to true, "expected" to (p.int("count") ?: 0))); message = "${r.int("deleted") ?: 0}件を削除しました（実行履歴に記録）" to false; previewed = null; load.reload() }
                            }
                        }, enabled = previewed != null && busy != "cleanup", icon = Icons.Outlined.Delete)
                    }
                    Muted("削除した件数は自動処理履歴に残ります。写真・レポート・設定はこの画面から削除しません。", 10)
                }
            }
            item {
                Panel(title = "バックアップ") {
                    SmallButton(if (busy == "backup") "書き出し中…" else "JSONを書き出す", {
                        scope.guarded({ busy = it }, "backup", ::fail, "書き出せませんでした") {
                            message = null; val r = Api.rpc("automation/server-fns#exportBackupData", jsonOf())
                            val saved = Export.text(context, r.str("filename") ?: "backup.json", "application/json", r.str("json").orEmpty())
                            message = "バックアップを書き出しました（運行${r.int("counts.runs") ?: 0} / 位置${r.int("counts.positions") ?: 0} / 車両${r.int("counts.vehicles") ?: 0}）: $saved" to false
                        }
                    }, enabled = busy != "backup", icon = Icons.Outlined.Download)
                    Muted("運行記録800件・位置履歴800件・車両登録・自動化ルール・レポートを書き出します。写真ファイル本体とAPIキーは含みません。復元は書き出したJSONを確認しながら手動で行ってください（自動復元は未実装）。", 10)
                }
            }
        }
    }
}
