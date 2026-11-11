package app.narabuspulse.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Json
import app.narabuspulse.core.Logic
import app.narabuspulse.core.Net
import app.narabuspulse.core.Session
import app.narabuspulse.core.Telemetry
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.rememberBusData
import app.narabuspulse.feature.bus.rememberVehicleGps
import app.narabuspulse.feature.push.PushStatusCard
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private data class Service(val key: String, val label: String, val purpose: String, val labels: List<String>)
private val SERVICES = listOf(
    Service("narakotsu", "奈良交通 バスロケーション", "停留所一覧・車両位置（route_order API）", emptyList()),
    Service("sheets", "Google Sheets API", "監視シート・運行ログの読み取り", emptyList()),
    Service("jma", "気象庁（降水ナウキャスト）", "雨雲・降雪・積雪タイルの時刻", emptyList()),
    Service("forecast", "Open-Meteo（予報・過去値）", "天気予報・平年値・標高", emptyList()),
    Service("gsi", "国土地理院 標高API", "3Dシミュレーターの地形データ", emptyList()),
    Service("collect", "GPS自動取得（収集API）", "/api/collect からの車両位置保存", emptyList()),
    Service("app", "アプリ内API（server関数）", "画面表示用のデータ取得", listOf(Telemetry.APP)),
    Service("db", "Base44 エンティティ", "保存データの読み書き", listOf("Base44 エンティティ")),
)
private fun statusColor(s: String) = when (s) { "ok" -> Brand.Success; "warn" -> Brand.Warning; "error" -> Brand.Destructive; else -> Brand.MutedFg }
private fun statusLabel(s: String) = when (s) { "ok" -> "正常"; "warn" -> "注意"; "error" -> "異常"; "offline" -> "停止中"; else -> "未確認" }

/** システム状態モニター（Web: settings.monitor.jsx） */
@Composable
fun MonitorSettingsScreen() {
    val scope = rememberCoroutineScope()
    val admin = Session.isAdmin
    val tel by Telemetry.state.collectAsState()
    val plan by Net.plan.collectAsState()
    val resolved by Net.resolved.collectAsState()
    val bus = rememberBusData()
    val gps = rememberVehicleGps(bus.data?.fetchedAt)
    var snapVersion by remember { mutableStateOf(0) }
    val passive = rememberLoad("system-snapshot", snapVersion) { Api.rpc("monitor/server-fns#getSystemSnapshot", JsonNull) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); snapVersion++ } }
    var live by remember { mutableStateOf<JsonElement?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf("") }
    LaunchedEffect(admin) {
        if (admin) runCatching { Api.rpc("monitor/server-fns#probeServices", jsonOf("keys" to listOf("sheets", "jma", "forecast", "gsi"))) }.getOrNull()?.let { live = it }
    }
    val liveMap = live.items("results").associateBy { it.str("key") }
    val collect = passive.data?.objAt("collect")
    data class Row(val s: Service, val status: String, val message: String, val okAt: Long, val errAt: Long, val code: Int, val avgMs: Long?, val rate: Double?, val calls: Int, val lastError: String)
    val rows = SERVICES.map { s ->
        val t = s.labels.mapNotNull { tel.api[it] }
        val calls = t.sumOf { it.calls }; val ok = t.sumOf { it.ok }; val fail = t.sumOf { it.fail }
        var okAt = t.maxOfOrNull { it.lastOkAt } ?: 0; var errAt = t.maxOfOrNull { it.lastErrorAt } ?: 0
        var code = t.mapNotNull { it.lastStatus.takeIf { c -> c > 0 } }.lastOrNull() ?: 0
        val avg = if (calls > 0) t.sumOf { (it.avgMs ?: 0) * it.calls } / calls else null
        var status = "unknown"; var message = ""
        val found = liveMap[s.key]
        if (s.key == "collect" && collect != null) {
            okAt = collect.long("lastOkAt") ?: okAt
            val enabled = collect.bool("enabled") == true; val ls = collect.str("lastStatus")
            message = collect.str("lastError") ?: if (enabled) "自動収集は有効です（${collect.int("intervalMinutes") ?: 0}分間隔）" else "自動収集は無効です（データ収集の画面で有効にできます）"
            status = when { !enabled -> "offline"; ls == "ok" -> "ok"; ls == "partial" -> "warn"; ls != null -> "error"; else -> "unknown" }
        } else if (found != null) {
            status = if (found.bool("ok") == true) "ok" else if (found.bool("configured") == false) "offline" else "error"
            message = found.str("message").orEmpty(); code = found.int("status") ?: code
            if (found.bool("ok") == true) okAt = live.long("at") ?: okAt else errAt = live.long("at") ?: errAt
        } else if (calls > 0) status = if (fail > 0 && fail >= ok) "error" else if (errAt > okAt) "warn" else "ok"
        Row(s, status, message, okAt, errAt, code, avg, if (calls > 0) ok.toDouble() / calls else null, calls, t.mapNotNull { it.lastError.ifBlank { null } }.lastOrNull().orEmpty())
    }
    val maxAge = resolved.int("gpsMaxAgeMin") ?: 30
    val now = System.currentTimeMillis()
    val gpsList = bus.data?.vehicles.orEmpty().map { v -> val at = v.carCode?.let { gps.byCode[it]?.at }; Triple(v.name, at, when { at == null -> "none"; now - at > maxAge * 60_000L -> "stale"; else -> "ok" }) }.sortedBy { it.second ?: 0 }
    val overall = when { rows.any { it.status == "error" } -> "error"; rows.any { it.status == "warn" } || gpsList.any { it.third == "stale" } -> "warn"; rows.any { it.status == "ok" } -> "ok"; else -> "unknown" }
    ScreenList {
        item { SettingsHeader("/settings/monitor") }
        item {
            SettingsCard("システム状態モニター", "API・GPS・DB・通信エラーの実際の記録を1画面にまとめています。数値はこの端末で観測した値と、DBに保存された収集結果だけを使います。", action = {
                Column(horizontalAlignment = Alignment.End) {
                    SmallButton("更新", { snapVersion++ }, icon = Icons.Outlined.Refresh)
                    SmallButton(if (probing) "確認中…" else "接続を確認（実通信）", {
                        probing = true; probeError = ""
                        scope.launch { try { live = Api.rpc("monitor/server-fns#probeServices", JsonNull) } catch (e: Throwable) { if (e is CancellationException) throw e; probeError = e.userMessage().ifBlank { "接続確認を実行できませんでした" } } finally { probing = false } }
                    }, enabled = admin && !probing, icon = Icons.Outlined.Wifi, primary = true, modifier = Modifier.padding(top = 4.dp))
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Tag(statusLabel(overall), statusColor(overall)); Muted("モード：${plan.label}", 11) }
                Muted("最終更新 ${BusFormat.dateTime(live.long("at") ?: passive.data.long("at") ?: tel.updatedAt.takeIf { it > 0 })}（自動更新60秒）", 10)
                if (!admin) Muted("外部APIへの実通信による確認は管理者のみ実行できます。表示中の数値は、この端末で実際に発生した通信の記録です。", 10)
                if (probeError.isNotBlank()) Text(probeError, fontSize = 11.sp, color = Brand.Destructive)
                Muted("「接続を確認（実通信）」は、各APIへ1回ずつ実際に問い合わせます（奈良交通APIへは稼働中の車両1台分だけ）。通常の画面表示では追加のアクセスは行いません。", 10)
            }
        }
        item {
            SettingsCard("API接続状態", "使用中の外部APIとアプリ内APIの状態です。応答時間と成功率は、この端末で観測した直近の通信から計算しています。") {
                rows.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(r.s.label, fontSize = 13.sp, fontWeight = FontWeight.Bold); Muted(r.s.purpose, 10) }; Tag(statusLabel(r.status), statusColor(r.status)) }
                        Muted("最終成功 ${BusFormat.dateTime(r.okAt.takeIf { it > 0 })}　最終エラー ${if (r.errAt > 0) BusFormat.dateTime(r.errAt) else "—"}　HTTP ${if (r.code > 0) r.code else "—"}　応答 ${r.avgMs?.let { "${it}ms" } ?: "—"}　成功率 ${r.rate?.let { "${Math.round(it * 100)}%" } ?: "—"}　呼び出し ${if (r.calls > 0) "${r.calls}回" else "—"}", 10)
                        (r.message.ifBlank { r.lastError }).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = if (r.status == "error") Brand.Destructive else Brand.MutedFg) }
                    }
                }
            }
        }
        item {
            SettingsCard("GPS取得状態", "車両ごとの最終位置更新と、GPS取得の成否です。値が古い車両は「遅延」「未取得」として検出します。") {
                val okC = gpsList.count { it.third == "ok" }; val stale = gpsList.count { it.third == "stale" }; val none = gpsList.count { it.third == "none" }
                val lastOk = gpsList.mapNotNull { it.second }.maxOrNull()
                Muted(lastOk?.let { "最終成功 ${BusFormat.dateTime(it)}" } ?: "まだ取得できていません", 11)
                KeyValue("監視中の車両", "${gpsList.size}台"); KeyValue("取得成功", "${okC}台"); KeyValue("取得失敗", "${none}台"); KeyValue("更新停止・遅延", "${stale}台")
                KeyValue("取得時間", tel.gpsLastMs?.let { "${it}ms" } ?: "—")
                KeyValue("取得の成功率", (tel.gpsOk + tel.gpsFail).takeIf { it > 0 }?.let { "${Math.round(tel.gpsOk * 100.0 / it)}%" } ?: "—")
                (gps.error ?: tel.gpsLastError.ifBlank { null })?.let { Text("⚠ $it", fontSize = 11.sp, color = Brand.Warning) }
                DataTable(listOf(Col("車両", 120.dp), Col("最終位置更新", 120.dp), Col("状態", 60.dp)), gpsList.map { (n, at, s) -> listOf(n, at?.let { BusFormat.dateTime(it) } ?: "記録なし", when (s) { "ok" -> "正常"; "stale" -> "遅延"; else -> "未取得" }) }, boldFirst = false)
                if (stale + none > 0) Muted("「遅延」「未取得」は、GPSの最終更新が設定の保持時間（${maxAge}分）を超えた車両です。収集処理（/api/collect）が動いているか確認してください。", 10)
            }
        }
        item {
            SettingsCard("データベース状態", "エンティティAPIの呼び出し回数と、Base44の上限への接近状況です。") {
                val db = tel.db.values
                val reads = db.sumOf { it.reads }; val writes = db.sumOf { it.writes }; val ok = db.sumOf { it.ok }; val fail = db.sumOf { it.fail }
                KeyValue("entity 呼び出し", "${reads + writes}回"); KeyValue("直近60秒のDBアクセス", "${tel.opsLastMinute}回")
                KeyValue("読み込み", "${reads}回"); KeyValue("書き込み", "${writes}回"); KeyValue("成功", "${ok}回"); KeyValue("失敗", "${fail}回")
                KeyValue("成功率", if (ok + fail > 0) "${Math.round(ok * 100.0 / (ok + fail))}%" else "—")
                val top = db.sortedByDescending { it.calls }.take(5)
                if (top.isNotEmpty()) { Text("エンティティ別の呼び出し回数（上位5件）", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)); top.forEach { Muted("${it.entity}：${it.calls}回（読${it.reads} / 書${it.writes}・${it.avgMs?.let { m -> "${m}ms" } ?: "—"}）", 10) } }
                if (tel.opsLastMinute >= (resolved.int("queuePerMinute") ?: 55) * 0.7) Text("⚠ Base44の呼び出し上限に近づいています。詳細設定でアプリ全体の1分あたりの上限を下げるか、更新間隔を長くしてください。", fontSize = 10.sp, color = Brand.Warning)
                Muted("上限値は Base44 が公表している entity API の回数です。実際の残数は取得できないため、ここではアプリが記録した呼び出し回数との比率を表示しています。", 10)
            }
        }
        item {
            SettingsCard("通信エラー監視", "429・522・500・タイムアウト・JSON解析・DBアクセス・GPS取得の失敗を記録します。", action = { SmallButton("記録をクリア", { Telemetry.reset() }) }) {
                val kinds = tel.errorCounts.entries.sortedByDescending { it.value }
                if (kinds.isEmpty()) Muted("記録されたエラーはありません。", 12)
                else @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { kinds.forEach { (k, c) -> Tag("${Telemetry.KIND_LABEL[k] ?: k}：${c}件", Brand.Destructive) } }
                if (tel.errors.isNotEmpty()) DataTable(listOf(Col("発生時刻", 120.dp), Col("種類", 110.dp), Col("発生元 → 対象", 160.dp), Col("内容", 220.dp)),
                    tel.errors.map { listOf(BusFormat.dateTime(it.at), Telemetry.KIND_LABEL[it.kind] ?: it.kind, "${it.source} → ${it.target.ifBlank { "—" }}", it.message + if (it.status > 0) " (HTTP ${it.status})" else "") }, boldFirst = false)
                Muted("429は「しばらく待ってから再取得」、522・タイムアウトは「回線を確認して再取得」、500系は「時間をおいて再取得」が基本の対処です。同じエラーが続く場合は設定の更新間隔を長くしてください。", 10)
            }
        }
        item {
            SettingsCard("判定できない項目") {
                listOf("Base44の残り呼び出し回数（残数そのもの）は取得できません。公表された上限と、アプリが記録した回数との比率を表示しています。", "外部APIのサーバー側の稼働率・メンテナンス情報は取得していません（実際に問い合わせた結果のみ表示）。", "複数端末の記録は合算していません（この端末で発生した通信の記録です）。").forEach { Muted("・$it", 11) }
            }
        }
    }
}

private fun kb(b: Double?): String = when { b == null -> "—"; b >= 1_048_576 -> "%.1fMB".format(b / 1_048_576); b >= 1024 -> "${Math.round(b / 1024)}KB"; else -> "${b.toLong()}B" }
private val KIND = mapOf("core" to "本データ", "log" to "履歴", "cache" to "一時データ")
private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")

/** データ管理センター（Web: settings.data.jsx） */
@Composable
fun DataSettingsScreen() {
    val admin = Session.isAdmin
    var invVersion by remember { mutableStateOf(0) }
    val inv = rememberLoad("data-inventory", invVersion) { Api.rpc("monitor/server-fns#getDataInventory", JsonNull) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("all") }
    ScreenList {
        item { SettingsHeader("/settings/data") }
        item {
            SettingsCard("🗄 データベースの保存状況", "アプリが使っているエンティティの保存件数・最終更新・用途です。件数は実際の値、容量は推定値です。", action = { SmallButton("更新", { invVersion++ }, enabled = !inv.loading, icon = Icons.Outlined.Refresh) }) {
                Muted("最終取得 ${BusFormat.dateTime(inv.data.long("at"))}${if (inv.error != null) "　取得できませんでした" else ""}", 10)
                val all = inv.data.items("rows")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("エンティティ名・用途で絞り込み", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    SelectField(listOf("all" to "すべて") + KIND.entries.map { it.key to it.value }, kind, { kind = it })
                }
                Muted("${all.count { (it.int("count") ?: 0) > 0 }}種類 / 合計 ${app.narabuspulse.feature.analytics.num(all.sumOf { it.long("count") ?: 0 })}件 / 容量（推定）${kb(all.sumOf { it.dbl("bytes") ?: 0.0 })}", 11)
                val rows = all.filter { (kind == "all" || it.str("kind") == kind) && (query.isBlank() || "${it.str("label")}${it.str("name")}${it.str("purpose")}".contains(query.trim())) }
                fun retention(r: JsonElement) = when (r.str("kind")) { "cache" -> "期限切れで置き換え"; "log" -> "${inv.data.int("retention.logDays") ?: 180}日（目安）"; else -> "${inv.data.int("retention.runDays") ?: 365}日（目安）" }
                DataTable(listOf(Col("種類", 64.dp), Col("エンティティ", 170.dp), Col("保存件数", 72.dp, true), Col("最終更新", 120.dp), Col("24時間の増加", 80.dp, true), Col("容量（推定）", 80.dp, true), Col("用途", 200.dp), Col("保存期間", 120.dp)),
                    rows.map { r -> listOf(KIND[r.str("kind")] ?: "", "${r.str("label")}（${r.str("name")}）", r.long("count")?.let { app.narabuspulse.feature.analytics.num(it) } ?: "—", r.long("lastAt")?.let { BusFormat.dateTime(it) } ?: "—", r.long("newToday")?.let { "+${app.narabuspulse.feature.analytics.num(it)}" } ?: "—", kb(r.dbl("bytes")), r.str("purpose").orEmpty(), retention(r)) }, boldFirst = false)
                if (rows.isEmpty()) Muted(if (inv.data != null) "該当するデータがありません" else "読み込み中…", 11)
                Muted("件数は実際の保存件数です。容量は最新5件の実データから計算した推定値で、正確な使用量ではありません（実測値とは区別しています）。「24時間の増加」は作成日時が直近24時間の件数です。", 10)
                inv.data.list("errors").takeIf { it.isNotEmpty() }?.let { Text("一部の件数を取得できませんでした：${it.take(3).joinToString(" / ") { e -> e.text }}", fontSize = 10.sp, color = Brand.Warning) }
            }
        }
        item { SettingsCard("🗑 古いデータの整理", "データの種類ごとに保存期間を決め、期間を過ぎたデータだけを削除します。削除前に件数と対象期間を確認できます。") { PurgePanel(admin) } }
        item { SettingsCard("📦 バックアップ・復元", "選択したデータをJSONで保存します。復元は既存データを上書きせず、足りない分だけ追加します。") { BackupPanel(admin) } }
    }
}

@Composable
private fun PurgePanel(admin: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val types = produceState(emptyList<JsonElement>()) { value = Logic.constant("monitor/entities", "PURGE_TYPES").items() }.value
    val days = remember { mutableStateMapOf<String, String>() }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var result by remember { mutableStateOf<JsonElement?>(null) }
    var done by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var autoEnabled by remember { mutableStateOf(prefs.getBoolean("auto-purge", false)) }
    var autoLast by remember { mutableStateOf(prefs.getLong("auto-purge-last", 0)) }
    fun dayOf(t: JsonElement) = days[t.str("key")]?.toIntOrNull() ?: if (t.str("kind") == "cache") 7 else 180
    LaunchedEffect(autoEnabled, types, admin) {
        if (!autoEnabled || !admin || types.isEmpty() || System.currentTimeMillis() - autoLast < 86_400_000) return@LaunchedEffect
        val items = types.filter { it.str("kind") == "cache" }.map { jsonOf("key" to it.str("key"), "days" to dayOf(it)) }
        runCatching { Api.rpc("monitor/server-fns#purgeOldData", jsonOf("items" to JsonArray(items), "confirm" to true)) }.getOrNull()?.let { done = it; autoLast = System.currentTimeMillis(); prefs.edit().putLong("auto-purge-last", autoLast).apply() }
    }
    val items = types.filter { selected[it.str("key")] == true }.map { jsonOf("key" to it.str("key"), "days" to dayOf(it)) }
    val total = result.items("results").sumOf { it.long("targets") ?: 0 }
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(autoEnabled, { autoEnabled = it; prefs.edit().putBoolean("auto-purge", it).apply() })
            Column { Text("一時データ（キャッシュ）を自動で整理する", fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("この画面を開いたときに1日1回だけ、期限切れのキャッシュ（経路検索・気象・撮影地シーン・市町村予報）を削除します。運行記録・位置履歴は自動では削除しません。最終実行：${if (autoLast > 0) BusFormat.dateTime(autoLast) else "未実行"}", 10) }
        }
        types.forEach { t ->
            val k = t.str("key").orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Checkbox(selected[k] == true, { selected[k] = it })
                Column(Modifier.weight(1f)) { Text(t.str("label").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("$k・${t.str("field")}・${if (t.str("kind") == "cache") "一時データ" else "履歴"}", 10) }
                OutlinedTextField(days[k] ?: dayOf(t).toString(), { days[k] = it.filter(Char::isDigit).take(4) }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(" 日", fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallButton(if (busy) "確認中…" else "削除対象を確認", {
                busy = true; error = ""; done = null
                scope.launch { try { result = Api.rpc("monitor/server-fns#previewPurge", jsonOf("items" to JsonArray(items))) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "削除対象を確認できませんでした" } } finally { busy = false } }
            }, enabled = items.isNotEmpty() && !busy && admin)
            SmallButton("選択したデータを整理", {
                confirm.ask("古いデータを削除しますか？\n${items.size}種類・合計${total}件を削除します。\nこの操作は元に戻せません。運行記録・位置履歴が含まれる場合は特にご確認ください。") {
                    busy = true; error = ""
                    scope.launch { try { done = Api.rpc("monitor/server-fns#purgeOldData", jsonOf("items" to JsonArray(items), "confirm" to true)); result = null; selected.clear() } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "削除できませんでした" } } finally { busy = false } }
                }
            }, enabled = result != null && total > 0 && !busy && admin, icon = Icons.Outlined.Delete)
        }
        Muted("対象を選択してください（${selected.count { it.value }}種類）", 10)
        if (!admin) Muted("データの整理は管理者のみ実行できます。", 11)
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        result?.let { r ->
            DataTable(listOf(Col("対象", 140.dp), Col("現在の件数", 80.dp, true), Col("削除対象", 80.dp, true), Col("基準日時", 150.dp)), r.items("results").map { listOf(it.str("label").orEmpty(), it.long("total")?.toString() ?: "—", it.long("targets")?.toString() ?: "—", "${BusFormat.dateTime(it.long("cutoff"))} より前") })
            Text("合計 ${total}件が削除対象です。この時点ではまだ削除していません。", fontSize = 11.sp, color = Brand.Warning)
        }
        done?.let { d -> d.items("results").forEach { Text("${it.str("label")}：${it.str("error")?.let { e -> "失敗（$e）" } ?: "${it.long("deleted") ?: 0}件を削除しました"}", fontSize = 11.sp) } }
        Muted("位置履歴・運行記録は重要なデータのため、確認画面を経ないと削除されません。削除したデータは元に戻せないため、先にバックアップを作成することをおすすめします。", 10)
    }
}

private val DEFAULT_SELECTION = setOf("AppConfig", "StopCoord", "RouteGeometry", "VehicleRegistry", "VehicleRun", "PositionLog", "TransitStop", "TrafficDataSource", "TrafficSurveyLocation", "ServiceConnection", "AutomationRule")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupPanel(admin: Boolean) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val meta = produceState(emptyList<JsonElement>()) { value = Logic.constant("monitor/entities", "ENTITY_META").items() }.value
    val restorable = produceState(emptyList<String>()) { value = Logic.constant("monitor/entities", "RESTORABLE").items().map { it.text } }.value
    val selected = remember { mutableStateMapOf<String, Boolean>().apply { DEFAULT_SELECTION.forEach { put(it, true) } } }
    var limit by remember { mutableStateOf(500) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var restoreResult by remember { mutableStateOf<JsonElement?>(null) }
    var recVersion by remember { mutableStateOf(0) }
    val records = rememberLoad("backup-records", recVersion, admin) { if (!admin) emptyList() else Api.entityList("BackupRecord", sort = "-at", limit = 50) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        error = ""; restoreResult = null
        scope.launch {
            try {
                val text = context.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val parsed = Json.parse(text)
                val entities = parsed.objAt("entities") ?: throw IllegalArgumentException("バックアップファイルの形式ではありません")
                confirm.ask("バックアップから復元しますか？\n${u.lastPathSegment.orEmpty()}\n既存のデータは上書きせず、足りない分だけ追加します。履歴系のデータは対象外です。") {
                    busy = true
                    scope.launch { try { restoreResult = Api.rpc("monitor/server-fns#restoreBackup", jsonOf("payload" to jsonOf("entities" to entities), "confirm" to true)) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "復元できませんでした" } } finally { busy = false } }
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.message ?: "ファイルを読み込めませんでした" }
        }
    }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            meta.filter { it.str("name") != "BackupRecord" }.forEach { m ->
                val n = m.str("name").orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(selected[n] == true, { selected[n] = it }); Text(m.str("label").orEmpty(), fontSize = 11.sp); Spacer4() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectField(listOf(200, 500, 1000, 2000).map { it to "1種類あたり${it}件" }, limit, { limit = it })
            Muted("選択中 ${selected.count { it.value }}種類", 11)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            SmallButton(if (busy) "作成中…" else "バックアップを作成", {
                busy = true; error = ""; message = ""
                scope.launch {
                    try {
                        val res = Api.rpc("monitor/server-fns#exportBackup", jsonOf("entities" to selected.filterValues { it }.keys.toList(), "limitPerEntity" to limit))
                        val json = res.toString(); val bytes = json.toByteArray(Charsets.UTF_8)
                        val name = "busloca-backup-${ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).format(STAMP)}.json"
                        val where = Export.save(context, name, "application/json", bytes)
                        val fileUri = Api.uploadPrivateFile(name, "application/json", bytes)
                        val trunc = res.list("truncated").map { it.text }
                        Api.entityCreate("BackupRecord", jsonOf("name" to name, "at" to res.long("at"), "entities" to (res.objAt("entities")?.keys?.toList() ?: emptyList<String>()).let { JsonArray(it.map { s -> kotlinx.serialization.json.JsonPrimitive(s) }).toString() },
                            "counts" to (res.obj?.get("counts") ?: JsonObject(emptyMap())).toString(), "rows_total" to res.long("total"), "bytes" to bytes.size, "file_uri" to fileUri, "note" to if (trunc.isNotEmpty()) "件数上限で切り捨て: ${trunc.joinToString(", ")}" else ""))
                        recVersion++
                        message = "${res.long("total") ?: 0}件を保存しました（${res.objAt("entities")?.size ?: 0}種類・${kb(bytes.size.toDouble())}）。$where に保存し、一覧にも記録しました。"
                    } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "バックアップを作成できませんでした" } } finally { busy = false }
                }
            }, enabled = !busy && admin && selected.any { it.value }, icon = Icons.Outlined.Backup, primary = true)
            SmallButton("ファイルから復元", { picker.launch(arrayOf("application/json", "*/*")) }, enabled = !busy && admin, icon = Icons.Outlined.Upload)
        }
        if (!admin) Muted("バックアップと復元は管理者のみ実行できます。", 11)
        if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = Brand.Success)
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        restoreResult?.let { r ->
            Text("復元しました", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Success)
            r.objAt("created")?.forEach { (n, c) -> Muted("$n：${c.text}件を追加", 11) }
            r.items("skipped").forEach { Muted("${it.str("name")}：${it.str("reason")}", 11) }
            r.items("errors").forEach { Text("${it.str("name")}：${it.str("message")}", fontSize = 11.sp, color = Brand.Destructive) }
        }
        val recs = records.data.orEmpty()
        recs.forEachIndexed { i, rec ->
            if (i > 0) RowDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(rec.str("name").orEmpty(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Muted("${BusFormat.dateTime(rec.long("at"))}・${rec.long("rows_total") ?: 0}件・${kb(rec.dbl("bytes"))}", 10)
                    rec.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
                }
                TextButton(onClick = { scope.launch { try { uri.openUri(Api.signedUrl(rec.str("file_uri").orEmpty())) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "ダウンロード用のURLを作成できませんでした" } } } }) { Text("取得", fontSize = 11.sp) }
                TextButton(onClick = { confirm.ask("このバックアップを一覧から削除しますか？\n${rec.str("name")}\n一覧の記録を削除します。保存済みのファイルはストレージに残ります。") { scope.launch { runCatching { Api.entityDelete("BackupRecord", rec.str("id").orEmpty()) }.onFailure { error = it.userMessage() }; recVersion++ } } }) { Text("削除", fontSize = 11.sp, color = Brand.Destructive) }
            }
        }
        if (recs.isEmpty()) Muted(if (admin) "バックアップはまだありません" else "管理者のみ表示できます", 11)
        Muted("バックアップは選択したエンティティをJSONで保存します（1種類あたり最大${limit}件、全体で6000件まで）。ファイルは端末のダウンロードに保存し、同時に非公開ストレージへ保存して一覧から再取得できるようにします。復元は既存データを上書きせず、足りない分だけ追加します。対象は${restorable.joinToString("・") { n -> meta.firstOrNull { it.str("name") == n }?.str("label") ?: n }}の${restorable.size}種類で、位置履歴・運行記録などの履歴は重複を防ぐため復元対象外です。", 10)
    }
}

@Composable private fun Spacer4() = androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))

private val TOGGLES = listOf(Triple("weather", "天気の通知", "気温の急変・高温・低温・強風・雨の予測"), Triple("rain", "雨雲の通知", "雨雲の接近・30分以内の降雨・強い雨・雨が止む見込み"), Triple("bus", "バスの通知", "お気に入り車両・乗車予定の接近、運行情報の更新"), Triple("report", "定期レポート", "朝・昼・夕方に天気と予定をまとめて通知します"))
private val REPORTS = listOf(Triple("morning_enabled", "morning_time", "朝のレポート"), Triple("noon_enabled", "noon_time", "昼のレポート"), Triple("evening_enabled", "evening_time", "夕方のレポート"))

/** 通知の設定（Web: settings.notify.jsx + NotifySettingsForm.jsx） */
@Composable
fun NotifySettingsScreen() {
    val scope = rememberCoroutineScope()
    val q = rememberLoad("notify-prefs") { Api.rpc("notify/fns#getNotifyPrefs", jsonOf()) }
    val prefs = remember { mutableStateMapOf<String, Any?>() }
    LaunchedEffect(q.data) { q.data?.obj?.forEach { (k, v) -> prefs[k] = (v as? kotlinx.serialization.json.JsonPrimitive)?.let { p -> if (p.isString) p.content else p.content.toBooleanStrictOrNull() ?: p.content.toIntOrNull() } } }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    ScreenList {
        item { SettingsHeader("/settings/notify") }
        item { Muted("この端末への通知の登録と、受け取る通知の種類・時間帯を設定します。", 12) }
        item { PushStatusCard() }
        item {
            Panel(title = "受け取る通知", subtitle = "種類ごとにオン・オフできます。") {
                if (q.data == null) { Muted(q.error ?: "読み込み中…", 12); return@Panel }
                TOGGLES.forEach { (k, l, h) -> ToggleRow(l, h, prefs[k] == true, { prefs[k] = it }) }
                Text("定期レポートの時刻", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Muted("アプリを開いていなくても届きます（日本時間）。レポート全体を止めるときは上の「定期レポート」をオフにしてください。", 10)
                REPORTS.forEach { (ek, tk, l) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(l, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TimeField((prefs[tk] as? String).orEmpty(), { prefs[tk] = it })
                        androidx.compose.material3.Switch(prefs[ek] == true, { prefs[ek] = it })
                    }
                }
                Text("通知しない時間帯", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Muted("就寝中など、通知を止める時間帯を指定できます。", 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField((prefs["quiet_from"] as? Int)?.toString().orEmpty(), { prefs["quiet_from"] = it.filter(Char::isDigit).take(2).toIntOrNull()?.coerceIn(0, 23) }, singleLine = true, modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(" 時 〜 ", fontSize = 12.sp)
                    OutlinedTextField((prefs["quiet_to"] as? Int)?.toString().orEmpty(), { prefs["quiet_to"] = it.filter(Char::isDigit).take(2).toIntOrNull()?.coerceIn(0, 23) }, singleLine = true, modifier = Modifier.width(72.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text(" 時", fontSize = 12.sp)
                }
                Muted("空欄なら時間帯の指定なし", 10)
                if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = Brand.Success)
                if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
                SmallButton(if (busy) "保存中…" else "設定を保存", {
                    busy = true; message = ""; error = ""
                    scope.launch {
                        try {
                            Api.rpc("notify/fns#saveNotifyPrefs", jsonOf("weather" to prefs["weather"], "rain" to prefs["rain"], "bus" to prefs["bus"], "report" to prefs["report"],
                                "morning_enabled" to prefs["morning_enabled"], "morning_time" to prefs["morning_time"], "noon_enabled" to prefs["noon_enabled"], "noon_time" to prefs["noon_time"],
                                "evening_enabled" to prefs["evening_enabled"], "evening_time" to prefs["evening_time"], "quiet_from" to prefs["quiet_from"], "quiet_to" to prefs["quiet_to"]))
                            message = "通知の設定を保存しました。"
                        } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
                    }
                }, enabled = !busy, icon = Icons.Outlined.Save, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Panel(title = "アプリの通知について") {
                Muted("このアプリはインストール済みのため、ホーム画面への追加は不要です。通知が届かない場合は、端末の設定 → アプリ → 奈良交通バスロケ監視 → 通知 がオンになっているか確認してください。", 11)
            }
        }
    }
}

