package app.narabuspulse.feature.collect

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.BuildConfig
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.BusRepository
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.LoadState
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private val STATE_LABEL = mapOf("running" to ("運行中" to Brand.Success), "no_service" to ("運用情報なし" to Brand.MutedFg), "error" to ("取得失敗" to Brand.Destructive))
private val MARKER = Color(0xFFD94F2B)

/** 収集状況を読み、画面を開いている間は設定した間隔を超えたら自動で収集する（Web: useCollection.js）。 */
@Composable
private fun rememberCollection(autoRun: Boolean = true): Pair<LoadState<JsonElement>, suspend (Boolean) -> JsonElement> {
    val load = rememberLoad { Api.rpc("collect/server-fns#getCollectionOverview", jsonOf()) }
    var lastTry by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(load) { while (true) { delay(45_000); load.reload() } }
    LaunchedEffect(load, autoRun) {
        if (!autoRun) return@LaunchedEffect
        while (true) {
            val o = load.data
            if (o != null && o.bool("enabled") == true && !running && System.currentTimeMillis() - lastTry >= 45_000) {
                val interval = (o.int("intervalMinutes") ?: 5) * 60_000L
                val last = o.long("lastRunAt") ?: 0
                if (last == 0L || System.currentTimeMillis() - last >= interval) {
                    running = true; lastTry = System.currentTimeMillis()
                    try {
                        val r = Api.rpc("collect/server-fns#runCollectionNow", jsonOf("trigger" to "auto", "force" to false))
                        load.reload(); if (r.bool("skipped") != true) BusRepository.invalidate()
                    } catch (e: Throwable) { if (e is CancellationException) throw e } finally { running = false }
                }
            }
            delay(20_000)
        }
    }
    return load to { force: Boolean ->
        val r = Api.rpc("collect/server-fns#runCollectionNow", jsonOf("trigger" to "manual", "force" to force))
        load.reload(); if (r.bool("skipped") != true) BusRepository.invalidate(); r
    }
}

@Composable
private fun StatusCards(o: JsonElement?) {
    val states = o.items("states")
    val c = states.groupingBy { it.str("status") }.eachCount()
    KpiRow(Triple("運行中（実測）", "${c["running"] ?: 0}", Brand.Success), Triple("運用情報なし", "${c["no_service"] ?: 0}", Brand.MutedFg),
        Triple("取得失敗", "${c["error"] ?: 0}", Brand.Destructive), Triple("今日の蓄積", o.str("snapshotToday") ?: "—", INFO), units = listOf("台", "台", "台", "件"))
    Muted(o.long("lastSnapshotAt")?.let { "最終 ${BusFormat.elapsed(it)}" } ?: "まだ蓄積なし", 10)
    if ((c["error"] ?: 0) > 0) Text("⚠ 取得失敗は「運行終了」ではありません。下の車両一覧で最終成功時刻を確認してください。", fontSize = 11.sp, color = Brand.Destructive)
}

@Composable
private fun LiveStateList(states: List<JsonElement>) {
    Panel(title = "最新の実測状態") {
        if (states.isEmpty()) { Muted("まだ取得できていません。収集を実行してください。", 11); return@Panel }
        states.forEach { s ->
            val (l, c) = STATE_LABEL[s.str("status")] ?: (s.str("status").orEmpty() to Brand.MutedFg)
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.str("car_code").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Tag(l, c)
                    s.str("route")?.takeIf { it.isNotBlank() }?.let { Text("系統 $it", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Text(s.str("destination") ?: "—", fontSize = 11.sp, color = Brand.MutedFg, maxLines = 1)
                }
                Muted(listOfNotNull("停車 ${s.str("stop_name") ?: "—"}", "停留所順 ${s.str("route_seq") ?: "—"} / ${s.int("stop_count") ?: 0}件",
                    if (s.str("status") == "running" && s.dbl("lat") != null && s.dbl("lon") != null) "%.5f, %.5f".format(s.dbl("lat"), s.dbl("lon")) else null,
                    "観測 ${BusFormat.dateTime(s.long("last_ok_at") ?: s.long("at"))}").joinToString("　"), 10)
                if (s.str("status") == "error") s.str("last_error")?.let { Text(it, fontSize = 10.sp, color = Brand.Destructive) }
            }
        }
    }
}

private fun splitTypical(clusters: List<JsonElement>, now: Int): Pair<JsonElement?, JsonElement?> {
    val list = clusters.sortedBy { it.int("minutes") ?: 0 }
    return list.lastOrNull { (it.int("minutes") ?: 0) <= now } to list.firstOrNull { (it.int("minutes") ?: 0) > now }
}
private fun labelDate(iso: String?): String = Regex("^\\d{4}-(\\d{2})-(\\d{2})$").find(iso.orEmpty())?.let { "${it.groupValues[1].toInt()}月${it.groupValues[2].toInt()}日" } ?: iso.orEmpty()

/** 次運用予測（Web: NextOperationsCard.jsx + NextOperationRow.jsx）。 */
@Composable
private fun NextOperationsCard() {
    val load = rememberLoad { Api.rpc("collect/server-fns#getDailyOperationPlan", jsonOf()) }
    var filter by remember { mutableStateOf("next") }
    var openName by remember { mutableStateOf("") }
    val d = load.data
    val now = d.int("nowMinutes") ?: 0
    val items = d.items("items")
    val dayCount = d.int("sheetCount") ?: 0
    val nextCount = items.count { splitTypical(it.list("clusters"), now).second != null }
    val rows = items.map { it to splitTypical(it.list("clusters"), now) }.filter { filter != "next" || it.second.second != null }
        .sortedWith(compareBy<Pair<JsonElement, Pair<JsonElement?, JsonElement?>>> { it.second.second?.int("minutes") ?: 9999 }.thenBy { it.first.str("name").orEmpty() })
    Panel(title = "🗓 次運用予測（同じ種別の過去運用表を集計）", subtitle = d.objAtService()?.let { "$it の過去 ${dayCount}日分（${labelDate(d.str("firstDate"))}〜${labelDate(d.str("lastDate"))}）をひとまとめに集計／車両 ${items.size}台・次便あり ${nextCount}台・マスター一致 ${d.int("matched") ?: 0}台" } ?: "運用表を集計しています",
        action = { Row { SmallButton("次便あり", { filter = "next" }, primary = filter == "next"); SmallButton("全車両", { filter = "all" }, primary = filter == "all", modifier = Modifier.padding(start = 4.dp)); TextButton(onClick = { load.reload() }, enabled = !load.loading) { Text("↻") } } }) {
        if (load.loading && d == null) Muted("集計中…", 12)
        load.error?.let { Text("運用表を集計できませんでした: $it", fontSize = 12.sp, color = Brand.Destructive) }
        if (d != null && d.bool("configured") == false) Muted("設定画面でスプレッドシートを保存すると、運用表から予測します。", 12)
        if (d?.bool("missing") == true) Muted("${labelDate(d.str("wantedDate"))}（${d.str("service.label")}）と同じ種別の運用表が1枚も見つかりませんでした。", 12)
        if (d != null && d.bool("missing") != true) {
            if (d.bool("hasToday") != true) Text("本日（${labelDate(d.str("wantedDate"))}・${d.str("service.label")}）の運用表はまだ無いため、過去の同じ種別の実績だけから予測しています。", fontSize = 11.sp, color = INFO)
            if (dayCount < 3) Text("同じ種別の運用表が ${dayCount}日分しかないため、参考値として見てください。", fontSize = 11.sp, color = Brand.Warning)
            if (rows.isEmpty()) Muted("該当する車両がありません。", 12)
            rows.forEach { (item, plan) ->
                val name = item.str("name").orEmpty()
                val (cur, next) = plan
                val (sl, sc) = STATE_LABEL[item.str("status")] ?: ((if (item.bool("registered") == true) "実測なし" else "車両未登録") to Brand.MutedFg)
                fun ratio(c: JsonElement?) = "${c.int("support") ?: 0}/${if (dayCount > 0) dayCount else item.int("supportDays") ?: 0}日"
                Column(Modifier.fillMaxWidth().clickable { openName = if (openName == name) "" else name }.padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold); item.str("car_code")?.takeIf { it.isNotBlank() }?.let { Tag(it, Brand.MutedFg) }; Tag(sl, sc)
                            }
                            val live = item.objAtLive()
                            Muted(if (live != null && (live.str("route") != null || live.str("stop_name") != null)) "実測 ${live.str("route")?.let { "$it " } ?: ""}${live.str("destination").orEmpty()}${live.str("stop_name")?.let { "・$it" } ?: ""}" else "実測の運用情報なし", 11)
                        }
                        Column(Modifier.width(70.dp), horizontalAlignment = Alignment.End) { Muted("現在", 10); Text(cur.str("time") ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted(cur.str("route").orEmpty(), 10) }
                        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
                            Text("次便", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MARKER); Text(next.str("time") ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MARKER)
                            Muted(if (next != null) "あと${(next.int("minutes") ?: now) - now}分・${ratio(next)}" else "予定終了", 10)
                        }
                    }
                    if (openName == name) Column(Modifier.padding(top = 4.dp)) {
                        item.list("clusters").forEach { c ->
                            val isCur = cur != null && cur.str("time") == c.str("time") && cur.str("operation") == c.str("operation")
                            val isNext = next != null && next.str("time") == c.str("time") && next.str("operation") == c.str("operation")
                            val passed = (c.int("minutes") ?: 0) <= now && !isCur
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(c.str("time").orEmpty(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (passed) Brand.MutedFg else if (isCur) MARKER else if (isNext) Brand.Warning else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(40.dp))
                                c.str("route")?.takeIf { it.isNotBlank() }?.let { Tag(it, Brand.MutedFg) }
                                Text("${c.str("operation").orEmpty()}${c.str("kind")?.takeIf { it.isNotBlank() }?.let { "［$it］" } ?: ""}", fontSize = 11.sp, color = if (passed) Brand.MutedFg else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Muted(ratio(c), 10)
                            }
                        }
                        Muted("同じ種別の過去 ${if (dayCount > 0) dayCount else item.int("supportDays") ?: 0}日分をまとめた予測です（右端は支持日数）。", 10)
                    }
                }
            }
        }
        Muted("同じ種別の各日の運用表を日付ごとに突き合わせ、同じ運用・同じ時間帯をひとまとめにしています（最大30日分）。時刻はそのまとまりの中央値、支持日数は対象日のうち何日に現れたかを示します。遅延や当日の変更は反映しません。", 10)
    }
}
private fun JsonElement?.objAtService(): String? = str("service.label")
private fun JsonElement?.objAtLive(): JsonElement? = (this as? kotlinx.serialization.json.JsonObject)?.get("live")?.takeIf { it is kotlinx.serialization.json.JsonObject }

/** データ収集・品質管理（Web: collection.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val (load, runNow) = rememberCollection()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    fun guard(label: String, block: suspend () -> JsonElement) {
        busy = true; message = ""; problem = ""
        scope.launch {
            try { val r = block(); message = if (r.bool("skipped") == true) "$label：${r.str("message") ?: "実行条件を満たしていないため実行しませんでした"}" else "$label：完了しました"; load.reload() }
            catch (e: Throwable) { if (e is CancellationException) throw e; problem = e.userMessage().ifBlank { "${label}に失敗しました" } } finally { busy = false }
        }
    }
    val o = load.data
    ScreenList {
        item { Heading("データ収集・品質管理", "奈良交通APIから登録車両すべての実測値（位置・系統・行先・停留所）を取得し、DBへ蓄積します。取得できない時間と運行終了は区別して記録します。") }
        if (load.loading && o == null) item { Muted("読み込み中…", 12) }
        load.error?.let { item { Text("収集状況を読み込めませんでした: $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (o != null) {
            item { StatusCards(o) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(if (busy) "実行中…" else "今すぐ収集", { guard("収集の実行") { runNow(true) } }, enabled = !busy, icon = Icons.Outlined.Refresh, primary = true)
                    o.str("lastStatus")?.let { Muted("前回 ${when (it) { "ok" -> "正常"; "partial" -> "一部失敗"; "failed" -> "失敗"; else -> "スキップ" }} ／ 累計 ${o.str("snapshotTotal") ?: "—"} 件を蓄積", 11) }
                }
                if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = Brand.Success)
                if (problem.isNotBlank()) Text(problem, fontSize = 11.sp, color = Brand.Destructive)
                o.str("lastError")?.takeIf { it.isNotBlank() }?.let { Text("直近のエラー: $it", fontSize = 11.sp, color = Brand.Warning) }
            }
            item {
                var enabled by remember(o.bool("enabled")) { mutableStateOf(o.bool("enabled") == true) }
                var interval by remember(o.int("intervalMinutes")) { mutableStateOf(o.int("intervalMinutes") ?: 5) }
                var maxV by remember(o.int("maxVehicles")) { mutableStateOf((o.int("maxVehicles") ?: 40).toString()) }
                val presets = (listOf(1, 2, 3, 5, 10, 15, 30, 60) + interval).distinct().sorted()
                Panel(title = "収集設定", subtitle = "奈良交通APIから登録車両すべての実測値を取得し、DBへ蓄積します。間隔を短くするほど地図の更新が早くなります（1分〜）。", action = { Switch(enabled, { enabled = it }) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectField(presets.map { it to "${it}分ごと" }, interval, { interval = it }, label = "取得間隔")
                        OutlinedTextField(maxV, { maxV = it.filter(Char::isDigit).take(2) }, label = { Text("1回の取得上限（台）", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallButton("設定を保存", { guard("設定の保存") { Api.rpc("collect/server-fns#saveCollectSettings", jsonOf("enabled" to enabled, "intervalMinutes" to interval, "maxVehicles" to (maxV.toIntOrNull() ?: 40).coerceIn(1, 60))) } }, enabled = !busy, primary = true)
                        Muted("  保存できるのは管理者のみです", 10)
                    }
                }
            }
            item {
                var code by remember { mutableStateOf("") }
                val registry = o.items("registry")
                Panel(title = "車両マスター", action = { Muted("${registry.size} 台登録", 11) }) {
                    if (registry.isEmpty()) Muted("まだ車両が登録されていません。スプレッドシートを設定して収集を実行すると、監視シートの車両URLから車番が自動登録されます。", 11)
                    registry.forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(v.str("car_code").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                            Text(v.str("vehicle_name") ?: "—", fontSize = 11.sp, color = Brand.MutedFg, modifier = Modifier.weight(1f), maxLines = 1)
                            Muted(if (!v.str("last_error").isNullOrBlank()) "失敗 ${BusFormat.elapsed(v.long("last_error_at"))}" else v.long("last_success_at")?.let { "成功 ${BusFormat.elapsed(it)}" } ?: "未取得", 10)
                            SmallButton(if (v.bool("enabled") == true) "収集ON" else "停止中", { guard("収集対象の変更") { Api.rpc("collect/server-fns#setRegistryEnabled", jsonOf("car_code" to v.str("car_code"), "enabled" to (v.bool("enabled") != true))) } }, enabled = !busy, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, placeholder = { Text("車番を追加（例 506）", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        SmallButton("追加", { val c = code; code = ""; guard("車両の登録") { Api.rpc("collect/server-fns#addRegistryVehicle", jsonOf("car_code" to c)) } }, enabled = !busy && code.isNotBlank(), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            item { LiveStateList(o.items("states")) }
            item {
                val logs = o.items("logs")
                Panel(title = "取得履歴") {
                    if (logs.isEmpty()) Muted("実行履歴はまだありません。", 11)
                    logs.forEach { log ->
                        val failed = log.int("failed") ?: 0
                        val tone = if (failed > 0) Brand.Warning else if ((log.int("vehicles") ?: 0) == 0) Brand.MutedFg else Brand.Success
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text("${BusFormat.dateTime(log.long("started_at"))}　${mapOf("manual" to "手動", "auto" to "自動", "cron" to "定期")[log.str("trigger")] ?: log.str("trigger") ?: "—"}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${log.int("vehicles") ?: 0}台中 成功${log.int("ok") ?: 0} / なし${log.int("no_service") ?: 0} / 失敗$failed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tone)
                            Muted("新規${log.int("new_snapshots") ?: 0}件・重複${log.int("duplicates") ?: 0}件・${"%.1f".format((log.dbl("duration_ms") ?: 0.0) / 1000)}秒", 10)
                            log.str("note")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 10.sp, color = Brand.Warning) }
                        }
                    }
                }
            }
            item {
                val url = "${BuildConfig.BASE_URL.trimEnd('/')}/api/collect"
                Panel(title = "画面を閉じている間の収集", subtitle = "次のURLへ POST すると収集が実行されます。設定した間隔内の呼び出しは何もせず、実行中の多重呼び出しもロックで止まります。GPSを止めずに更新し続けるには、このURLを外部の定期実行（cron など）に登録してください。") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(url, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        SmallButton("コピー", { clipboard.setText(AnnotatedString(url)); message = "URLをコピーしました" }, icon = Icons.Outlined.ContentCopy)
                    }
                    Muted("最終実行 ${BusFormat.dateTime(o.long("lastRunAt"))}。秘密トークン（COLLECT_TOKEN）を設定した場合は、ヘッダー x-collect-token が必要になります。", 10)
                }
            }
        }
    }
}

private val AI_LINKS = listOf("/collection" to "データ収集・品質", "/vehicles" to "車両位置", "/status" to "車両状態一覧", "/shoot/light" to "AI撮影支援", "/analysis" to "時間帯別分析")

/** AI運行ダッシュボード（Web: ai.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiDashboardScreen() {
    val nav = LocalNav.current
    val (load, _) = rememberCollection()
    val o = load.data
    ScreenList {
        item { Heading("AI運行ダッシュボード", "実際に取得した実測データのみを表示します。予測・精度評価は、予測結果の記録と実測との突き合わせが動き始めてから数値を出します。") }
        if (load.loading && o == null) item { Muted("読み込み中…", 12) }
        load.error?.let { item { Text("読み込めませんでした: $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (o != null) {
            item {
                val states = o.items("states")
                KpiRow(Triple("登録車両", "${o.items("registry").size}", null), Triple("運行中（実測）", "${states.count { it.str("status") == "running" }}", Brand.Success),
                    Triple("運用情報なし", "${states.count { it.str("status") == "no_service" }}", Brand.MutedFg), units = listOf("台", "台", "台"))
                KpiRow(Triple("取得失敗", "${states.count { it.str("status") == "error" }}", Brand.Destructive), Triple("今日の蓄積", o.str("snapshotToday") ?: "—", INFO), units = listOf("台", "件"))
            }
            item { NextOperationsCard() }
            item {
                Panel(title = "データ蓄積状況") {
                    listOf("累計スナップショット" to "${o.str("snapshotTotal") ?: "—"} 件",
                        "最終取得" to (o.long("lastSnapshotAt")?.let { "${BusFormat.dateTime(it)}（${BusFormat.elapsed(it)}）" } ?: "—"),
                        "最終実行" to (o.long("lastRunAt")?.let { BusFormat.dateTime(it) } ?: "—"), "実行間隔" to "${o.int("intervalMinutes") ?: 5} 分", "自動収集" to if (o.bool("enabled") == true) "有効" else "無効").forEach { (k, v) ->
                        Row { Text(k, fontSize = 12.sp, color = Brand.MutedFg, modifier = Modifier.weight(1f)); Text(v, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End) }
                    }
                    Muted("車両マスターの車番は監視シートのURL列から自動登録されます。未知の車番へは問い合わせません。", 10)
                }
            }
            item {
                Panel(title = "ℹ 予測・精度評価の状態") {
                    Muted("次運用予測は、同じ種別（平日・土曜・日祝）の過去の日別運用表をまとめ、同じ運用・同じ時間帯をひとまとめにして表示しています。未来位置予測・異常検知・予測精度の評価は未実装のため、数値は表示しません（架空の精度を出さないため）。", 11)
                    Muted("未来位置予測には同一車両の連続した観測（位置・停留所通過・所要時間）が必要です。蓄積が進むほど精度を評価できるようになります。", 11)
                    TextButton(onClick = { nav.go("/collection") }) { Text("収集状況と履歴を見る →", fontSize = 11.sp) }
                }
            }
            item { LiveStateList(o.items("states")) }
            item {
                Panel(title = "関連画面") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { AI_LINKS.forEach { (to, l) -> SmallButton(l, { nav.go(to) }) } }
                    Muted("📷 撮影支援の計算は既存の太陽位置・道路形状・気象の各画面で動作しています。", 10)
                }
            }
        }
    }
}
